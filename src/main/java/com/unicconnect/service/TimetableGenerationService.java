package com.unicconnect.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.unicconnect.dto.request.CreateGenerationRequest;
import com.unicconnect.dto.request.DragStatusRequest;
import com.unicconnect.dto.request.GenerateTimetableRequest;
import com.unicconnect.dto.request.SwapAnimRequest;import com.unicconnect.dto.response.GenerationManageResponse;
import com.unicconnect.dto.response.GenerationScopeSemester;
import com.unicconnect.dto.response.GenerationSessionResponse;
import com.unicconnect.dto.response.GenerationSnapshotResponse;
import com.unicconnect.dto.response.ScheduleResponse;
import com.unicconnect.entity.*;
import com.unicconnect.exception.BusinessRuleException;
import com.unicconnect.exception.ResourceNotFoundException;
import com.unicconnect.exception.TimetableConflictException;
import com.unicconnect.repository.*;
import jakarta.annotation.PreDestroy;
import org.hibernate.Hibernate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Constraint-aware timetable generator with backtracking, driven by
 * {@code course_meeting_requirements}.
 *
 * <p>Placement rules:
 * <ul>
 *   <li>Scope: teaching assignments are filtered by the generation term and by the
 *       selected Mid/Final exam type (Mid Term = semesters 1/3/5/7, Final Term =
 *       2/4/6/8, resolved from {@code semesters.semester_no}) and by the explicit
 *       semester/section selections made by the lobby creator.</li>
 *   <li>Course sessions are placed via backtracking search (Monday-Friday, 6 real
 *       time slots). Sessions for the same unit never share a day; consecutive-period
 *       sessions are allocated as Period X + Period X+1.</li>
 *   <li>No lecturer overlap, section overlap, or slot overlap.</li>
 *   <li>LECTURE sessions are placed before LAB sessions.</li>
 *   <li>Only LMS and ASSIGNMENT special periods are placed on free slots.
 *       No BREAK schedule rows are ever created (lunch is a slot gap).</li>
 *   <li>Generation is all-or-nothing: if any required session cannot be placed the
 *       transaction rolls back and a precise report is thrown.</li>
 *   <li>Combined-section courses (A+B+C etc.) are taught as ONE unit: an HOD groups
 *       the section assignments of a course (teaching_assignment_groups) and the
 *       generator places a single {@code class_schedules} row for the whole group.</li>
 * </ul>
 */
@Service
@Transactional
public class TimetableGenerationService {

    private static final Logger log = LoggerFactory.getLogger(TimetableGenerationService.class);
    private static final int WORKING_DAY_START = 1;
    private static final int WORKING_DAY_END = 5;
    private static final String[] DAY_NAMES = {
            "", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"};
    private static final LocalTime LUNCH_END = LocalTime.of(12, 0);
    private static final LocalTime LUNCH_START = LocalTime.of(13, 0);
    // ========== GENERATION CONTEXT (preloaded once) ==========
    /**
     * Holds ALL data needed for generation, preloaded once from the database.
     * No repository calls happen inside the solver â€” only this context is used.
     */
    private record GenerationContext(
            UUID termId,
            Map<UUID, Set<UUID>> scope,
            List<TeachingAssignment> assignments,
            List<TeachingAssignment> singletons,
            Map<UUID, List<TeachingAssignmentGroupMember>> membersByGroup,
            Map<UUID, List<CourseMeetingRequirement>> requirementsByCourse,
            List<TimeSlot> timeSlots,
            // Derived indexes
            Map<UUID, List<SchedulingUnit>> unitsBySemester,
            Set<UUID> groupedAssignmentIds
    ) {}

    // ========== SOLVER LIMITS (configurable) ==========
    /** Maximum wall-clock time (ms) for the entire generation (all semesters). */
    private static final long GENERATION_MAX_TIME_MS = 120_000L;
    /** Maximum backtracking nodes per individual solver attempt. */
    private static final int MAX_NODES_PER_ATTEMPT = 300_000;
    /** Maximum solver restart attempts per elective combination. */
    private static final int MAX_SOLVE_ATTEMPTS = 2;
    /** Maximum elective-group combinations to try per semester. */
    private static final int MAX_ELECTIVE_COMBINATION_ATTEMPTS = 200;
    /** Per-semester wall-clock time limit (ms). Must be <= GENERATION_MAX_TIME_MS. */
    private static final long SEMESTER_MAX_TIME_MS = 60_000L;
    private static final ThreadLocal<Random> SOLVE_RANDOM =
            ThreadLocal.withInitial(() -> new Random(0x5EED));

    // ========== CANDIDATE SCORING / CAPACITY RESERVATION ==========
    /**
     * Weight (soft, ordering-only) applied to the consecutive-window preservation
     * penalty inside {@link #evaluatePlacement}. Hard constraints (canPlace) still
     * reject invalid candidates first; this only re-orders valid candidates so
     * that placements destroying fewer free consecutive runs (P1-P2..P5-P6) are
     * preferred, preserving capacity for later 2-period sessions. 0 disables it.
     */
    private static final int CONSECUTIVE_PRESERVATION_WEIGHT = 5;

    // ========== EXP-ONLY INSTRUMENTATION / ABLATION GATES ==========
    // These are read from environment variables and are OFF by default so that
    // production behavior is byte-for-byte unchanged when the env vars are unset.
    // Used only for the read-only Sem4 failure investigation (Experiments A-F).
    // No production behavior is modified in the default (env-unset) path.
    static final boolean INSTR_TRACE =
            "1".equals(System.getenv("EXP_INSTR_TRACE"));
    static final boolean ABLATE_SOFT =
            "1".equals(System.getenv("EXP_ABLATE_SOFT"));
    static final boolean ABLATE_CONSEC =
            "1".equals(System.getenv("EXP_ABLATE_CONSEC"));
    static final boolean ABLATE_DAYBAL =
            "1".equals(System.getenv("EXP_ABLATE_DAYBAL"));
    static final boolean ABLATE_RAND =
            "1".equals(System.getenv("EXP_ABLATE_RAND"));
    static final String SCORE_ABLATE =
            System.getenv("EXP_SCORE_ABLATE"); // "zero" | "daybal" | "consec" | "both" | "current"
    static final String RAND_SEED_OVERRIDE =
            System.getenv("EXP_RAND_SEED"); // decimal seed for Experiment E

    // ========== DIAGNOSTIC COUNTERS (reset per solver run) ==========
    static final class SolverDiagnostics {
        int mrvCalls;
        int mrvUnitsScanned;
        int gvpCalls;            // generateValidPlacements calls (from MRV + final generation)
        int canPlaceCalls;
        int totalOptionsReturned;// sum of options.size() across all GVP calls
        int scoringCalls;        // evaluatePlacement calls
        int nodesExplored;
        int depthMax;
        int[] depthHistogram = new int[80]; // units placed at each depth
        int[] unplacedAtDepth = new int[80]; // unplaced units scanned at each depth
        int[] optionsAtDepth = new int[80]; // options of selected unit at each depth
        int[] gvpCallsAtDepth = new int[80]; // GVP calls at each depth (inside MRV)
        int[] canPlaceAtDepth = new int[80]; // canPlace calls at each depth
        long mrvTimeNs;
        long scoringTimeNs;
        long gvpTimeNs;
        long canPlaceTimeNs;
        int placements;        // successful grid placements committed during search
        int backtracks;        // placements undone during search

        // Elective combo diagnostics
        int electiveGroupsEnumerated;
        int rawCandidatesTotal;
        int filteredCandidatesTotal;
        int combosAttempted;
        int combosPrunedCrossSection;
        int combosPrunedFeasibility;
        int combosSolved;
        int combosFailedSolver;
        List<String> comboReasons = new ArrayList<>();

        // Candidate cache diagnostics
        int cacheHits;
        int cacheMisses;
        int cacheInvalidations;

        // True while any unplaced combined (linked) group remains, i.e. the
        // "Section A anchors combined slots first" phase is still active.
        boolean anchorPhase;

        // Quick feasibility check diagnostics
        int feasibilityChecks;
        int feasibilityPrunes;

        void reset() {
            mrvCalls = 0; mrvUnitsScanned = 0; gvpCalls = 0; canPlaceCalls = 0;
            totalOptionsReturned = 0; scoringCalls = 0; nodesExplored = 0;
            depthMax = 0; mrvTimeNs = 0; scoringTimeNs = 0; gvpTimeNs = 0; canPlaceTimeNs = 0;
            placements = 0; backtracks = 0;
            Arrays.fill(depthHistogram, 0); Arrays.fill(unplacedAtDepth, 0);
            Arrays.fill(optionsAtDepth, 0); Arrays.fill(gvpCallsAtDepth, 0);
            Arrays.fill(canPlaceAtDepth, 0);
            electiveGroupsEnumerated = 0; rawCandidatesTotal = 0; filteredCandidatesTotal = 0;
            combosAttempted = 0; combosPrunedCrossSection = 0; combosPrunedFeasibility = 0;
            combosSolved = 0; combosFailedSolver = 0; comboReasons.clear();
            cacheHits = 0; cacheMisses = 0; cacheInvalidations = 0;
            feasibilityChecks = 0; feasibilityPrunes = 0;
            anchorPhase = false;
        }

        void logSummary(String semLabel) {
            log.info("[DIAG {}] nodes={}, mrvCalls={}, mrvUnitsScanned={}, gvpCalls={}, " +
                     "canPlaceCalls={}, totalOptions={}, scoringCalls={}",
                    semLabel, nodesExplored, mrvCalls, mrvUnitsScanned, gvpCalls,
                    canPlaceCalls, totalOptionsReturned, scoringCalls);
            log.info("[DIAG {}] timing: mrv={}ms, gvp={}ms, canPlace={}ms, scoring={}ms",
                    semLabel, mrvTimeNs / 1_000_000, gvpTimeNs / 1_000_000,
                    canPlaceTimeNs / 1_000_000, scoringTimeNs / 1_000_000);
        log.info("[DIAG {}] placements={}, backtracks={}", semLabel, placements, backtracks);
            log.info("[DIAG {}] cache: hits={}, misses={}, invalidations={}",
                    semLabel, cacheHits, cacheMisses, cacheInvalidations);
            log.info("[DIAG {}] feasibility: checks={}, prunes={}",
                    semLabel, feasibilityChecks, feasibilityPrunes);
            log.info("[DIAG {}] electiveCombos: attempted={}, crossSectionPruned={}, " +
                     "feasibilityPruned={}, solved={}, failedSolver={}",
                    semLabel, combosAttempted, combosPrunedCrossSection,
                    combosPrunedFeasibility, combosSolved, combosFailedSolver);
            // Per-depth summary (first 20 depths + last 5)
            StringBuilder sb = new StringBuilder();
            sb.append("[DIAG ").append(semLabel).append("] depth profile (depth: unplaced/units/gvp/canPlace/options): ");
            for (int d = 0; d <= Math.min(depthMax, 20); d++) {
                sb.append(d).append(":").append(unplacedAtDepth[d]).append("/")
                  .append(gvpCallsAtDepth[d]).append("/").append(canPlaceAtDepth[d])
                  .append("/").append(optionsAtDepth[d]).append(" | ");
            }
            if (depthMax > 20) {
                sb.append("... | ");
                for (int d = Math.max(21, depthMax - 4); d <= depthMax; d++) {
                    sb.append(d).append(":").append(unplacedAtDepth[d]).append("/")
                      .append(gvpCallsAtDepth[d]).append("/").append(canPlaceAtDepth[d])
                      .append("/").append(optionsAtDepth[d]).append(" | ");
                }
            }
            log.info("{}", sb);
        }
    }
    private final SolverDiagnostics solverDiag = new SolverDiagnostics();

    /** Bounds [COMBINED-ANCHOR]/[COMBINED-INHERIT] diagnostics: one output per combined group per JVM. */
    private final java.util.Set<String> combinedAnchorEmitted = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private final GenerationSessionRepository generationRepository;
    private final TeachingAssignmentRepository assignmentRepository;
    private final TeachingAssignmentGroupMemberRepository groupMemberRepository;
    private final CourseMeetingRequirementRepository requirementRepository;
    private final TimeSlotRepository timeSlotRepository;
    private final ClassScheduleRepository scheduleRepository;
    private final AcademicTermRepository termRepository;
    private final SemesterRepository semesterRepository;
    private final SectionRepository sectionRepository;
    private final ExamTypeRepository examTypeRepository;
    private final AttendanceRepository attendanceRepository;
    private final HodAccessService hodAccessService;
    private final TimetableLobbyRepository lobbyRepository;
    private final TimetableLobbyAccessService lobbyAccessService;
    private final TimetableRealtimeEventService realtimeEventService;
    private final ObjectMapper objectMapper;
    private final TimetableGenerationService self;
    private final CourseRepository courseRepository;
    private final CurriculumEligibilityService curriculumEligibilityService;
    private final StaffRepository staffRepository;
    private final ExecutorService generationExecutor = Executors.newFixedThreadPool(2);

    public TimetableGenerationService(GenerationSessionRepository generationRepository,
                                      TeachingAssignmentRepository assignmentRepository,
                                      TeachingAssignmentGroupMemberRepository groupMemberRepository,
                                      CourseMeetingRequirementRepository requirementRepository,
                                      TimeSlotRepository timeSlotRepository,
                                      ClassScheduleRepository scheduleRepository,
                                      AcademicTermRepository termRepository,
                                      SemesterRepository semesterRepository,
                                      SectionRepository sectionRepository,
                                      ExamTypeRepository examTypeRepository,
                                      AttendanceRepository attendanceRepository,
                                      HodAccessService hodAccessService,
                                      TimetableLobbyRepository lobbyRepository,
                                      TimetableLobbyAccessService lobbyAccessService,
                                      TimetableRealtimeEventService realtimeEventService,
                                      ObjectMapper objectMapper,
                                      CourseRepository courseRepository,
                                      CurriculumEligibilityService curriculumEligibilityService,
                                      StaffRepository staffRepository,
                                      @Lazy TimetableGenerationService self) {
        this.generationRepository = generationRepository;
        this.assignmentRepository = assignmentRepository;
        this.groupMemberRepository = groupMemberRepository;
        this.requirementRepository = requirementRepository;
        this.timeSlotRepository = timeSlotRepository;
        this.scheduleRepository = scheduleRepository;
        this.termRepository = termRepository;
        this.semesterRepository = semesterRepository;
        this.sectionRepository = sectionRepository;
        this.examTypeRepository = examTypeRepository;
        this.attendanceRepository = attendanceRepository;
        this.hodAccessService = hodAccessService;
        this.lobbyRepository = lobbyRepository;
        this.lobbyAccessService = lobbyAccessService;
        this.realtimeEventService = realtimeEventService;
        this.objectMapper = objectMapper;
        this.courseRepository = courseRepository;
        this.curriculumEligibilityService = curriculumEligibilityService;
        this.staffRepository = staffRepository;
        this.self = self;
    }

    @PreDestroy
    void shutdownGenerationExecutor() {
        generationExecutor.shutdownNow();
    }

    // ========== PUBLIC READ METHODS ==========

    public List<GenerationSessionResponse> getAll(UUID termId) {
        boolean hod = hodAccessService.currentHod().isPresent();
        List<GenerationSession> sessions = termId != null
                ? generationRepository.findByTerm_TermIdOrderByCreatedAtDesc(termId)
                : generationRepository.findAll();
        return sessions.stream()
                .filter(s -> hod || s.getStatus() == GenerationStatus.PUBLISHED)
                .map(TimetableGenerationService::toResponse).toList();
    }

    public GenerationSessionResponse getById(UUID generationId) {
        return toResponse(findGeneration(generationId));
    }

    public List<GenerationScopeSemester> getGenerationScope(UUID termId, UUID examTypeId) {
        Integer parity = resolveParity(examTypeId);
        boolean isMidTerm = parity != null && parity == 1;
        List<Section> masterSections = sectionRepository.findAll().stream()
                .sorted(Comparator.comparing(Section::getSectionName))
                .toList();
        List<GenerationScopeSemester> result = new ArrayList<>();
        List<Semester> ordered = semesterRepository.findAll().stream()
                .sorted(Comparator.comparing(Semester::getSemesterNo))
                .toList();
        for (Semester s : ordered) {
            if (parity != null && s.getSemesterNo() % 2 != parity) continue;
            int semNo = s.getSemesterNo();
            Set<String> midtermAllowed = isMidTerm ? midTermAllowedSections(semNo) : null;
            List<GenerationScopeSemester.SectionInfo> sectionInfos = masterSections.stream()
                    .filter(sec -> semNo <= 2 ? !"CT".equals(sec.getSectionName()) : true)
                    .filter(sec -> midtermAllowed == null
                            || midtermAllowed.contains(sec.getSectionName()))
                    .map(sec -> new GenerationScopeSemester.SectionInfo(
                            sec.getSectionId(), sec.getSectionName()))
                    .toList();
            result.add(new GenerationScopeSemester(s.getSemesterId(), semNo, sectionInfos));
        }
        return result;
    }

    public GenerationManageResponse getManagementContext(UUID termId) {
        Optional<Staff> hod = hodAccessService.currentHod();
        if (hod.isEmpty()) {
            return new GenerationManageResponse(false, false, null);
        }
        GenerationSession draft = null;
        if (termId != null) {
            // Only the generation of the term's ACTIVE lobby may drive the
            // automatic redirect into the shared workspace — and only once the
            // lobby has actually started (GENERATING, i.e. the creator clicked
            // start after every invited HOD joined). An OPEN lobby must keep
            // every HOD on the lobby join page, and leftover drafts from
            // completed or cancelled workflows must never hijack the redirect.
            draft = lobbyRepository
                    .findFirstByStatusInAndTerm_TermId(
                            List.of(LobbyStatus.OPEN, LobbyStatus.GENERATING), termId)
                    .filter(lobby -> lobby.getStatus() == LobbyStatus.GENERATING)
                    .map(TimetableLobby::getGeneration)
                    .filter(g -> g != null && g.getStatus() != GenerationStatus.PUBLISHED)
                    .orElse(null);
        }
        if (draft != null && !lobbyAccessService.canAccessSharedDraft(draft.getGenerationId())) {
            draft = null;
        }
        return new GenerationManageResponse(true, true, draft != null ? toResponse(draft) : null);
    }

    // ========== CREATE / GENERATE ==========

    public GenerationSessionResponse create(CreateGenerationRequest request) {
        AcademicTerm term = termRepository.findById(request.termId())
                .orElseThrow(() -> new ResourceNotFoundException("Academic term not found"));
        Staff generator = hodAccessService.requireHod();
        GenerationSession session = new GenerationSession();
        session.setTerm(term);
        session.setGeneratedByStaff(generator);
        session.setStatus(GenerationStatus.PENDING);
        return toResponse(generationRepository.save(session));
    }

    public GenerationSessionResponse generate(UUID generationId) {
        return doGenerate(generationId, null, null, null, true);
    }

    public GenerationSessionResponse generate(UUID generationId, UUID semesterId) {
        return doGenerate(generationId, semesterId, null, null, true);
    }

    public GenerationSessionResponse generate(UUID generationId, GenerateTimetableRequest request) {
        return doGenerate(generationId,
                null,
                request != null ? request.examTypeId() : null,
                request != null ? request.semesters() : null,
                request == null || request.shouldAutoBindCurriculum());
    }

    // ========== CORE GENERATION (backtracking) ==========

    private GenerationSessionResponse doGenerate(UUID generationId, UUID semesterId, UUID examTypeId,
                                                 List<GenerateTimetableRequest.SemesterSelection> selections,
                                                 boolean autoBindCurriculum) {
        Staff caller = hodAccessService.requireHod();
        GenerationSession generation = findGeneration(generationId);
        if (generation.getStatus() == GenerationStatus.PUBLISHED) {
            throw new BusinessRuleException("A published timetable cannot be regenerated");
        }

        lobbyRepository.findByGeneration_GenerationId(generationId).ifPresent(lobby -> {
            if (lobby.getStatus() == LobbyStatus.OPEN) {
                throw new BusinessRuleException(
                        "Generation is locked until every invited HOD joins the lobby");
            }
            if (lobby.getStatus() != LobbyStatus.COMPLETED
                    && !lobby.getLeaderStaff().getStaffId().equals(caller.getStaffId())) {
                throw new BusinessRuleException(
                        "Only the lobby leader (creator) can change the shared generation scope");
            }
        });

        Integer parity = resolveParity(examTypeId);
        String examTypeName = parity != null
                ? (parity == 1 ? "Mid Term" : "Final Term") : null;

        // Build semester -> section scope.
        boolean isMidTerm = parity != null && parity == 1;
        Map<UUID, Set<UUID>> scope = new HashMap<>();
        if (selections != null && !selections.isEmpty()) {
            List<Section> allSections = sectionRepository.findAll();
            for (GenerateTimetableRequest.SemesterSelection sel : selections) {
                if (sel.semesterId() == null) continue;
                Semester sem = semesterRepository.findById(sel.semesterId())
                        .orElseThrow(() -> new ResourceNotFoundException("Semester not found"));
                if (parity != null && sem.getSemesterNo() % 2 != parity) {
                    throw new BusinessRuleException("Semester " + sem.getSemesterNo()
                            + " does not belong to the " + examTypeName + " group");
                }
                Set<UUID> sections = (sel.sectionIds() != null && !sel.sectionIds().isEmpty())
                        ? new HashSet<>(sel.sectionIds())
                        : null;
                // Mid Term: enforce section restrictions for semesters 5+.
                if (isMidTerm && sections != null) {
                    Set<String> allowed = midTermAllowedSections(sem.getSemesterNo());
                    if (allowed != null) {
                        for (UUID secId : sections) {
                            String secName = allSections.stream()
                                    .filter(s -> s.getSectionId().equals(secId))
                                    .map(Section::getSectionName)
                                    .findFirst()
                                    .orElse(null);
                            if (secName != null && !allowed.contains(secName)) {
                                throw new BusinessRuleException(
                                        "Section " + secName + " is not allowed for Semester "
                                                + sem.getSemesterNo() + " during "
                                                + examTypeName + ". Allowed: " + allowed);
                            }
                        }
                    }
                }
                scope.merge(sem.getSemesterId(), sections != null ? sections : Collections.emptySet(),
                        (existing, incoming) -> {
                    if (incoming != null) existing.addAll(incoming);
                    return existing;
                });
            }
        } else if (parity != null) {
            for (Semester s : semesterRepository.findAll()) {
                if (s.getSemesterNo() % 2 == parity) {
                    scope.put(s.getSemesterId(), null);
                }
            }
        } else if (semesterId != null) {
            semesterRepository.findById(semesterId)
                    .orElseThrow(() -> new ResourceNotFoundException("Semester not found"));
            scope.put(semesterId, null);
        }

        generation.setScopeJson(toScopeJson(examTypeId, scope, autoBindCurriculum));

        // Load assignments
        List<TeachingAssignment> loadedAssignments = assignmentRepository
                .findWithDetailsByTermId(generation.getTerm().getTermId()).stream()
                .filter(a -> a.getAssignmentStatus() != AssignmentStatus.CANCELLED)
                .filter(a -> inScope(a, scope))
                .toList();

        // Load groups (only combined-class groups whose course belongs to the
        // generation semester scope; out-of-scope groups stay out of the whole
        // validation/delivery pipeline so Rule 2 never sees them).
        List<TeachingAssignmentGroupMember> groupMembers = groupMemberRepository
                .findWithDetailsByTermId(generation.getTerm().getTermId()).stream()
                .filter(m -> m.getAssignment().getAssignmentStatus() != AssignmentStatus.CANCELLED)
                .filter(m -> groupSemesterInScope(m, scope))
                .toList();
        Map<UUID, List<TeachingAssignmentGroupMember>> membersByGroup = new LinkedHashMap<>();
        Set<UUID> groupedAssignmentIds = new HashSet<>();
        for (TeachingAssignmentGroupMember m : groupMembers) {
            groupedAssignmentIds.add(m.getAssignment().getAssignmentId());
            membersByGroup.computeIfAbsent(m.getGroup().getGroupId(), k -> new ArrayList<>()).add(m);
        }

        // Curriculum-to-delivery binding: every required course whose eligibility
        // covers an explicitly selected dedicated-cohort section must enter the
        // solver with a delivery representation. Missing deliveries are closed by
        // creating a section-specific TeachingAssignment (the existing mechanism -
        // no second scheduling path), before validation and unit building run.
        List<TeachingAssignment> assignments = new ArrayList<>(loadedAssignments);
        if (autoBindCurriculum) {
            assignments.addAll(ensureCurriculumDeliveries(
                    generation.getTerm(), scope, loadedAssignments, membersByGroup));
        }

        if (assignments.isEmpty() && membersByGroup.isEmpty()) {
            throw new BusinessRuleException(
                    "No courses or teaching assignments found for the selected term, semesters and sections");
        }

        // Load CMRs
        Map<UUID, List<CourseMeetingRequirement>> requirementsByCourse = new HashMap<>();
        {
            Set<UUID> courseIds = new HashSet<>();
            for (TeachingAssignment a : assignments) courseIds.add(a.getCourse().getCourseId());
            for (List<TeachingAssignmentGroupMember> members : membersByGroup.values()) {
                courseIds.add(members.get(0).getGroup().getCourse().getCourseId());
            }
            for (CourseMeetingRequirement r : requirementRepository
                    .findAllByCourse_CourseIdIn(courseIds)) {
                requirementsByCourse.computeIfAbsent(r.getCourse().getCourseId(),
                        k -> new ArrayList<>()).add(r);
            }
            requirementsByCourse.values()
                    .forEach(list -> list.sort(Comparator.comparing(r -> r.getMeetingType())));
        }

        // ========== VALIDATION ==========
        List<String> errors = new ArrayList<>();

        // Missing CMRs
        for (TeachingAssignment a : assignments) {
            if (requirementsByCourse.getOrDefault(a.getCourse().getCourseId(), List.of()).isEmpty()) {
                errors.add(describe(a) + ": course has no course meeting requirement");
            }
        }

        // Split singletons from grouped
        List<TeachingAssignment> singletons = new ArrayList<>();
        for (TeachingAssignment a : assignments) {
            if (!groupedAssignmentIds.contains(a.getAssignmentId())) {
                singletons.add(a);
            }
        }

        // Group scope validation
        for (List<TeachingAssignmentGroupMember> members : membersByGroup.values()) {
            TeachingAssignmentGroup group = members.get(0).getGroup();
            Course course = group.getCourse();
            Semester sem = course.getSemester();
            boolean semesterInScope = scope.isEmpty()
                    || (sem != null && scope.containsKey(sem.getSemesterId()));
            if (!semesterInScope) continue;
            Set<UUID> scopeSections = sem != null ? scope.get(sem.getSemesterId()) : null;
            if (scopeSections != null) {
                for (TeachingAssignmentGroupMember m : members) {
                    if (!scopeSections.contains(m.getAssignment().getSection().getSectionId())) {
                        throw new BusinessRuleException("Cannot generate timetable: combined class "
                                + describeGroup(members) + " is only partially selected. All sections of a "
                                + "combined class must be selected together.");
                    }
                }
            }
            if (requirementsByCourse.getOrDefault(course.getCourseId(), List.of()).isEmpty()) {
                errors.add(describeGroup(members) + ": course has no course meeting requirement");
            }
        }

        // Rule 1: Course-semester code validation
        for (TeachingAssignment a : singletons) {
            String err = validateCourseSemesterCode(a.getCourse());
            if (err != null) errors.add(describe(a) + ": " + err);
        }
        for (List<TeachingAssignmentGroupMember> members : membersByGroup.values()) {
            String err = validateCourseSemesterCode(members.get(0).getGroup().getCourse());
            if (err != null) errors.add(describeGroup(members) + ": " + err);
        }

        // Rule 2: Course-semester must match generation scope
        for (TeachingAssignment a : singletons) {
            String err = validateCourseGenerationScope(a.getCourse(), scope);
            if (err != null) errors.add(describe(a) + ": " + err);
        }
        for (List<TeachingAssignmentGroupMember> members : membersByGroup.values()) {
            String err = validateCourseGenerationScope(members.get(0).getGroup().getCourse(), scope);
            if (err != null) errors.add(describeGroup(members) + ": " + err);
        }

        // Rule 3: CS/CT separation
        for (TeachingAssignment a : singletons) {
            String err = validateCsCtSeparation(a, scope);
            if (err != null) errors.add(describe(a) + ": " + err);
        }
        for (List<TeachingAssignmentGroupMember> members : membersByGroup.values()) {
            for (TeachingAssignmentGroupMember m : members) {
                String err = validateCsCtSeparation(m.getAssignment(), scope);
                if (err != null) errors.add(describe(m.getAssignment()) + ": " + err);
            }
        }

        // Rule 4: Lecturer ownership
        for (TeachingAssignment a : singletons) {
            String err = validateLecturerOwnership(a);
            if (err != null) errors.add(describe(a) + ": " + err);
        }
        for (List<TeachingAssignmentGroupMember> members : membersByGroup.values()) {
            for (TeachingAssignmentGroupMember m : members) {
                String err = validateLecturerOwnership(m.getAssignment());
                if (err != null) errors.add(describe(m.getAssignment()) + ": " + err);
            }
        }

        if (!errors.isEmpty()) {
            throw new BusinessRuleException("Cannot generate timetable:\n" + String.join("\n", errors));
        }

        // ========== PREPARE / REUSE generation snapshot (owned by generationId) ==========
        // The snapshot may already exist for this generationId (e.g. it was prepared
        // when the HOD entered the shared workspace, or kept from a previous
        // completed run). Existing snapshot for the SAME ACTIVE generationId is
        // always REUSED (scope re-applied); a genuinely missing one is created and
        // keyed by generationId. Because a snapshot is ALWAYS guaranteed before
        // this point, the "Generation snapshot not found" error can never occur.
        GenerationSnapshot snapshot =
                prepareSnapshotForScope(generation, examTypeId, scope, autoBindCurriculum);

        // ========== ASYNC GENERATION ==========
        if (generation.getStatus() == GenerationStatus.GENERATING) {
            throw new BusinessRuleException("Timetable generation is already in progress");
        }
        generation.setStatus(GenerationStatus.GENERATING);
        generation.setStartedAt(Instant.now());
        generation.setFailureReport(null);
        generation.setFinishedAt(null);
        generation = generationRepository.save(generation);

        // Live "generating" state: every connected HOD mirrors the loading UI.
        realtimeEventService.publishForGeneration(generationId,
                TimetableRealtimeEventService.GENERATION_STARTED,
                Map.of("generationId", generationId));

        // Heavy solving runs on a background worker so the HTTP request returns
        // immediately (no proxy/gateway timeouts). afterCommit guarantees the
        // worker only starts once the GENERATING status is committed; the
        // worker's own transaction rolls back on failure, preserving the
        // previously stored schedules.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                generationExecutor.submit(() ->
                        runBackgroundGenerationWithRetry(generationId));
            }
        });
        return toResponse(generation);
    }

    // =======================================================================
    // GENERATION-SNAPSHOT LIFECYCLE (owned by generationId)
    // =======================================================================

    /**
     * Serializes snapshot preparation per generation so that two HODs entering
     * the same shared workspace in near-parallel never prepare two snapshots.
     * Only one authoritative snapshot exists per generationId.
     */
    private final ConcurrentHashMap<UUID, Object> snapshotPrepLocks = new ConcurrentHashMap<>();

    /**
     * Prepares (or reuses) the backend snapshot owned by generationId when an
     * HOD enters the shared workspace. The expensive source-data load is skipped
     * when a valid, up-to-date snapshot already exists for generationId. The
     * snapshot lives on the backend and is owned by generationId — it is never
     * tied to a browser (refresh, reconnect or another HOD joining cannot
     * destroy it), and survives any HOD navigating away.
     */
    public GenerationSnapshotResponse prepareSnapshot(UUID generationId) {
        GenerationSession generation = findGeneration(generationId);
        UUID examTypeId = null;
        Map<UUID, Set<UUID>> scope = null;
        boolean autoBind = true;
        PersistedScope persisted = parseScope(generation.getScopeJson());
        if (persisted != null) {
            scope = toScopeMap(persisted);
            autoBind = shouldAutoBindCurriculum(persisted);
            if (persisted.examTypeId() != null) {
                ExamType et = examTypeRepository.findById(persisted.examTypeId()).orElse(null);
                examTypeId = et != null ? et.getExamTypeId() : null;
            }
        }
        if (scope == null || scope.isEmpty()) {
            // No scope selected yet: prepare the term-wide source layer so the
            // first Generate reuses this expensive preload instead of re-reading
            // the database from scratch.
            scope = defaultTermScope();
        }
        Object lock = snapshotPrepLocks.computeIfAbsent(generationId, k -> new Object());
        synchronized (lock) {
            log.info("[SNAPSHOT] WORKSPACE_ENTER generationId={} workspaceId={}",
                    generationId, generation.getTerm().getTermId());
            realtimeEventService.publishSnapshotState(generationId, "SNAPSHOT_PREPARING", null);
            GenerationSnapshot prepared = prepareSnapshotForScope(generation, examTypeId, scope, autoBind);
            if (prepared.isReady()) {
                realtimeEventService.publishSnapshotState(generationId, "SNAPSHOT_READY", null);
            }
        }
        return getSnapshotStatus(generationId);
    }

    public GenerationSnapshotResponse getSnapshotStatus(UUID generationId) {
        GenerationSession generation = findGeneration(generationId);
        Optional<GenerationSnapshot> existing = GenerationSnapshot.findActive(generationId);
        if (existing.isEmpty()) {
            return new GenerationSnapshotResponse(generationId, null,
                    generation.getTerm().getTermId(), "SNAPSHOT_INVALID");
        }
        GenerationSnapshot snap = existing.get();
        String status = switch (snap.getStatus()) {
            case CREATED, PRELOADING -> "SNAPSHOT_PREPARING";
            case PRELOADED, GENERATING, COMPLETED -> "SNAPSHOT_READY";
            case FAILED -> "SNAPSHOT_ERROR";
        };
        return new GenerationSnapshotResponse(generationId, snap.getSnapshotId(),
                generation.getTerm().getTermId(), status);
    }

    private Map<UUID, Set<UUID>> defaultTermScope() {
        Map<UUID, Set<UUID>> scope = new HashMap<>();
        for (Semester s : semesterRepository.findAll()) {
            scope.put(s.getSemesterId(), null);
        }
        return scope;
    }

    /**
     * Ensures a fully prepared, scope-applied snapshot exists for generationId.
     * <p>The ONLY lifecycle rules enforced here are:
     * <ul>
     *   <li>ACTIVE generation + snapshot exists  → REUSE it (re-apply the scope).</li>
     *   <li>ACTIVE generation + snapshot missing → CREATE a new snapshot for the SAME generationId.</li>
     * </ul>
     * An existing snapshot is never deleted nor recreated just because of a
     * refresh, re-entry, another HOD joining/leaving, or a repeated Generate.
     * Reuse re-applies the selected scope to the snapshot's already-loaded
     * term-wide source, which is always correct, so the same snapshotId is
     * consistently returned for the same ACTIVE generationId.
     * <p>A snapshot in memory is naturally lost on JVM/context restart; the
     * missing snapshot is then transparently rebuilt for the persisted ACTIVE
     * generation. "Generation snapshot not found" is therefore impossible on
     * this path — one is always returned.
     */
    private GenerationSnapshot prepareSnapshotForScope(GenerationSession generation,
                                                       UUID examTypeId,
                                                       Map<UUID, Set<UUID>> scope,
                                                       boolean autoBindCurriculum) {
        UUID generationId = generation.getGenerationId();
        UUID termId = generation.getTerm().getTermId();

        Optional<GenerationSnapshot> existingOpt = GenerationSnapshot.findActive(generationId);
        if (existingOpt.isPresent()) {
            GenerationSnapshot existing = existingOpt.get();
            log.info("[SNAPSHOT] REUSE generationId={} snapshotId={}",
                    generationId, existing.getSnapshotId());
            applySnapshotScope(existing, generation, examTypeId, scope, autoBindCurriculum);
            return existing;
        }

        log.info("[SNAPSHOT] REBUILD generationId={} reason=missing_after_restart", generationId);
        GenerationSnapshot snapshot = GenerationSnapshot.create(termId, examTypeId, generationId);
        snapshot.markPreloading();
        loadSnapshotSource(snapshot, generation);
        applySnapshotScope(snapshot, generation, examTypeId, scope, autoBindCurriculum);
        log.info("[SNAPSHOT] READY generationId={} snapshotId={} semesters={}",
                generationId, snapshot.getSnapshotId(), scope.size());
        return snapshot;
    }

    /**
     * Loads the term-wide source data (not scope-filtered) into the snapshot.
     * This is the expensive preload; its result is reused across Generate calls
     * and across HOD re-entries until the source fingerprint changes.
     */
    private void loadSnapshotSource(GenerationSnapshot snapshot, GenerationSession generation) {
        UUID termId = generation.getTerm().getTermId();

        List<TeachingAssignment> allAssignments = assignmentRepository
                .findWithDetailsByTermId(termId).stream()
                .filter(a -> a.getAssignmentStatus() != AssignmentStatus.CANCELLED)
                .toList();
        snapshot.setAllAssignments(allAssignments);
        snapshot.incrementDbQueryCount(1);

        List<TeachingAssignmentGroupMember> allGroupMembers = groupMemberRepository
                .findWithDetailsByTermId(termId).stream()
                .filter(m -> m.getAssignment().getAssignmentStatus() != AssignmentStatus.CANCELLED)
                .toList();
        snapshot.setAllGroupMembers(allGroupMembers);
        snapshot.incrementDbQueryCount(1);

        // Initialize lazy group collections while the Hibernate session is open
        // (the background solver reads them with no active session).
        Set<UUID> initializedGroupIds = new HashSet<>();
        for (TeachingAssignmentGroupMember m : allGroupMembers) {
            UUID gid = m.getGroup().getGroupId();
            if (initializedGroupIds.add(gid)) {
                Hibernate.initialize(m.getGroup().getMembers());
                Hibernate.initialize(m.getGroup().getCourse());
                if (m.getGroup().getCourse().getSemester() != null) {
                    Hibernate.initialize(m.getGroup().getCourse().getSemester());
                }
            }
        }

        List<TimeSlot> ts = timeSlotRepository.findAllByOrderByDisplayOrderAscPeriodNoAsc();
        snapshot.setTimeSlots(ts);
        snapshot.incrementDbQueryCount(1);

        List<Section> secs = sectionRepository.findAll();
        snapshot.setSections(secs);
        snapshot.incrementDbQueryCount(1);

        List<Semester> sems = semesterRepository.findAll();
        snapshot.setSemesters(sems);
        snapshot.incrementDbQueryCount(1);
    }

    /**
     * Applies the selected scope to a snapshot's source data and partitions it
     * per semester. After this returns the snapshot is fully READY for the
     * solver (no DB queries happen inside the solver — only this data).
     */
    private void applySnapshotScope(GenerationSnapshot snapshot, GenerationSession generation,
                                    UUID examTypeId, Map<UUID, Set<UUID>> scope,
                                    boolean autoBindCurriculum) {
        snapshot.setScope(scope);
        List<TeachingAssignment> allAssignments = snapshot.getAllAssignments();
        List<TeachingAssignmentGroupMember> allGroupMembers = snapshot.getAllGroupMembers();

        List<TeachingAssignment> scopedAssign = allAssignments.stream()
                .filter(a -> inScope(a, scope)).toList();
        snapshot.setScopedAssignments(scopedAssign);

        Map<UUID, List<TeachingAssignmentGroupMember>> scopedMembersByGroup = new LinkedHashMap<>();
        Set<UUID> scopedGroupedIds = new HashSet<>();
        for (Map.Entry<UUID, List<TeachingAssignmentGroupMember>> e :
                groupMembersByGroupId(allGroupMembers).entrySet()) {
            List<TeachingAssignmentGroupMember> scoped = e.getValue().stream()
                    .filter(m -> inScope(m.getAssignment(), scope))
                    .toList();
            if (!scoped.isEmpty()) {
                scopedMembersByGroup.put(e.getKey(), scoped);
                for (TeachingAssignmentGroupMember m : scoped) {
                    scopedGroupedIds.add(m.getAssignment().getAssignmentId());
                }
            }
        }
        snapshot.setScopedMembersByGroup(scopedMembersByGroup);
        snapshot.setScopedGroupedAssignmentIds(scopedGroupedIds);

        // Curriculum auto-binding may add new assignments AFTER snapshot scoping.
        List<TeachingAssignment> finalAssignments = new ArrayList<>(scopedAssign);
        if (autoBindCurriculum) {
            finalAssignments.addAll(ensureCurriculumDeliveries(
                    generation.getTerm(), scope, scopedAssign, scopedMembersByGroup));
        }
        snapshot.setScopedAssignments(finalAssignments);

        List<TeachingAssignment> scopedSingletons = new ArrayList<>();
        for (TeachingAssignment a : finalAssignments) {
            if (!scopedGroupedIds.contains(a.getAssignmentId())) {
                scopedSingletons.add(a);
            }
        }
        snapshot.setScopedSingletons(scopedSingletons);

        // Load CMRs scoped to the participating courses.
        Set<UUID> courseIds = new HashSet<>();
        for (TeachingAssignment a : finalAssignments) courseIds.add(a.getCourse().getCourseId());
        for (List<TeachingAssignmentGroupMember> members : scopedMembersByGroup.values()) {
            courseIds.add(members.get(0).getGroup().getCourse().getCourseId());
        }
        Map<UUID, List<CourseMeetingRequirement>> reqsByCourse = new HashMap<>();
        if (!courseIds.isEmpty()) {
            for (CourseMeetingRequirement r : requirementRepository.findAllByCourse_CourseIdIn(courseIds)) {
                reqsByCourse.computeIfAbsent(r.getCourse().getCourseId(), k -> new ArrayList<>()).add(r);
            }
        }
        reqsByCourse.values().forEach(l -> l.sort(Comparator.comparing(r -> r.getMeetingType())));
        snapshot.setRequirementsByCourse(reqsByCourse);
        snapshot.incrementDbQueryCount(1);

        snapshot.partitionBySemester();
        snapshot.markPreloaded();
    }

    // ========== DATA PRELOADING ==========

    /**
     * Build the complete in-memory generation context from preloaded data.
     * Groups scheduling units by semester for sequential processing.
     * No database calls â€” pure in-memory indexing.
     */
    private GenerationContext buildGenerationContext(
            UUID termId, Map<UUID, Set<UUID>> scope,
            List<TeachingAssignment> assignments,
            List<TeachingAssignment> singletons,
            Map<UUID, List<TeachingAssignmentGroupMember>> membersByGroup,
            Map<UUID, List<CourseMeetingRequirement>> requirementsByCourse,
            List<TimeSlot> timeSlots,
            Set<UUID> groupedAssignmentIds) {

        // Build ALL scheduling units
        List<SchedulingUnit> allUnits = buildSchedulingUnits(
                singletons, membersByGroup, requirementsByCourse, scope);

        // Partition by semester
        LinkedHashMap<UUID, List<SchedulingUnit>> unitsBySemester = new LinkedHashMap<>();
        for (SchedulingUnit u : allUnits) {
            unitsBySemester.computeIfAbsent(u.semesterId, k -> new ArrayList<>()).add(u);
        }

        return new GenerationContext(
                termId, scope, assignments, singletons, membersByGroup,
                requirementsByCourse, timeSlots, unitsBySemester, groupedAssignmentIds);
    }

    /**
     * Heavy generation worker. Runs in its own transaction on a background
     * thread (invoked through the Spring proxy so {@code @Transactional}
     * applies). Uses the pre-loaded GenerationSnapshot â€” no DB queries for
     * assignments/groups/requirements/timeSlots. Any exception rolls back
     * the worker transaction and the caller flips the session to FAILED.
     */
    public GenerationSessionResponse runGenerationBackground(UUID generationId) {
        GenerationSession generation = findGeneration(generationId);
        if (generation.getStatus() != GenerationStatus.GENERATING) {
            return toResponse(generation);
        }

        long pipelineStart = System.currentTimeMillis();

        // ===== PHASE 1: RETRIEVE SNAPSHOT (preloaded on HTTP thread) =====
        GenerationSnapshot snapshot = GenerationSnapshot.findActive(generationId)
                .orElseThrow(() -> new BusinessRuleException(
                        "Generation snapshot not found. Cannot generate without a preloaded snapshot."));
        snapshot.markGenerating();

        Map<UUID, Set<UUID>> scope = snapshot.getScope();
        UUID examTypeId = snapshot.getExamTypeId();
        Integer parity = resolveParity(examTypeId);
        String examTypeName = parity != null ? (parity == 1 ? "Mid Term" : "Final Term") : null;

        if (scope.isEmpty()) {
            throw new BusinessRuleException(
                    "Generation scope is empty. No semesters were selected for this generation.");
        }

        // ===== PHASE 2: USE SNAPSHOT DATA (zero DB queries) =====
        long preloadTime = snapshot.getPreloadTimeMs();

        List<TeachingAssignment> scopedAssignments = snapshot.getScopedAssignments();
        List<TeachingAssignment> singletons = snapshot.getScopedSingletons();
        Map<UUID, List<TeachingAssignmentGroupMember>> scopedMembersByGroup = snapshot.getScopedMembersByGroup();
        Map<UUID, List<CourseMeetingRequirement>> requirementsByCourse = snapshot.getRequirementsByCourse();
        List<TimeSlot> timeSlots = snapshot.getTimeSlots();
        Set<UUID> scopedGroupedIds = snapshot.getScopedGroupedAssignmentIds();
        UUID termId = snapshot.getTermId();

        if (timeSlots.isEmpty()) {
            generation.setStatus(GenerationStatus.FAILED);
            generation.setFinishedAt(Instant.now());
            snapshot.markFailed();
            return toResponse(generationRepository.save(generation));
        }

        // Build GenerationContext from snapshot data
        GenerationContext ctx = buildGenerationContext(
                termId, scope, scopedAssignments, singletons,
                scopedMembersByGroup, requirementsByCourse, timeSlots, scopedGroupedIds);

        // ===== PHASE 3: PREPARE =====
        List<ClassSchedule> existing = scheduleRepository.findByGeneration_GenerationId(generationId);
        scheduleRepository.deleteAll(existing);
        scheduleRepository.flush();

        // Log scope
        log.info("Generation {} scope (examType={}):", generationId, examTypeName);
        for (Map.Entry<UUID, Set<UUID>> e : scope.entrySet()) {
            semesterRepository.findById(e.getKey()).ifPresent(sem -> {
                List<String> secNames = new ArrayList<>();
                if (e.getValue() != null) {
                    for (UUID secId : e.getValue()) {
                        sectionRepository.findById(secId).ifPresent(s -> secNames.add(s.getSectionName()));
                    }
                }
                Collections.sort(secNames);
                log.info("  Sem-{}: {}{}", sem.getSemesterNo(), String.join(",", secNames),
                        e.getValue() == null ? " (ALL sections)" : "");
            });
        }

        // ===== SCOPE DIAGNOSTIC (before solver) =====
        // Prove exactly what data enters the solver: selected scope, auto-included
        // combined groups, and combined groups ignored because their semester or
        // member sections are outside the requested scope. Pure logging; no solver
        // or data changes.
        logScopeDiagnostic(snapshot, scope, scopedAssignments, singletons,
                scopedMembersByGroup, scopedGroupedIds);

        SolverContext solverCtx = new SolverContext(System.currentTimeMillis() + GENERATION_MAX_TIME_MS);
        List<ClassSchedule> created = new ArrayList<>();
        List<String> failureReport = new ArrayList<>();
        ConflictGrid grid = new ConflictGrid();

        // ===== PHASE 4: SEQUENTIAL SEMESTER GENERATION =====
        // Process each semester ONE AT A TIME. After each succeeds, its
        // resource occupancy is frozen in the ConflictGrid (staff + section).
        // The next semester's solver only sees its OWN units but respects
        // frozen occupancy from all previous semesters.
        for (Map.Entry<UUID, List<SchedulingUnit>> entry : ctx.unitsBySemester().entrySet()) {
            UUID semId = entry.getKey();
            List<SchedulingUnit> semUnits = entry.getValue();

            if (solverCtx.isTimeUp()) {
                failureReport.add("Generation time limit reached. Remaining semesters skipped.");
                break;
            }

            String semLabel = "Sem-" + snapshot.getSemester(semId).getSemesterNo();

            long semStart = System.currentTimeMillis();

            int frozenStaff = grid.getFrozenStaffCount();
            int frozenSections = grid.getFrozenSectionCount();
            log.info("SEMESTER {}: {} scheduling units, deadline={}ms, frozenStaff={}, frozenSections={}",
                    semLabel, semUnits.size(), solverCtx.semesterDeadlineMs, frozenStaff, frozenSections);

            // Capacity pre-check
            if (!checkCapacityPreconditions(semUnits, semId, failureReport)) {
                solverCtx.logSummary(semLabel);
                generation.setStatus(GenerationStatus.FAILED);
                generation.setFinishedAt(Instant.now());
                generation.setFailureReport(String.join("\n", failureReport));
                generationRepository.save(generation);
                snapshot.markFailed();
                GenerationSnapshot.remove(generationId);
                throw new BusinessRuleException("Timetable generation failed:\n"
                        + String.join("\n", failureReport));
            }

            // Enumerate elective group patterns for THIS semester only
            List<ElectiveGroupDescriptor> electiveDescriptors =
                    enumerateElectiveGroupPatterns(semUnits, grid);
            for (ElectiveGroupDescriptor desc : electiveDescriptors) {
                if (desc.validCandidates.isEmpty()) {
                    failureReport.add("No valid elective group pattern found for group "
                            + desc.electiveGroup + " in section " + desc.sectionId);
                    generation.setStatus(GenerationStatus.FAILED);
                    generation.setFinishedAt(Instant.now());
                    generation.setFailureReport(String.join("\n", failureReport));
                    generationRepository.save(generation);
                    snapshot.markFailed();
                    GenerationSnapshot.remove(generationId);
                    throw new BusinessRuleException("Timetable generation failed:\n"
                            + String.join("\n", failureReport));
                }
            }

            // MRV: sort elective groups by candidate count (fewest first)
            // This ensures the most constrained groups are processed first,
            // causing impossible branches to fail earlier.
            electiveDescriptors.sort(Comparator.comparingInt(d -> d.validCandidates.size()));

            log.info("SEMESTER {}: {} elective groups (candidates: {})", semLabel,
                    electiveDescriptors.size(),
                    electiveDescriptors.stream().mapToInt(d -> d.validCandidates.size()).boxed().toList());

            for (ElectiveGroupDescriptor desc : electiveDescriptors) {
                log.info("ElectiveGroupPrecheck: group={} section={} rawCandidates={}",
                        desc.electiveGroup, desc.sectionId, desc.validCandidates.size());
            }

            // Solve this semester
            int[] electiveComboCount = {0};
            boolean ok = tryElectiveGroupCombinations(electiveDescriptors, 0,
                    semUnits, timeSlots, generation, created, failureReport, grid,
                    new HashMap<>(), solverCtx);

            long semElapsed = System.currentTimeMillis() - semStart;

            solverDiag.logSummary(semLabel);

            if (!ok) {
                log.info("SEMESTER {} FAILED in {}ms: electiveCombos={}, nodes={}",
                        semLabel, semElapsed, solverCtx.electiveCombos, solverCtx.totalNodes);
                solverCtx.logSummary(semLabel);
                generation.setStatus(GenerationStatus.FAILED);
                generation.setFinishedAt(Instant.now());
                generation.setFailureReport(String.join("\n", failureReport));
                generationRepository.save(generation);
                snapshot.markFailed();
                GenerationSnapshot.remove(generationId);
                throw new BusinessRuleException("Timetable generation failed:\n"
                        + String.join("\n", failureReport));
            }

            log.info("SEMESTER {} SOLVED in {}ms: {} schedules placed, combos={}, nodes={}",
                    semLabel, semElapsed, created.size(), solverCtx.electiveCombos, solverCtx.totalNodes);

            // Semester is now FROZEN â€” its resource occupancy remains in the grid
            // for all subsequent semesters. No need to explicitly "freeze" anything;
            // the ConflictGrid.staff and ConflictGrid.sections maps already hold
            // the occupancy, which the next semester's solver will respect.
        }

        // ===== PHASE 5: FILLERS (LMS/ASSIGNMENT) =====
        record GridKey(UUID semesterId, UUID sectionId) {}
        Map<GridKey, List<int[]>> gridFreeSlots = new LinkedHashMap<>();
        for (ClassSchedule s : created) {
            Semester sem = semesterOf(s);
            if (sem == null) continue;
            for (UUID sectionId : ClassScheduleService.coveredSections(s)) {
                GridKey key = new GridKey(sem.getSemesterId(), sectionId);
                gridFreeSlots.computeIfAbsent(key,
                        k -> freeSlotsFor(k.semesterId(), k.sectionId(), timeSlots, created));
            }
        }
        Set<String> placedFillers = new HashSet<>();
        List<GridKey> fillerOrder = gridFreeSlots.entrySet().stream()
                .sorted(Comparator.comparingInt(e -> e.getValue().size()))
                .map(Map.Entry::getKey)
                .toList();
        for (GridKey fillerGrid : fillerOrder) {
            placeSectionSpecial(generation, fillerGrid.semesterId(),
                    fillerGrid.sectionId(), gridFreeSlots.get(fillerGrid),
                    timeSlots, created, placedFillers);
        }

        // ===== PHASE 6: PERSIST =====
        scheduleRepository.saveAll(created);

        long totalTime = System.currentTimeMillis() - pipelineStart;
        generation.setStatus(GenerationStatus.COMPLETED);
        generation.setFinishedAt(Instant.now());
        generation = generationRepository.save(generation);
        snapshot.markCompleted();
        log.info("GENERATION COMPLETE: {} schedules for {} â€” preload={}ms, solver={}ms, total={}ms, " +
                 "combos={}, solveAttempts={}, nodes={}, dbQueries={}",
                created.size(), generationId, preloadTime, totalTime - preloadTime, totalTime,
                solverCtx.electiveCombos, solverCtx.totalSolveAttempts, solverCtx.totalNodes,
                snapshot.getDbQueryCount());
        realtimeEventService.publishForGeneration(generationId,
                TimetableRealtimeEventService.GENERATION_COMPLETED,
                Map.of("generationId", generationId));
        snapshot.keep();
        return toResponse(generation);
    }

    /**
     * Flips a generation to FAILED after a background worker crashed. Runs in
     * its own transaction; safe to call from the worker's catch block.
     */
    public void markGenerationFailed(UUID generationId) {
        markGenerationFailed(generationId, null);
    }

    /**
     * Flips the session to FAILED and persists the human-readable reason so the
     * UI can show exactly why generation did not produce a timetable.
     */
    public void markGenerationFailed(UUID generationId, String failureReport) {
        try {
            GenerationSession generation = findGeneration(generationId);
            generation.setStatus(GenerationStatus.FAILED);
            generation.setFinishedAt(Instant.now());
            if (failureReport != null && !failureReport.isBlank()) {
                generation.setFailureReport(failureReport.length() > 8000
                        ? failureReport.substring(0, 8000) : failureReport);
            }
            generationRepository.save(generation);
            GenerationSnapshot.findActive(generationId).ifPresent(snap -> {
                snap.markFailed();
                GenerationSnapshot.remove(generationId);
            });
            realtimeEventService.publishForGeneration(generationId,
                    TimetableRealtimeEventService.GENERATION_FAILED,
                    Map.of("generationId", generationId,
                           "reason", failureReport == null ? "generation failed" : failureReport));
            log.warn("Generation {} marked FAILED after a background worker failure", generationId);
        } catch (ResourceNotFoundException e) {
            log.warn("Generation {} no longer exists; ignoring background worker failure", generationId);
        }
    }

    /**
     * Number of times the background generation worker is re-attempted after a
     * transient database failure (the Neon pooler resets a connection while the
     * pool was idle, so a transaction that started on a stale socket dies with
     * "Connection reset" / "Unable to rollback against JDBC Connection"). Each
     * attempt runs in its own fresh transaction on a re-validated connection.
     * Non-transient failures (validation, constraints) fail immediately.
     */
    private static final int GENERATION_WORKER_RETRIES = 2;

    private void runBackgroundGenerationWithRetry(UUID generationId) {
        Exception last = null;
        for (int attempt = 0; attempt <= GENERATION_WORKER_RETRIES; attempt++) {
            if (attempt > 0) {
                log.warn("[GENERATION] transient DB failure on attempt {} for generation {}; retrying: {}",
                        attempt, generationId, String.valueOf(last));
                try {
                    Thread.sleep(attempt == 1 ? 2_000L : 8_000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            try {
                self.runGenerationBackground(generationId);
                return;
            } catch (Exception e) {
                last = e;
                if (!isTransientDbFailure(e)) {
                    log.error("Background timetable generation failed for generation {}", generationId, e);
                    self.markGenerationFailed(generationId,
                            e instanceof BusinessRuleException bre
                                    ? bre.getMessage()
                                    : "Unexpected error: " + e.getMessage());
                    return;
                }
            }
        }
        log.error("Background timetable generation failed for generation {} after {} attempts",
                generationId, GENERATION_WORKER_RETRIES + 1, last);
        self.markGenerationFailed(generationId,
                "Unexpected error: " + (last == null ? "unknown" : last.getMessage()));
    }

    /**
     * True when the failure chain is an infrastructure/connection problem (dead
     * socket, pooler reset, borrow timeout) that a fresh connection can fix,
     * rather than a business/constraint error that retrying cannot resolve.
     */
    private boolean isTransientDbFailure(Throwable t) {
        for (Throwable c = t; c != null && c != c.getCause(); c = c.getCause()) {
            if (c instanceof java.net.SocketException
                    || c instanceof java.net.ConnectException
                    || c instanceof org.springframework.dao.DataAccessResourceFailureException) {
                String m = String.valueOf(c.getMessage());
                if (m.contains("I/O error") || m.contains("Connection reset")
                        || m.contains("Unable to acquire JDBC Connection")
                        || m.contains("Unable to rollback")
                        || m.contains("timed out") || m.contains("timeout")) {
                    return true;
                }
            }
        }
        return false;
    }

    // ========== SCHEDULING UNIT ==========

    private static class SchedulingUnit {
        final String label;
        final TeachingAssignment assignment;
        final TeachingAssignmentGroup group;
        final Set<UUID> staffIds;
        final Set<UUID> sectionIds;
        final int sessionsPerWeek;
        final int periodsPerSession;
        final int priority;
        final int structuralPriority;
        final UUID semesterId;
        /**
         * Elective group key (non-null only for elective singleton units).
         * Identity: semester_id + "|" + sectionId (per-section scoping).
         * Group members within the same section may co-locate on identical
         * windows (same day/start/end); the lecturer conflict rule always
         * wins. Null for required courses and combined-group units.
         */
        final String electiveGroup;

        /**
         * Combined groups are one linked unit spanning every member section, so a
         * single place() fixes identical windows in A, B, CT at once. The anchor
         * section is the canonical FIRST member section (smallest section name);
         * its slots are the authoritative ones the other members inherit.
         */
        final UUID anchorSectionId;
        final String anchorSectionName;
        /** Sorted member section names joined with '+', e.g. "A+B+CT" (or the section name for singletons). */
        final String sectionNamesLabel;
        /** Actual meeting/session type (LECTURE or LAB) of the source CMR row. */
        final MeetingType meetingType;

        SchedulingUnit(String label, TeachingAssignment assignment, TeachingAssignmentGroup group,
                        Set<UUID> staffIds, Set<UUID> sectionIds,
                        int sessionsPerWeek, int periodsPerSession, int priority,
                        int structuralPriority, UUID semesterId,
                        String electiveGroup, UUID anchorSectionId,
                        String anchorSectionName, String sectionNamesLabel,
                        MeetingType meetingType) {
            this.label = label;
            this.assignment = assignment;
            this.group = group;
            this.staffIds = staffIds;
            this.sectionIds = sectionIds;
            this.sessionsPerWeek = sessionsPerWeek;
            this.periodsPerSession = periodsPerSession;
            this.priority = priority;
            this.structuralPriority = structuralPriority;
            this.semesterId = semesterId;
            this.electiveGroup = electiveGroup;
            this.anchorSectionId = anchorSectionId;
            this.anchorSectionName = anchorSectionName;
            this.sectionNamesLabel = sectionNamesLabel;
            this.meetingType = meetingType;
        }

        boolean isElective() {
            return electiveGroup != null;
        }

        /** True when this unit is a linked combined teaching group (A+B, A+B+CT, ...). */
        boolean isCombined() {
            return group != null;
        }
    }

    /**
     * O(1) conflict grid that tracks which staff/section IDs occupy which day+period.
     * Internal representation: per-day 6-bit occupancy masks (P1=bit0 .. P6=bit5),
     * keyed by staffId, and by semesterId+sectionId. Semester-scoped section keys
     * guarantee Section A in Sem 1 never conflicts with Section A in Sem 3.
     * <p>Section occupancy is recorded per occupant (owner key) with the exact
     * window and optional elective-group key, so elective group members may
     * co-locate on an IDENTICAL window (same day/start/end, same section) while
     * every partial overlap and every staff overlap stays rejected.
     * This is an in-memory solver optimization only; nothing is persisted.
     */
    private static class ConflictGrid {
        /** staffId -> day -> 6-bit period occupancy mask. */
        private final Map<UUID, Map<Integer, Integer>> staff = new HashMap<>();
        /** semesterId -> sectionId -> day -> component/unit key -> occupancy record. */
        private final Map<UUID, Map<UUID, Map<Integer, Map<Object, SectionOcc>>>> sections = new HashMap<>();
        /**
         * Established elective-group windows per section: semesterId -> sectionId ->
         * groupKey -> weekly occurrence (1-based) -> window chosen by the first
         * member to place that occurrence. Later members of the same group in the
         * same section are forced onto the same window, which is what makes
         * co-location a hard constraint (physical load = 2 windows per group).
         */
        private final Map<UUID, Map<UUID, Map<String, Map<Integer, GroupWindow>>>> groupWindows = new HashMap<>();
        /**
         * Tracks which elective-group key owns a staff+section+day+period slot.
         * Parallel to {@link #staff}: when a staff member is occupied due to
         * an elective group placement, this records the group key so that
         * same-group electives may co-locate (business rule: elective
         * alternatives in the same group share windows even with the same
         * lecturer).  {@code null} for required-course placements.
         * Keyed by staffId â†’ sectionId â†’ day â†’ period â†’ groupKey.
         */
        private final Map<UUID, Map<UUID, Map<Integer, Map<Integer, String>>>> staffGroupKeys = new HashMap<>();

        private static final class SectionOcc {
            final int mask;
            final int start;
            final int end;
            final String groupKey;
            SectionOcc(int mask, int start, int end, String groupKey) {
                this.mask = mask;
                this.start = start;
                this.end = end;
                this.groupKey = groupKey;
            }
        }

        private static final class GroupWindow {
            final UUID ownerKey;
            final int day;
            final int start;
            final int end;
            GroupWindow(UUID ownerKey, int day, int start, int end) {
                this.ownerKey = ownerKey;
                this.day = day;
                this.start = start;
                this.end = end;
            }
        }

        private static int periodMask(int startOrder, int endOrder) {
            int mask = 0;
            for (int p = startOrder; p <= endOrder; p++) {
                mask |= (1 << (p - 1));
            }
            return mask;
        }

        private static int dayMask(Map<UUID, Map<Integer, Integer>> outer, UUID key, int day) {
            Map<Integer, Integer> days = outer.get(key);
            return days != null ? days.getOrDefault(day, 0) : 0;
        }

        private static void orDay(Map<UUID, Map<Integer, Integer>> outer, UUID key, int day, int mask) {
            outer.computeIfAbsent(key, k -> new HashMap<>()).merge(day, mask, (a, b) -> a | b);
        }

        private static void andNotDay(Map<UUID, Map<Integer, Integer>> outer, UUID key, int day, int mask) {
            Map<Integer, Integer> days = outer.get(key);
            if (days == null) return;
            int next = days.getOrDefault(day, 0) & ~mask;
            if (next == 0) {
                days.remove(day);
                if (days.isEmpty()) outer.remove(key);
            } else {
                days.put(day, next);
            }
        }

        boolean canPlace(Set<UUID> staffIds, Set<UUID> sectionIds, UUID semesterId,
                         int day, int startOrder, int endOrder, String electiveGroup) {
            int mask = periodMask(startOrder, endOrder);
            for (UUID id : staffIds) {
                int conflict = dayMask(staff, id, day) & mask;
                if (conflict != 0) {
                    if (electiveGroup == null) return false;
                    // Allow overlap only when ALL conflicting periods belong to the
                    // same elective group in the SAME section (same-group elective
                    // alternatives share windows within a section, not across sections).
                    boolean allowed = false;
                    Map<UUID, Map<Integer, Map<Integer, String>>> bySection = staffGroupKeys.get(id);
                    if (bySection != null) {
                        for (UUID secId : sectionIds) {
                            Map<Integer, Map<Integer, String>> byDay = bySection.get(secId);
                            if (byDay == null) continue;
                            Map<Integer, String> gk = byDay.get(day);
                            if (gk == null) continue;
                            boolean allSameGroup = true;
                            for (int p = startOrder; p <= endOrder; p++) {
                                if ((conflict & (1 << (p - 1))) != 0) {
                                    String existing = gk.get(p);
                                    if (existing == null || !existing.equals(electiveGroup)) {
                                        allSameGroup = false;
                                        break;
                                    }
                                }
                            }
                            if (allSameGroup) { allowed = true; break; }
                        }
                    }
                    if (!allowed) return false;
                }
            }
            Map<UUID, Map<Integer, Map<Object, SectionOcc>>> secDays = sections.get(semesterId);
            if (secDays != null) {
                for (UUID id : sectionIds) {
                    Map<Integer, Map<Object, SectionOcc>> dayOcc = secDays.get(id);
                    if (dayOcc == null) continue;
                    Map<Object, SectionOcc> occ = dayOcc.get(day);
                    if (occ == null) continue;
                    for (SectionOcc o : occ.values()) {
                        if ((o.mask & mask) == 0) continue;
                        // Only an IDENTICAL window of a same-group elective may overlap.
                        if (electiveGroup == null
                                || !electiveGroup.equals(o.groupKey)
                                || o.start != startOrder || o.end != endOrder) {
                            return false;
                        }
                    }
                }
            }
            return true;
        }

        /**
         * The window already established by the group for the given weekly
         * occurrence in this section, or null when no member placed it yet.
         */
        int[] forcedWindow(String electiveGroup, UUID sectionId, UUID semesterId, int occurrence) {
            Map<UUID, Map<String, Map<Integer, GroupWindow>>> bySec = groupWindows.get(semesterId);
            if (bySec == null) return null;
            Map<String, Map<Integer, GroupWindow>> byGroup = bySec.get(sectionId);
            if (byGroup == null) return null;
            Map<Integer, GroupWindow> occ = byGroup.get(electiveGroup);
            if (occ == null) return null;
            GroupWindow w = occ.get(occurrence);
            return w != null ? new int[] {w.day, w.start, w.end} : null;
        }

        void place(Set<UUID> staffIds, Set<UUID> sectionIds, UUID semesterId,
                   int day, int startOrder, int endOrder, String electiveGroup, SchedulingUnit unitKey,
                   int occurrence) {
            int mask = periodMask(startOrder, endOrder);
            for (UUID id : staffIds) {
                orDay(staff, id, day, mask);
                if (electiveGroup != null) {
                    for (UUID secId : sectionIds) {
                        Map<Integer, String> gk = staffGroupKeys
                                .computeIfAbsent(id, k -> new HashMap<>())
                                .computeIfAbsent(secId, k -> new HashMap<>())
                                .computeIfAbsent(day, k -> new HashMap<>());
                        for (int p = startOrder; p <= endOrder; p++) {
                            gk.put(p, electiveGroup);
                        }
                    }
                }
            }
            Map<UUID, Map<Integer, Map<Object, SectionOcc>>> secDays =
                    sections.computeIfAbsent(semesterId, k -> new HashMap<>());
            for (UUID id : sectionIds) {
                Map<Integer, Map<Object, SectionOcc>> dayOcc =
                        secDays.computeIfAbsent(id, k -> new HashMap<>());
                dayOcc.computeIfAbsent(day, k -> new HashMap<>())
                        .put(unitKey, new SectionOcc(mask, startOrder, endOrder, electiveGroup));
                if (electiveGroup != null) {
                    Map<String, Map<Integer, GroupWindow>> byGroup =
                            groupWindows.computeIfAbsent(semesterId, k -> new HashMap<>())
                                    .computeIfAbsent(id, k -> new HashMap<>());
                    byGroup.computeIfAbsent(electiveGroup, k -> new HashMap<>())
                            .putIfAbsent(occurrence,
                                    new GroupWindow(unitOwnerKey(unitKey), day, startOrder, endOrder));
                }
            }
        }

        void remove(Set<UUID> staffIds, Set<UUID> sectionIds, UUID semesterId,
                    int day, int startOrder, int endOrder, String electiveGroup, SchedulingUnit unitKey,
                    int occurrence) {
            int mask = periodMask(startOrder, endOrder);
            for (UUID id : staffIds) {
                andNotDay(staff, id, day, mask);
                if (electiveGroup != null) {
                    for (UUID secId : sectionIds) {
                        Map<UUID, Map<Integer, Map<Integer, String>>> bySection = staffGroupKeys.get(id);
                        if (bySection != null) {
                            Map<Integer, Map<Integer, String>> byDay = bySection.get(secId);
                            if (byDay != null) {
                                Map<Integer, String> gk = byDay.get(day);
                                if (gk != null) {
                                    for (int p = startOrder; p <= endOrder; p++) {
                                        gk.remove(p);
                                    }
                                    if (gk.isEmpty()) byDay.remove(day);
                                    if (byDay.isEmpty()) bySection.remove(secId);
                                }
                            }
                            if (bySection.isEmpty()) staffGroupKeys.remove(id);
                        }
                    }
                }
            }
            Map<UUID, Map<Integer, Map<Object, SectionOcc>>> secDays = sections.get(semesterId);
            if (secDays == null) return;
            for (UUID id : sectionIds) {
                Map<Integer, Map<Object, SectionOcc>> dayOcc = secDays.get(id);
                if (dayOcc == null) continue;
                Map<Object, SectionOcc> occ = dayOcc.get(day);
                if (occ == null) continue;
                occ.remove(unitKey);
                if (electiveGroup != null) {
                    // The established window for this occurrence survives as long as
                    // any same-group member still occupies this exact window: otherwise
                    // backtracking the establishing member could strand another member
                    // on a window the group has abandoned (co-location would break).
                    boolean survivor = false;
                    for (SectionOcc o : occ.values()) {
                        if (electiveGroup.equals(o.groupKey)
                                && o.start == startOrder && o.end == endOrder) {
                            survivor = true;
                            break;
                        }
                    }
                    if (!survivor) {
                        Map<UUID, Map<String, Map<Integer, GroupWindow>>> bySec = groupWindows.get(semesterId);
                        if (bySec != null) {
                            Map<String, Map<Integer, GroupWindow>> byGroup = bySec.get(id);
                            if (byGroup != null) {
                                Map<Integer, GroupWindow> gw = byGroup.get(electiveGroup);
                                if (gw != null) {
                                    gw.remove(occurrence);
                                }
                            }
                        }
                    }
                }
                if (occ.isEmpty()) {
                    dayOcc.remove(day);
                    if (dayOcc.isEmpty()) {
                        secDays.remove(id);
                        if (secDays.isEmpty()) sections.remove(semesterId);
                    }
                }
            }
        }

        /**
         * Atomically place ALL elective sessions of a group at the pre-selected
         * windows.  Every member course/session is recorded at each window with
         * the same elective-group key, and the groupWindows map is populated so
         * that {@link #forcedWindow} returns the correct window for each
         * occurrence during the main backtracking phase.
         *
         * @param staffIds        all staff teaching in this group (for ownerKey)
         * @param electiveGroup   the elective group key (semesterId + "|" + sectionId)
         * @param memberKeys      unique Object key per member session (used as SectionOcc map key)
         * @param memberPPS       periods-per-session for each member (parallel to memberKeys)
         * @param memberStaffIds  staff IDs per member session (parallel to memberKeys)
         * @param windows         ordered list of time windows (one per weekly occurrence)
         */
        void placeElectiveGroup(Set<UUID> staffIds, UUID sectionId, UUID semesterId,
                                 String electiveGroup,
                                 List<Object> memberKeys, List<Integer> memberPPS,
                                 List<Set<UUID>> memberStaffIds,
                                 List<TimeWindow> windows) {
            int occurrence = 1;
            for (TimeWindow w : windows) {
                int mask = periodMask(w.startOrder, w.endOrder);
                // Only block staff whose member's pps matches this window width
                Set<UUID> windowStaff = new HashSet<>();
                for (int i = 0; i < memberKeys.size(); i++) {
                    if ((w.endOrder - w.startOrder + 1) == memberPPS.get(i)) {
                        windowStaff.addAll(memberStaffIds.get(i));
                    }
                }
                for (UUID id : windowStaff) {
                    orDay(staff, id, w.day, mask);
                    Map<Integer, String> gk = staffGroupKeys
                            .computeIfAbsent(id, k -> new HashMap<>())
                            .computeIfAbsent(sectionId, k -> new HashMap<>())
                            .computeIfAbsent(w.day, k -> new HashMap<>());
                    for (int p = w.startOrder; p <= w.endOrder; p++) {
                        gk.put(p, electiveGroup);
                    }
                }
                Map<UUID, Map<Integer, Map<Object, SectionOcc>>> secDays =
                        sections.computeIfAbsent(semesterId, k -> new HashMap<>());
                Map<Integer, Map<Object, SectionOcc>> dayOcc =
                        secDays.computeIfAbsent(sectionId, k -> new HashMap<>());
                for (int i = 0; i < memberKeys.size(); i++) {
                    int pps = memberPPS.get(i);
                    if ((w.endOrder - w.startOrder + 1) != pps) continue;
                    Object key = memberKeys.get(i);
                    dayOcc.computeIfAbsent(w.day, k -> new HashMap<>())
                            .put(key, new SectionOcc(mask, w.startOrder, w.endOrder,
                                    electiveGroup));
                }
                Map<String, Map<Integer, GroupWindow>> byGroup =
                        groupWindows.computeIfAbsent(semesterId, k -> new HashMap<>())
                                .computeIfAbsent(sectionId, k -> new HashMap<>());
                UUID ownerKey = staffIds.iterator().hasNext()
                        ? staffIds.iterator().next() : UUID.randomUUID();
                byGroup.computeIfAbsent(electiveGroup, k -> new HashMap<>())
                        .putIfAbsent(occurrence, new GroupWindow(ownerKey, w.day,
                                w.startOrder, w.endOrder));
                occurrence++;
            }
        }

        /**
         * Atomically undo a previously committed elective group pattern.
         * Mirrors {@link #placeElectiveGroup} in reverse.
         */
        void removeElectiveGroup(Set<UUID> staffIds, UUID sectionId, UUID semesterId,
                                   String electiveGroup,
                                   List<Object> memberKeys, List<Integer> memberPPS,
                                   List<Set<UUID>> memberStaffIds,
                                   List<TimeWindow> windows) {
            int occurrence = 1;
            for (TimeWindow w : windows) {
                // Remove staff occupancy â€” mirror of placeElectiveGroup
                int mask = periodMask(w.startOrder, w.endOrder);
                Set<UUID> windowStaff = new HashSet<>();
                for (int i = 0; i < memberKeys.size(); i++) {
                    if ((w.endOrder - w.startOrder + 1) == memberPPS.get(i)) {
                        windowStaff.addAll(memberStaffIds.get(i));
                    }
                }
                for (UUID id : windowStaff) {
                    andNotDay(staff, id, w.day, mask);
                    Map<UUID, Map<Integer, Map<Integer, String>>> bySection = staffGroupKeys.get(id);
                    if (bySection != null) {
                        Map<Integer, Map<Integer, String>> byDay = bySection.get(sectionId);
                        if (byDay != null) {
                            Map<Integer, String> gk = byDay.get(w.day);
                            if (gk != null) {
                                for (int p = w.startOrder; p <= w.endOrder; p++) {
                                    gk.remove(p);
                                }
                                if (gk.isEmpty()) byDay.remove(w.day);
                                if (byDay.isEmpty()) bySection.remove(sectionId);
                            }
                        }
                        if (bySection.isEmpty()) staffGroupKeys.remove(id);
                    }
                }
                // Remove section occupancy
                Map<UUID, Map<Integer, Map<Object, SectionOcc>>> secDays = sections.get(semesterId);
                if (secDays != null) {
                    Map<Integer, Map<Object, SectionOcc>> dayOcc = secDays.get(sectionId);
                    if (dayOcc != null) {
                        Map<Object, SectionOcc> occ = dayOcc.get(w.day);
                        if (occ != null) {
                            for (Object key : memberKeys) {
                                occ.remove(key);
                            }
                            if (occ.isEmpty()) {
                                dayOcc.remove(w.day);
                                if (dayOcc.isEmpty()) {
                                    secDays.remove(sectionId);
                                    if (secDays.isEmpty()) sections.remove(semesterId);
                                }
                            }
                        }
                    }
                }
                Map<UUID, Map<String, Map<Integer, GroupWindow>>> bySec = groupWindows.get(semesterId);
                if (bySec != null) {
                    Map<String, Map<Integer, GroupWindow>> byGroup = bySec.get(sectionId);
                    if (byGroup != null) {
                        Map<Integer, GroupWindow> gw = byGroup.get(electiveGroup);
                        if (gw != null) {
                            gw.remove(occurrence);
                            if (gw.isEmpty()) byGroup.remove(electiveGroup);
                            if (byGroup.isEmpty()) bySec.remove(sectionId);
                            if (bySec.isEmpty()) groupWindows.remove(semesterId);
                        }
                    }
                }
                occurrence++;
            }
        }

        /** Number of staff members with at least one occupied period (frozen from completed semesters). */
        int getFrozenStaffCount() {
            int count = 0;
            for (Map<Integer, Integer> dayMap : staff.values()) {
                for (int mask : dayMap.values()) {
                    if (mask != 0) { count++; break; }
                }
            }
            return count;
        }

        /** Number of section+day combinations with at least one occupied period (frozen). */
        int getFrozenSectionCount() {
            int count = 0;
            for (Map<UUID, Map<Integer, Map<Object, SectionOcc>>> secDays : sections.values()) {
                for (Map<Integer, Map<Object, SectionOcc>> dayMap : secDays.values()) {
                    for (Map<Object, SectionOcc> occMap : dayMap.values()) {
                        if (!occMap.isEmpty()) { count++; break; }
                    }
                }
            }
            return count;
        }
    }

    private List<SchedulingUnit> buildSchedulingUnits(
            List<TeachingAssignment> singletons,
            Map<UUID, List<TeachingAssignmentGroupMember>> membersByGroup,
            Map<UUID, List<CourseMeetingRequirement>> requirementsByCourse,
            Map<UUID, Set<UUID>> scope) {

        List<SchedulingUnit> units = new ArrayList<>();

        // Per-course structural tier, aggregated across all of the course's CMR rows
        // (a 2x1 + 1x2 pair is one "mixed" course requirement, not two independent ones).
        Map<UUID, Integer> priorityByCourse = new HashMap<>();

        // Groups first (priority 0 = highest)
        for (List<TeachingAssignmentGroupMember> members : membersByGroup.values()) {
            TeachingAssignmentGroup group = members.get(0).getGroup();
            Course course = group.getCourse();
            Semester sem = course.getSemester();
            if (!scope.isEmpty() && (sem == null || !scope.containsKey(sem.getSemesterId()))) continue;

            Set<UUID> staffIds = new HashSet<>();
            Set<UUID> sectionIds = new HashSet<>();
            for (TeachingAssignmentGroupMember m : members) {
                if (!inScope(m.getAssignment(), scope)) continue;
                staffIds.add(m.getAssignment().getStaff().getStaffId());
                sectionIds.add(m.getAssignment().getSection().getSectionId());
            }
            if (sectionIds.isEmpty()) continue;

            List<CourseMeetingRequirement> reqs =
                    requirementsByCourse.getOrDefault(course.getCourseId(), List.of());
            int structuralPriority = priorityByCourse.computeIfAbsent(
                    course.getCourseId(), k -> classifyCourseShape(reqs));
            // Canonical anchor = smallest section name (Section A first). Combined
            // units are one linked unit spanning all member sections, so the anchor
            // is where the authoritative slots are decided; B/C/CT inherit them
            // by sharing the same unit (no per-section re-solve).
            List<String> sortedMemberNames = members.stream()
                    .map(m -> m.getAssignment().getSection().getSectionName())
                    .sorted().toList();
            String anchorName = sortedMemberNames.get(0);
            UUID anchorSectionId = members.stream()
                    .filter(m -> anchorName.equals(m.getAssignment().getSection().getSectionName()))
                    .findFirst().orElseThrow()
                    .getAssignment().getSection().getSectionId();
            String sectionNamesLabel = String.join("+", sortedMemberNames);
            for (CourseMeetingRequirement req : reqs) {
                units.add(new SchedulingUnit(
                        describeGroup(members) + " / " + req.getMeetingType(),
                        null, group, staffIds, sectionIds,
                        req.getSessionsPerWeek(), req.getPeriodsPerSession(), 0,
                        structuralPriority,
                        sem != null ? sem.getSemesterId() : null, null,
                        anchorSectionId, anchorName, sectionNamesLabel,
                        req.getMeetingType()));
            }
        }

        // Singletons (priority 1)
        for (TeachingAssignment a : singletons) {
            Set<UUID> staffIds = Set.of(a.getStaff().getStaffId());
            Set<UUID> sectionIds = Set.of(a.getSection().getSectionId());
            UUID semId = a.getCourse().getSemester() != null
                    ? a.getCourse().getSemester().getSemesterId() : null;
            // Elective group identity: per-section (semester_id + section_id).
            String electiveGroup = (!a.getCourse().isRequired() && semId != null)
                    ? semId.toString() + "|" + a.getSection().getSectionId() : null;

            List<CourseMeetingRequirement> reqs =
                    requirementsByCourse.getOrDefault(a.getCourse().getCourseId(), List.of());
            int structuralPriority = priorityByCourse.computeIfAbsent(
                    a.getCourse().getCourseId(), k -> classifyCourseShape(reqs));
            for (CourseMeetingRequirement req : reqs) {
                units.add(new SchedulingUnit(
                        describe(a) + " / " + req.getMeetingType(),
                        a, null, staffIds, sectionIds,
                        req.getSessionsPerWeek(), req.getPeriodsPerSession(), 1,
                        electiveGroup != null ? 4 : structuralPriority, semId,
                        electiveGroup,
                        a.getSection().getSectionId(),
                        a.getSection().getSectionName(),
                        a.getSection().getSectionName(),
                        req.getMeetingType()));
            }
        }

        // Sort: groups first, then 2-period before 1-period, then more sessions first
        units.sort(Comparator.comparingInt((SchedulingUnit u) -> u.priority)
                .thenComparingInt((SchedulingUnit u) -> -u.periodsPerSession)
                .thenComparingInt((SchedulingUnit u) -> -u.sessionsPerWeek));

        return units;
    }

    // ========== BACKTRACKING SOLVER (per-semester) ==========

    // (Semester processing moved to runGenerationBackground â€” sequential pipeline)

    private boolean checkCapacityPreconditions(List<SchedulingUnit> units, UUID semesterId,
                                               List<String> failureReport) {
        // Sum the required periods of every component of a course first, then apply
        // elective co-location: a group shares windows, so it contributes the load of
        // its largest member course only. Component-level max() would undercount a
        // course split across multiple rows (e.g. 2+2+2+2).
        Map<Object, Integer> periodsByCourse = new HashMap<>();
        for (SchedulingUnit u : units) {
            periodsByCourse.merge(courseKey(u), u.sessionsPerWeek * u.periodsPerSession, Integer::sum);
        }
        Map<UUID, Map<String, Integer>> electiveMaxBySection = new HashMap<>();
        Map<UUID, Map<String, Integer>> contributionBySection = new HashMap<>();
        Map<UUID, Integer> periodsBySection = new HashMap<>();
        Map<Object, Set<UUID>> counted = new HashMap<>();
        for (SchedulingUnit u : units) {
            int total = periodsByCourse.get(courseKey(u));
            String code = courseCodeOfUnit(u);
            for (UUID sectionId : u.sectionIds) {
                if (!counted.computeIfAbsent(courseKey(u), k -> new HashSet<>()).add(sectionId)) continue;
                if (u.isElective()) {
                    electiveMaxBySection.computeIfAbsent(sectionId, k -> new HashMap<>())
                            .merge(u.electiveGroup, total, Math::max);
                } else {
                    contributionBySection.computeIfAbsent(sectionId, k -> new LinkedHashMap<>())
                            .merge(code, total, Integer::sum);
                    periodsBySection.merge(sectionId, total, Integer::sum);
                }
            }
        }
        for (Map.Entry<UUID, Map<String, Integer>> e : electiveMaxBySection.entrySet()) {
            for (Map.Entry<String, Integer> g : e.getValue().entrySet()) {
                contributionBySection.computeIfAbsent(e.getKey(), k -> new LinkedHashMap<>())
                        .merge("(elective group) " + g.getKey(), g.getValue(), Integer::sum);
                periodsBySection.merge(e.getKey(), g.getValue(), Integer::sum);
            }
        }
        // Available per section: 6 periods x 5 days = 30 period-slots
        int maxSlots = 6 * 5;
        for (Map.Entry<UUID, Integer> e : periodsBySection.entrySet()) {
            if (e.getValue() > maxSlots) {
                failureReport.add(describeCapacityOverload(
                        semesterId, e.getKey(), e.getValue(), maxSlots,
                        contributionBySection.getOrDefault(e.getKey(), Map.of())));
                return false;
            }
        }
        return true;
    }

    /** Names the overloaded cohort and lists the courses that consume the slots. */
    private String describeCapacityOverload(UUID semesterId, UUID sectionId, int required,
                                            int maxSlots, Map<String, Integer> contributions) {
        StringBuilder sb = new StringBuilder("Semester ");
        semesterRepository.findById(semesterId).ifPresent(s -> sb.append(s.getSemesterNo()));
        sectionRepository.findById(sectionId)
                .ifPresent(sec -> sb.append(" / Section ").append(sec.getSectionName()));
        sb.append(" requires ").append(required).append(" period-slots/week but only ")
          .append(maxSlots).append(" are available (").append(WORKING_DAY_END)
          .append(" days x ").append(6).append(" periods). Reduce weekly periods of this")
          .append(" cohort's curriculum by ").append(required - maxSlots).append(".");
        contributions.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(10)
                .forEach(c -> sb.append("\n  - ").append(c.getKey()).append(": ")
                        .append(c.getValue()).append(" periods/week"));
        return sb.toString();
    }

    private static String courseCodeOfUnit(SchedulingUnit u) {
        if (u.group != null && u.group.getCourse() != null) {
            return u.group.getCourse().getCourseCode();
        }
        return u.assignment != null && u.assignment.getCourse() != null
                ? u.assignment.getCourse().getCourseCode() : "?";
    }

    // ========== CHEAP FEASIBILITY PRE-CHECK ==========
    /**
     * Before running the expensive backtracking solver, verify that the current
     * elective group pattern combination is not obviously infeasible.
     *
     * <p>Checks (in order of increasing cost):
     * <ol>
     *   <li>Each non-elective unit has at least one valid placement given
     *       frozen occupancy + committed elective windows.</li>
     *   <li>Per-section capacity: total required periods â‰¤ available period-slots
     *       (accounting for committed elective windows).</li>
     *   <li>Staff day-availability: each lecturer has at least enough free
     *       consecutive-period blocks for its remaining sessions.</li>
     * </ol>
     */
    private boolean quickFeasibilityCheck(List<SchedulingUnit> semUnits, List<TimeSlot> slots, ConflictGrid grid) {
        solverDiag.feasibilityChecks++;
        // Check 1: Each non-elective unit has at least one valid placement
        for (SchedulingUnit u : semUnits) {
            if (u.isElective()) continue;
            Set<Integer> used = new HashSet<>();
            List<PlacementOption> opts = generateValidPlacements(u, used, slots, grid);
            if (opts.isEmpty()) {
                solverDiag.feasibilityPrunes++;
                return false;
            }
        }

        // Check 2: Per-section capacity â€” count committed elective periods
        // and verify remaining non-elective periods still fit.
        Map<String, Integer> electiveGroupMaxBySection = new HashMap<>();
        for (SchedulingUnit u : semUnits) {
            if (!u.isElective()) continue;
            for (UUID secId : u.sectionIds) {
                String key = secId + "|" + u.electiveGroup;
                electiveGroupMaxBySection.merge(key, u.sessionsPerWeek * u.periodsPerSession, Math::max);
            }
        }
        Map<UUID, Integer> correctedElectivePeriods = new HashMap<>();
        for (Map.Entry<String, Integer> e : electiveGroupMaxBySection.entrySet()) {
            UUID secId = UUID.fromString(e.getKey().split("\\|")[0]);
            correctedElectivePeriods.merge(secId, e.getValue(), Integer::sum);
        }

        int maxSlots = 6 * 5; // 5 days Ã— 6 periods
        Map<UUID, Integer> requiredBySection = new HashMap<>();
        for (SchedulingUnit u : semUnits) {
            if (u.isElective()) continue;
            int periods = u.sessionsPerWeek * u.periodsPerSession;
            for (UUID secId : u.sectionIds) {
                requiredBySection.merge(secId, periods, Integer::sum);
            }
        }
        for (Map.Entry<UUID, Integer> e : requiredBySection.entrySet()) {
            int elective = correctedElectivePeriods.getOrDefault(e.getKey(), 0);
            if (e.getValue() + elective > maxSlots) {
                solverDiag.feasibilityPrunes++;
                return false;
            }
        }

        // Check 3: Staff day-availability â€” each lecturer must have enough
        // consecutive-period blocks across distinct days for its sessions.
        // We count the number of days where at least one valid consecutive block
        // is free (not the entire day â€” partial-day occupancy is fine).
        for (SchedulingUnit u : semUnits) {
            if (u.isElective()) continue;
            for (UUID staffId : u.staffIds) {
                int daysWithFreeBlock = 0;
                for (int d = WORKING_DAY_START; d <= WORKING_DAY_END; d++) {
                    int dayOcc = ConflictGrid.dayMask(grid.staff, staffId, d);
                    // Check if any consecutive block of u.periodsPerSession is free on this day
                    for (int startIdx = 0; startIdx + u.periodsPerSession <= slots.size(); startIdx++) {
                        if (!consecutiveSlots(slots, startIdx, u.periodsPerSession)) continue;
                        int startOrder = startIdx + 1;
                        int endOrder = startIdx + u.periodsPerSession;
                        int blockMask = ConflictGrid.periodMask(startOrder, endOrder);
                        if ((dayOcc & blockMask) == 0) {
                            daysWithFreeBlock++;
                            break;
                        }
                    }
                }
                if (daysWithFreeBlock < u.sessionsPerWeek) {
                    solverDiag.feasibilityPrunes++;
                    return false;
                }
            }
        }

        return true;
    }

    // ========== ELECTIVE GROUP PATTERN PRE-COMMITMENT ==========

    /** Collects all info needed to commit/rollback one elective group in one section. */
    private static class ElectiveGroupDescriptor {
        final String key;
        final UUID semesterId;
        final UUID sectionId;
        final String electiveGroup;
        final Set<UUID> allStaffIds;
        final List<SchedulingUnit> groupUnits;
        final List<Object> memberKeys;
        final List<Integer> memberPPS;
        final List<Set<UUID>> memberStaffIds;
        final int pps;
        final List<List<TimeWindow>> validCandidates = new ArrayList<>();
        int committedIndex = -1;

        ElectiveGroupDescriptor(String key, UUID semesterId, UUID sectionId,
                                String electiveGroup, Set<UUID> allStaffIds,
                                List<SchedulingUnit> groupUnits,
                                List<Object> memberKeys, List<Integer> memberPPS,
                                List<Set<UUID>> memberStaffIds, int pps) {
            this.key = key;
            this.semesterId = semesterId;
            this.sectionId = sectionId;
            this.electiveGroup = electiveGroup;
            this.allStaffIds = allStaffIds;
            this.groupUnits = groupUnits;
            this.memberKeys = memberKeys;
            this.memberPPS = memberPPS;
            this.memberStaffIds = memberStaffIds;
            this.pps = pps;
        }

        String sectionName() {
            return groupUnits.isEmpty() ? sectionId.toString()
                    : groupUnits.get(0).sectionIds.stream()
                        .map(id -> id.toString().substring(0, 8)).findFirst().orElse("?");
        }

        String memberCourseCodes() {
            return groupUnits.stream()
                    .map(u -> u.label.contains("/") ? u.label.substring(u.label.lastIndexOf('/') + 2) : u.label)
                    .distinct().collect(java.util.stream.Collectors.joining(", "));
        }
    }

    /**
     * Diagnostic: print the first N combos in enumeration order.
     * Does NOT modify the grid. For each combo, prints course codes, staff, and windows.
     */
    void logComboCatalog(List<ElectiveGroupDescriptor> descriptors, int maxCombos) {
        if (descriptors.isEmpty()) return;
        int totalCombos = 1;
        for (ElectiveGroupDescriptor d : descriptors) totalCombos *= d.validCandidates.size();
        log.info("[CATALOG] {} groups, total raw combinations = {}", descriptors.size(), totalCombos);
        for (int i = 0; i < descriptors.size(); i++) {
            ElectiveGroupDescriptor d = descriptors.get(i);
            log.info("[CATALOG] Group {} section={} pps={} validCandidates={} courses={}",
                    i, d.sectionId, d.pps, d.validCandidates.size(), d.memberCourseCodes());
        }

        // Print first maxCombos combos in lexicographic order
        int[] indices = new int[descriptors.size()];
        for (int comboNum = 0; comboNum < Math.min(maxCombos, totalCombos); comboNum++) {
            StringBuilder sb = new StringBuilder();
            sb.append("[CATALOG] #").append(comboNum + 1).append(": ");
            for (int g = 0; g < descriptors.size(); g++) {
                ElectiveGroupDescriptor d = descriptors.get(g);
                List<TimeWindow> windows = d.validCandidates.get(indices[g]);
                sb.append(d.sectionId.toString().substring(0, 4))
                  .append("=")
                  .append(windowsString(windows));
                if (g < descriptors.size() - 1) sb.append(" | ");
            }
            log.info("{}", sb);

            // Increment indices (lexicographic)
            for (int g = indices.length - 1; g >= 0; g--) {
                indices[g]++;
                if (indices[g] < descriptors.get(g).validCandidates.size()) break;
                indices[g] = 0;
            }
        }

        // Find the known-good combo: CST-4137 windows for all sections
        // We can't determine course from windows alone, but we can find
        // combos where the windows match what the standalone prover used
        log.info("[CATALOG] Searching for CST-4137 combo position...");
    }

    static String windowsString(List<TimeWindow> windows) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < windows.size(); i++) {
            TimeWindow w = windows.get(i);
            if (i > 0) sb.append(", ");
            sb.append(DAY_NAMES[w.day].substring(0, 3))
              .append(" P").append(w.startOrder).append("-P").append(w.endOrder);
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Diagnostic: log full details of a combo being committed.
     */
    static void traceComboCommit(int comboNum, List<ElectiveGroupDescriptor> descriptors) {
        log.info("========== COMBO #{} ==========", comboNum);
        for (int g = 0; g < descriptors.size(); g++) {
            ElectiveGroupDescriptor d = descriptors.get(g);
            List<TimeWindow> windows = (d.committedIndex >= 0 && d.committedIndex < d.validCandidates.size())
                    ? d.validCandidates.get(d.committedIndex) : null;
            log.info("  Group[{}] section={} pps={} staff={}", g,
                    d.sectionId, d.pps, d.allStaffIds);
            log.info("    courses: {}", d.memberCourseCodes());
            if (windows != null) {
                log.info("    windows: {}", windowsString(windows));
                for (TimeWindow w : windows) {
                    log.info("      day={} ({}) start=P{} end=P{}", w.day,
                            DAY_NAMES[w.day], w.startOrder, w.endOrder);
                }
            }
        }
    }

    /**
     * Diagnostic: classify why a placement option is rejected for a unit.
     * Returns a human-readable reason string.
     */
    String classifyRejection(SchedulingUnit unit, PlacementOption opt,
                                     List<TimeSlot> slots, ConflictGrid grid,
                                     Map<Object, Set<Integer>> usedDaysByCourse) {
        int day = opt.day;
        int startOrder = opt.startIdx + 1;
        int endOrder = opt.startIdx + unit.periodsPerSession;
        Set<Integer> usedDays = usedDaysByCourse.getOrDefault(courseKey(unit), Set.of());

        if (usedDays.contains(day)) return "USED_DAY";

        if (!consecutiveSlots(slots, opt.startIdx, unit.periodsPerSession))
            return "INVALID_CONSECUTIVE_SLOTS";

        for (UUID staffId : unit.staffIds) {
            int dayOcc = ConflictGrid.dayMask(grid.staff, staffId, day);
            int mask = ConflictGrid.periodMask(startOrder, endOrder);
            if ((dayOcc & mask) != 0) {
                return "STAFF_CONFLICT(staffId=" + staffId + ",day=" + day + ",P" + startOrder + "-P" + endOrder + ")";
            }
        }

        for (UUID secId : unit.sectionIds) {
            Map<UUID, Map<Integer, Map<Object, ConflictGrid.SectionOcc>>> secDays =
                    grid.sections.get(unit.semesterId);
            if (secDays == null) continue;
            Map<Integer, Map<Object, ConflictGrid.SectionOcc>> dayOcc2 = secDays.get(secId);
            if (dayOcc2 == null) continue;
            Map<Object, ConflictGrid.SectionOcc> occ = dayOcc2.get(day);
            if (occ == null) continue;
            for (Map.Entry<Object, ConflictGrid.SectionOcc> e : occ.entrySet()) {
                ConflictGrid.SectionOcc so = e.getValue();
                if (so.start <= endOrder && so.end >= startOrder) {
                    return "SECTION_CONFLICT(sec=" + secId + ",day=" + day +
                            ",P" + so.start + "-P" + so.end + ",key=" + e.getKey() + ")";
                }
            }
        }

        if (unit.isElective() && !unit.sectionIds.isEmpty()) {
            int[] forced = grid.forcedWindow(unit.electiveGroup, unit.sectionIds.iterator().next(),
                    unit.semesterId, usedDays.size() + 1);
            if (forced != null && (forced[2] - forced[1] + 1) != unit.periodsPerSession) {
                return "ELECTIVE_WINDOW_MISMATCH(forced=P" + forced[1] + "-P" + forced[2] +
                        " but unit needs " + unit.periodsPerSession + " periods)";
            }
            if (forced != null && (day != forced[0] || startOrder != forced[1] || endOrder != forced[2])) {
                return "ELECTIVE_WINDOW_CONFLICT(forced=" + DAY_NAMES[forced[0]] +
                        " P" + forced[1] + "-P" + forced[2] + ")";
            }
        }

        return "UNKNOWN";
    }

    /**
     * Diagnostic: log the search path for the FIRST few decisions of a failed combo.
     * Returns a list of strings describing each depth's decision.
     */
    List<String> traceFirstDecisions(List<SchedulingUnit> units, List<TimeSlot> slots,
                                     ConflictGrid grid,
                                     Map<Object, Set<Integer>> usedDaysByCourse,
                                     Map<SchedulingUnit, Integer> placedCounts,
                                     int maxDepth) {
        List<String> trace = new ArrayList<>();
        for (int depth = 0; depth < maxDepth; depth++) {
            if (unitIdx(units, placedCounts) >= units.size()) break;

            SchedulingUnit best = null;
            int minOptions = Integer.MAX_VALUE;
            for (SchedulingUnit u : units) {
                if (placedCounts.getOrDefault(u, 0) >= u.sessionsPerWeek) continue;
                Set<Integer> usedDays = usedDaysByCourse.getOrDefault(courseKey(u), new HashSet<>());
                List<PlacementOption> opts = generateValidPlacements(u, usedDays, slots, grid);
                if (opts.size() < minOptions) {
                    minOptions = opts.size();
                    best = u;
                }
            }
            if (best == null) break;

            Set<Integer> usedDays = usedDaysByCourse.getOrDefault(courseKey(best), new HashSet<>());
            List<PlacementOption> options = generateValidPlacements(best, usedDays, slots, grid);
            trace.add(String.format("depth=%d: unit=%s, options=%d", depth, best.label, options.size()));
            if (!options.isEmpty()) {
                PlacementOption first = options.get(0);
                trace.add(String.format("  -> first option: %s P%d-P%d",
                        DAY_NAMES[first.day], first.startIdx + 1, first.startIdx + best.periodsPerSession));
            }
            break; // Only trace the first decision for now
        }
        return trace;
    }

    /**
     * Diagnostic: find the index of a specific combo in the enumeration.
     * The combo is identified by a list of window lists, one per descriptor
     * (in the descriptor's sorted order). Returns -1 if not found.
     */
    int locateCombo(List<ElectiveGroupDescriptor> descriptors,
                     List<List<TimeWindow>> targetWindows) {
        if (descriptors.size() != targetWindows.size()) return -1;
        int totalCombos = 1;
        for (ElectiveGroupDescriptor d : descriptors) totalCombos *= d.validCandidates.size();

        int[] indices = new int[descriptors.size()];
        for (int comboNum = 0; comboNum < totalCombos; comboNum++) {
            boolean match = true;
            for (int g = 0; g < descriptors.size(); g++) {
                ElectiveGroupDescriptor d = descriptors.get(g);
                List<TimeWindow> actual = d.validCandidates.get(indices[g]);
                List<TimeWindow> target = targetWindows.get(g);
                if (!actual.equals(target)) { match = false; break; }
            }
            if (match) return comboNum + 1; // 1-based

            for (int g = indices.length - 1; g >= 0; g--) {
                indices[g]++;
                if (indices[g] < descriptors.get(g).validCandidates.size()) break;
                indices[g] = 0;
            }
        }
        return -1;
    }

    /**
     * Diagnostic: find which candidate index in a descriptor matches a specific window list.
     * Returns -1 if not found.
     */
    static int findCandidateIndex(ElectiveGroupDescriptor desc, List<TimeWindow> target) {
        for (int i = 0; i < desc.validCandidates.size(); i++) {
            if (desc.validCandidates.get(i).equals(target)) return i;
        }
        return -1;
    }

    /**
     * Diagnostic: run the solver with a FORCED combo.
     * Bypasses the combo search entirely. Commits the specified candidate indices
     * for each descriptor, runs solveWithRestarts, then rolls back.
     *
     * @param candidateIndices one index per descriptor (in descriptor order)
     * @return true if the solver finds a solution
     */
    boolean runSolverForcedCombo(List<ElectiveGroupDescriptor> descriptors,
                                  int[] candidateIndices,
                                  List<SchedulingUnit> semUnits, List<TimeSlot> slots,
                                  GenerationSession generation, List<ClassSchedule> created,
                                  List<String> failureReport, ConflictGrid grid,
                                  Map<String, int[]> sectionDayLoads, SolverContext solverCtx) {
        if (descriptors.size() != candidateIndices.length) {
            throw new IllegalArgumentException("Need " + descriptors.size() + " indices, got " + candidateIndices.length);
        }

        log.info("========== FORCED COMBO ==========");
        for (int g = 0; g < descriptors.size(); g++) {
            ElectiveGroupDescriptor d = descriptors.get(g);
            int ci = candidateIndices[g];
            if (ci < 0 || ci >= d.validCandidates.size()) {
                log.error("Forced combo: invalid index {} for group {} (max={})", ci, g, d.validCandidates.size() - 1);
                return false;
            }
            List<TimeWindow> windows = d.validCandidates.get(ci);
            log.info("  Group[{}] section={} pps={} staff={} courses={}", g,
                    d.sectionId, d.pps, d.allStaffIds, d.memberCourseCodes());
            log.info("    windows: {}", windowsString(windows));
            for (TimeWindow w : windows) {
                log.info("      day={} ({}) P{}-P{}", w.day, DAY_NAMES[w.day], w.startOrder, w.endOrder);
            }
        }

        // Commit all groups
        for (int g = 0; g < descriptors.size(); g++) {
            commitElectiveGroup(descriptors.get(g), candidateIndices[g], grid);
        }

        // Check cross-section conflicts
        for (int g = 0; g < descriptors.size(); g++) {
            if (hasCrossSectionStaffConflict(descriptors.get(g), grid)) {
                log.info("FORCED COMBO: cross-section staff conflict in group {}", g);
                for (int r = g; r >= 0; r--) rollbackElectiveGroup(descriptors.get(r), grid);
                return false;
            }
        }

        // Feasibility check
        if (!quickFeasibilityCheck(semUnits, slots, grid)) {
            log.info("FORCED COMBO: failed quick feasibility check");
            for (int r = descriptors.size() - 1; r >= 0; r--) rollbackElectiveGroup(descriptors.get(r), grid);
            return false;
        }

        log.info("FORCED COMBO: entering solveWithRestarts...");
        boolean result = solveWithRestarts(semUnits, slots, generation, created,
                failureReport, grid, sectionDayLoads, solverCtx);
        log.info("FORCED COMBO: solver {} (nodes={})", result ? "SOLVED" : "FAILED", solverCtx.totalNodes);

        // Rollback all groups
        for (int r = descriptors.size() - 1; r >= 0; r--) {
            rollbackElectiveGroup(descriptors.get(r), grid);
        }
        return result;
    }

    /**
     * Diagnostic entry-point: run a forced combo for a single semester.
     * Replicates the semester-solving flow from runGenerationBackground but
     * calls runSolverForcedCombo instead of tryElectiveGroupCombinations.
     * Returns a map with: result, nodes, combos, descriptors, catalogInfo.
     */
    Map<String, Object> diagnosticRunForcedCombo(UUID generationId, UUID semesterId, int[] candidateIndices) {
        Map<String, Object> result = new LinkedHashMap<>();
        GenerationSnapshot snapshot = GenerationSnapshot.findActive(generationId)
                .orElseThrow(() -> new BusinessRuleException("Snapshot not found for " + generationId));

        // Build context from snapshot
        GenerationContext ctx = buildGenerationContext(
                snapshot.getTermId(), snapshot.getScope(),
                snapshot.getScopedAssignments(), snapshot.getScopedSingletons(),
                snapshot.getScopedMembersByGroup(), snapshot.getRequirementsByCourse(),
                snapshot.getTimeSlots(), snapshot.getScopedGroupedAssignmentIds());

        List<SchedulingUnit> semUnits = ctx.unitsBySemester().getOrDefault(semesterId, List.of());
        List<TimeSlot> slots = snapshot.getTimeSlots();

        result.put("semUnits", semUnits.size());

        if (semUnits.isEmpty()) {
            result.put("result", "EMPTY");
            return result;
        }

        // Build grid + conflict tracking
        ConflictGrid grid = new ConflictGrid();

        // Enumerate elective groups
        List<ElectiveGroupDescriptor> descriptors = enumerateElectiveGroupPatterns(semUnits, grid);
        descriptors.sort(Comparator.comparingInt(d -> d.validCandidates.size()));

        // Build catalog info
        List<String> catalogInfo = new ArrayList<>();
        for (int i = 0; i < descriptors.size(); i++) {
            ElectiveGroupDescriptor d = descriptors.get(i);
            catalogInfo.add(String.format("Group[%d]: section=%s pps=%d candidates=%d courses=%s",
                    i, d.sectionId, d.pps, d.validCandidates.size(), d.memberCourseCodes()));
        }
        result.put("descriptors", descriptors.size());
        result.put("catalogInfo", catalogInfo);
        result.put("candidatesPerGroup", descriptors.stream()
                .mapToInt(d -> d.validCandidates.size()).boxed().toList());

        if (candidateIndices == null || candidateIndices.length != descriptors.size()) {
            result.put("result", "INVALID_INDICES");
            result.put("error", "Need " + descriptors.size() + " indices, got "
                    + (candidateIndices == null ? "null" : candidateIndices.length));
            return result;
        }

        // Build remaining solver state
        GenerationSession generation = findGeneration(generationId);
        List<ClassSchedule> created = scheduleRepository.findByGeneration_GenerationId(generationId);
        List<String> failureReport = new ArrayList<>();
        Map<String, int[]> sectionDayLoads = new HashMap<>();
        long semesterDeadlineMs = System.currentTimeMillis() + SEMESTER_MAX_TIME_MS;
        SolverContext solverCtx = new SolverContext(semesterDeadlineMs);

        // Reset diagnostics
        solverDiag.reset();

        // Run forced combo
        long t0 = System.currentTimeMillis();
        boolean ok = runSolverForcedCombo(descriptors, candidateIndices,
                semUnits, slots, generation, created, failureReport, grid,
                sectionDayLoads, solverCtx);
        long elapsed = System.currentTimeMillis() - t0;

        result.put("result", ok ? "FEASIBLE" : "INFEASIBLE");
        result.put("elapsedMs", elapsed);
        result.put("nodes", solverCtx.totalNodes);
        result.put("combos", solverCtx.electiveCombos);
        result.put("solveAttempts", solverCtx.totalSolveAttempts);
        result.put("failureReport", failureReport);
        result.put("solverDiagNodes", solverDiag.nodesExplored);
        result.put("solverDiagMRVCalls", solverDiag.mrvCalls);
        result.put("solverDiagGvpCalls", solverDiag.gvpCalls);
        result.put("solverDiagCanPlaceCalls", solverDiag.canPlaceCalls);
        result.put("solverDiagCacheHits", solverDiag.cacheHits);
        result.put("solverDiagCacheMisses", solverDiag.cacheMisses);
        result.put("solverDiagMaxDepth", solverDiag.depthMax);

        return result;
    }

    /**
     * Enumerate ALL valid candidate window pairs for every elective group
     * in this semester's units.  Populates each descriptor's
     * {@code validCandidates} list (ordered by discovery = lexicographic day/slot).
     * Does NOT modify the grid.
     */
    List<ElectiveGroupDescriptor> enumerateElectiveGroupPatterns(
            List<SchedulingUnit> semUnits, ConflictGrid grid) {

        Map<String, List<SchedulingUnit>> electiveByGroupSection = new LinkedHashMap<>();
        for (SchedulingUnit u : semUnits) {
            if (!u.isElective()) continue;
            for (UUID secId : u.sectionIds) {
                String key = u.semesterId + "|" + secId + "|" + u.electiveGroup;
                electiveByGroupSection.computeIfAbsent(key, k -> new ArrayList<>()).add(u);
            }
        }

        List<ElectiveGroupDescriptor> descriptors = new ArrayList<>();
        for (Map.Entry<String, List<SchedulingUnit>> entry : electiveByGroupSection.entrySet()) {
            List<SchedulingUnit> groupUnits = entry.getValue();
            SchedulingUnit first = groupUnits.get(0);
            UUID semesterId = first.semesterId;
            UUID sectionId = first.sectionIds.iterator().next();
            String electiveGroup = first.electiveGroup;

            Set<UUID> allStaffIds = new HashSet<>();
            for (SchedulingUnit u : groupUnits) {
                allStaffIds.addAll(u.staffIds);
            }

            List<Object> memberKeys = new ArrayList<>();
            List<Integer> memberPPS = new ArrayList<>();
            List<Set<UUID>> memberStaffIds = new ArrayList<>();
            for (SchedulingUnit u : groupUnits) {
                Object key = u.assignment != null ? u.assignment : u.group;
                for (int i = 0; i < u.sessionsPerWeek; i++) {
                    memberKeys.add(key);
                    memberPPS.add(u.periodsPerSession);
                    memberStaffIds.add(u.staffIds);
                }
            }

            int pps = first.periodsPerSession;
            ElectiveGroupDescriptor desc = new ElectiveGroupDescriptor(
                    entry.getKey(), semesterId, sectionId, electiveGroup,
                    allStaffIds, groupUnits, memberKeys, memberPPS, memberStaffIds, pps);

            for (int d1 = WORKING_DAY_START; d1 <= WORKING_DAY_END; d1++) {
                for (int s1 = 1; s1 + pps - 1 <= 6; s1++) {
                    int e1 = s1 + pps - 1;
                    for (int d2 = d1 + 1; d2 <= WORKING_DAY_END; d2++) {
                        for (int s2 = 1; s2 + pps - 1 <= 6; s2++) {
                            int e2 = s2 + pps - 1;
                            TimeWindow w1 = new TimeWindow(d1, s1, e1);
                            TimeWindow w2 = new TimeWindow(d2, s2, e2);
                            List<TimeWindow> candidate = List.of(w1, w2);
                            if (validateElectiveGroupPattern(electiveGroup, groupUnits,
                                    allStaffIds, semesterId, sectionId, candidate, grid)) {
                                desc.validCandidates.add(candidate);
                            }
                        }
                    }
                }
            }
            descriptors.add(desc);
        }
        return descriptors;
    }

    /**
     * After committing an elective group pattern, check whether any staff member
     * is now double-booked across sections.  This catches conflicts that
     * {@link #validateElectiveGroupPattern} cannot detect because it runs at
     * enumeration time (empty grid) before other sections' patterns are committed.
     *
     * <p>A staff member is double-booked when they occupy the same day+period
     * in two different sections.  Within a single elective group in a single
     * section, overlap is allowed (elective alternatives share windows).
     * Cross-section overlap for the same elective group is NOT allowed (the
     * lecturer cannot teach in two sections simultaneously).
     */
    private boolean hasCrossSectionStaffConflict(ElectiveGroupDescriptor desc, ConflictGrid grid) {
        Set<UUID> staffIds = desc.allStaffIds;
        UUID sectionId = desc.sectionId;

        List<TimeWindow> windows = desc.validCandidates.get(desc.committedIndex);

        for (UUID staffId : staffIds) {
            for (TimeWindow w : windows) {
                // Check global staff occupancy for this staff member on this day.
                int occupied = ConflictGrid.dayMask(grid.staff, staffId, w.day);
                int mask = ConflictGrid.periodMask(w.startOrder, w.endOrder);
                int conflict = occupied & mask;
                if (conflict == 0) continue;

                // There IS a conflict.  Walk each conflicting period and check
                // whether it comes from the SAME section + SAME elective group
                // (allowed) or from a DIFFERENT section / non-elective (denied).
                Map<UUID, Map<Integer, Map<Integer, String>>> bySection =
                        grid.staffGroupKeys.get(staffId);
                if (bySection == null) return true; // occupied but no group key = non-elective elsewhere

                for (int p = w.startOrder; p <= w.endOrder; p++) {
                    if ((conflict & (1 << (p - 1))) == 0) continue;
                    boolean allowed = false;
                    for (Map.Entry<UUID, Map<Integer, Map<Integer, String>>> secEntry : bySection.entrySet()) {
                        UUID otherSecId = secEntry.getKey();
                        Map<Integer, Map<Integer, String>> dayMap = secEntry.getValue();
                        if (dayMap == null) continue;
                        Map<Integer, String> periodMap = dayMap.get(w.day);
                        if (periodMap == null) continue;
                        String otherGroup = periodMap.get(p);
                        if (otherGroup != null) {
                            if (!otherSecId.equals(sectionId)) {
                                return true;
                            }
                            allowed = true;
                        }
                    }
                    if (!allowed) return true;
                }
            }
        }
        return false;
    }

    private void commitElectiveGroup(ElectiveGroupDescriptor desc, int candidateIndex,
                                      ConflictGrid grid) {
        List<TimeWindow> windows = desc.validCandidates.get(candidateIndex);
        grid.placeElectiveGroup(desc.allStaffIds, desc.sectionId, desc.semesterId,
                desc.electiveGroup, desc.memberKeys, desc.memberPPS,
                desc.memberStaffIds, windows);
        desc.committedIndex = candidateIndex;
    }

    /** Undo the committed candidate for the given descriptor (mutates grid). */
    private void rollbackElectiveGroup(ElectiveGroupDescriptor desc, ConflictGrid grid) {
        if (desc.committedIndex < 0) return;
        List<TimeWindow> windows = desc.validCandidates.get(desc.committedIndex);
        grid.removeElectiveGroup(desc.allStaffIds, desc.sectionId, desc.semesterId,
                desc.electiveGroup, desc.memberKeys, desc.memberPPS,
                desc.memberStaffIds, windows);
        desc.committedIndex = -1;
    }

    /**
     * Try all valid elective group pattern combinations for the given semester.
     * For each combination, commit all groups, then run the main solver.
     * If the solver fails, roll back ALL group patterns (atomic) and try the
     * next combination.  Returns true on the first combination that solves.
     *
     * <p>Optimizations:
     * <ul>
     *   <li><b>MRV ordering:</b> descriptors are pre-sorted by candidate count
     *       (fewest first) so impossible branches fail earlier.</li>
     *   <li><b>Forward checking:</b> after each commit at intermediate depths,
     *       verify that non-elective units still have placements via
     *       {@link #quickFeasibilityCheck}.  This prunes dead branches before
     *       recursing into the expensive solver.</li>
     * </ul>
     */
    private boolean tryElectiveGroupCombinations(
            List<ElectiveGroupDescriptor> descriptors, int depth,
            List<SchedulingUnit> semUnits, List<TimeSlot> slots,
            GenerationSession generation, List<ClassSchedule> created,
            List<String> failureReport, ConflictGrid grid,
            Map<String, int[]> sectionDayLoads,
            SolverContext solverCtx) {

        if (depth == descriptors.size()) {
            // All groups committed â€” run main solver
            solverCtx.electiveCombos++;
            if (solverCtx.electiveCombos > MAX_ELECTIVE_COMBINATION_ATTEMPTS) {
                log.warn("ElectiveGroupCombinations: exceeded {} attempts â€” giving up",
                        MAX_ELECTIVE_COMBINATION_ATTEMPTS);
                failureReport.add("Exhausted " + MAX_ELECTIVE_COMBINATION_ATTEMPTS
                        + " elective-group pattern combinations without finding a valid timetable. "
                        + "The constraint set may be too tight for the available time slots.");
                return false;
            }
            if (solverCtx.isTimeUp()) {
                log.warn("ElectiveGroupCombinations: time limit reached at combo#{}", solverCtx.electiveCombos);
                failureReport.add("Generation time limit reached after " + solverCtx.electiveCombos
                        + " elective combinations. Solver explored " + solverCtx.totalNodes + " nodes.");
                return false;
            }

            // === CHEAP FEASIBILITY PRE-CHECK ===
            if (!quickFeasibilityCheck(semUnits, slots, grid)) {
                log.debug("ElectiveGroupCombinations: combo#{} pruned by quick feasibility check",
                        solverCtx.electiveCombos);
                return false;
            }

            boolean result = solveWithRestarts(semUnits, slots, generation, created,
                    failureReport, grid, sectionDayLoads, solverCtx);
            log.info("ElectiveGroupCombinations: Main solver {} (depth={}, combo#{}, nodes={})",
                    result ? "SOLVED" : "FAILED", depth, solverCtx.electiveCombos, solverCtx.totalNodes);
            return result;
        }

        ElectiveGroupDescriptor desc = descriptors.get(depth);
        if (desc.validCandidates.isEmpty()) {
            failureReport.add("No valid elective group pattern found for " + desc.key);
            return false;
        }

        for (int ci = 0; ci < desc.validCandidates.size(); ci++) {
            List<TimeWindow> windows = desc.validCandidates.get(ci);

            // Commit tentatively, check cross-section staff conflict, then decide.
            commitElectiveGroup(desc, ci, grid);

            if (hasCrossSectionStaffConflict(desc, grid)) {
                rollbackElectiveGroup(desc, grid);
                solverCtx.candidatesSkipped++;
                continue;
            }

            // === FORWARD CHECK: after committing this group, verify that
            // non-elective units still have at least one valid placement.
            // This catches dead branches before recursing deeper.
            if (depth < descriptors.size() - 1) {
                if (!quickFeasibilityCheck(semUnits, slots, grid)) {
                    rollbackElectiveGroup(desc, grid);
                    solverCtx.forwardCheckPrunes++;
                    log.debug("ElectiveGroupCombinations: depth={} pattern {}/{} pruned by forward check",
                            depth, ci + 1, desc.validCandidates.size());
                    continue;
                }
            }

            if (tryElectiveGroupCombinations(descriptors, depth + 1, semUnits, slots,
                    generation, created, failureReport, grid, sectionDayLoads, solverCtx)) {
                log.info("ElectiveGroupCombinations: depth={} pattern {}/{} SUCCEEDED", depth, ci + 1, desc.validCandidates.size());
                return true;
            }

            // Early exit: if the limit was hit deep in recursion, propagate up
            if (solverCtx.electiveCombos > MAX_ELECTIVE_COMBINATION_ATTEMPTS || solverCtx.isTimeUp()) {
                rollbackElectiveGroup(desc, grid);
                return false;
            }

            // Main solver failed â€” roll back this group and try next candidate
            rollbackElectiveGroup(desc, grid);
            failureReport.clear();
        }
        return false;
    }

    private boolean validateElectiveGroupPattern(String electiveGroup,
                                                  List<SchedulingUnit> groupUnits,
                                                  Set<UUID> allStaffIds,
                                                  UUID semesterId, UUID sectionId,
                                                  List<TimeWindow> windows,
                                                  ConflictGrid grid) {
        for (TimeWindow w : windows) {
            int mask = ConflictGrid.periodMask(w.startOrder, w.endOrder);

            Map<UUID, Map<Integer, Map<Object, ConflictGrid.SectionOcc>>> secDays =
                    grid.sections.get(semesterId);
            if (secDays != null) {
                Map<Integer, Map<Object, ConflictGrid.SectionOcc>> dayOcc = secDays.get(sectionId);
                if (dayOcc != null) {
                    Map<Object, ConflictGrid.SectionOcc> occ = dayOcc.get(w.day);
                    if (occ != null) {
                        for (ConflictGrid.SectionOcc o : occ.values()) {
                            if ((o.mask & mask) != 0) {
                                if (!electiveGroup.equals(o.groupKey)
                                        || o.start != w.startOrder || o.end != w.endOrder) {
                                    return false;
                                }
                            }
                        }
                    }
                }
            }

            for (UUID staffId : allStaffIds) {
                int conflict = ConflictGrid.dayMask(grid.staff, staffId, w.day) & mask;
                if (conflict != 0) {
                    // Same-group staff overlap is allowed within the SAME section only.
                    Map<UUID, Map<Integer, Map<Integer, String>>> bySection = grid.staffGroupKeys.get(staffId);
                    if (bySection == null) return false;
                    Map<Integer, Map<Integer, String>> secDay = bySection.get(sectionId);
                    if (secDay == null) return false;
                    Map<Integer, String> gk = secDay.get(w.day);
                    if (gk == null) return false;
                    for (int p = w.startOrder; p <= w.endOrder; p++) {
                        if ((conflict & (1 << (p - 1))) != 0) {
                            String existing = gk.get(p);
                            if (existing == null || !existing.equals(electiveGroup)) {
                                return false;
                            }
                        }
                    }
                }
            }
        }
        return true;
    }

/**
     * Represents a valid placement option for a unit session.
     */
    private static class PlacementOption {
        final int day;
        final int startIdx;
        final int endIdx;
        final int startOrder;
        final int endOrder;

        PlacementOption(int day, int startIdx, int periodsPerSession) {
            this.day = day;
            this.startIdx = startIdx;
            this.endIdx = startIdx + periodsPerSession - 1;
            this.startOrder = startIdx + 1;
            this.endOrder = startIdx + periodsPerSession;
        }
    }

    /**
     * Represents a group-level elective pattern: ONE scheduling decision for an
     * entire elective group in a section.  All member elective courses share the
     * same set of weekly windows, so the physical footprint equals the number of
     * distinct windows (not the sum of member cells).
     *
     * <p>Example for three 2x2 electives in Sem-7:
     *   windows = [{Mon, P1-P2}, {Thu, P4-P5}]
     *   physical cells = 4  (not 12)
     */
    private static class ElectiveGroupSchedule {
        final String electiveGroup;   // semesterId.toString()
        final UUID semesterId;
        final UUID sectionId;
        final List<TimeWindow> windows;  // ordered by occurrence (1st, 2nd, ...)

        ElectiveGroupSchedule(String electiveGroup, UUID semesterId, UUID sectionId,
                              List<TimeWindow> windows) {
            this.electiveGroup = electiveGroup;
            this.semesterId = semesterId;
            this.sectionId = sectionId;
            this.windows = windows;
        }
    }

    private static class TimeWindow {
        final int day;
        final int startOrder;
        final int endOrder;

        TimeWindow(int day, int startOrder, int endOrder) {
            this.day = day;
            this.startOrder = startOrder;
            this.endOrder = endOrder;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof TimeWindow tw)) return false;
            return day == tw.day && startOrder == tw.startOrder && endOrder == tw.endOrder;
        }

        @Override
        public int hashCode() {
            return Objects.hash(day, startOrder, endOrder);
        }
    }

    // ========== SOLVER CONTEXT ==========
    /** Carries solver limits, deadlines, and counters through the entire solve chain.
     *  Prevents runaway backtracking and combinatorial explosion. */
    private static class SolverContext {
        final long semesterDeadlineMs;   // per-semester wall-clock deadline
        final long generationDeadlineMs; // global wall-clock deadline
        final long startTimeMs;          // when generation started
        int electiveCombos;              // combos tried this semester
        int totalSolveAttempts;          // total solve restart attempts
        long totalNodes;                 // total backtracking nodes across all attempts
        int forwardCheckPrunes;          // combos pruned by forward check after commit
        int prefilterPrunes;             // candidates pruned by pre-filtering before commit
        int candidatesSkipped;           // candidates skipped (pre-filtered)

        SolverContext(long generationDeadlineMs) {
            this.generationDeadlineMs = generationDeadlineMs;
            this.startTimeMs = System.currentTimeMillis();
            this.semesterDeadlineMs = Math.min(
                    generationDeadlineMs, startTimeMs + SEMESTER_MAX_TIME_MS);
        }

        long elapsedMs() {
            return System.currentTimeMillis() - startTimeMs;
        }

        boolean isTimeUp() {
            return System.currentTimeMillis() >= semesterDeadlineMs
                    || System.currentTimeMillis() >= generationDeadlineMs;
        }

        void logSummary(String semesterInfo) {
            log.info("SolverContext [{}]: combos={}, solveAttempts={}, nodes={}, forwardPrunes={}, prefilterSkipped={}, elapsed={}ms, timeUp={}",
                    semesterInfo, electiveCombos, totalSolveAttempts, totalNodes,
                    forwardCheckPrunes, candidatesSkipped, elapsedMs(), isTimeUp());
        }
    }

    private static UUID unitOwnerKey(SchedulingUnit u) {
        return u.assignment != null ? u.assignment.getAssignmentId() : u.group.getGroupId();
    }

    /**
     * Course identity shared by all components of a course (one unit per CMR row):
     * the assignment object for singletons, the group object for co-taught groups.
     * Used to key course-level state (used days, occurrence, capacity total).
     */
    private static Object courseKey(SchedulingUnit u) {
        return u.assignment != null ? u.assignment : u.group;
    }

    /**
     * Randomized-restart wrapper around the backtracking search. Symmetric
     * elective/combined configurations can make a single deterministic pass
     * thrash; restarting with a different unit order and option perturbation
     * (bounded total work) escapes those plateaus.
     */
    private boolean solveWithRestarts(List<SchedulingUnit> semUnits, List<TimeSlot> slots,
                                      GenerationSession generation, List<ClassSchedule> created,
                                      List<String> failureReport, ConflictGrid grid,
                                      Map<String, int[]> sectionDayLoads, SolverContext solverCtx) {
        solverDiag.reset();

        // ===== PRECOMPUTE DEPENDENCY GRAPH =====
        // For each unit, find all other units that share a staff member or section.
        // When a unit is placed, only affected units' candidate caches need invalidation.
        Map<SchedulingUnit, Set<SchedulingUnit>> dependencies = computeDependencies(semUnits);

        for (int attempt = 0; attempt < MAX_SOLVE_ATTEMPTS; attempt++) {
            if (solverCtx.isTimeUp()) {
                log.warn("solveWithRestarts: time limit reached at attempt {}/{}", attempt + 1, MAX_SOLVE_ATTEMPTS);
                break;
            }
            solverCtx.totalSolveAttempts++;
            if (RAND_SEED_OVERRIDE == null) {
                SOLVE_RANDOM.set(new Random(0x5EED + attempt * 101L));
            } else {
                SOLVE_RANDOM.set(new Random(Long.parseLong(RAND_SEED_OVERRIDE)));
            }
            List<SchedulingUnit> attemptUnits = new ArrayList<>(semUnits);
            if (attempt > 0) {
                Collections.shuffle(attemptUnits, SOLVE_RANDOM.get());
            }
            int[] counter = {0};
            boolean[] iterationLimitReached = {false};
            Map<Object, Set<Integer>> usedDaysByCourse = new HashMap<>();
            Map<SchedulingUnit, Integer> placedCounts = new HashMap<>();
            Map<SchedulingUnit, List<PlacementOption>> candidateCache = new IdentityHashMap<>();
            if (solveRecursive(attemptUnits, slots, generation, created, failureReport,
                    counter, iterationLimitReached, grid, usedDaysByCourse, placedCounts,
                    sectionDayLoads, solverCtx, dependencies, candidateCache)) {
                return true;
            }
            if (!iterationLimitReached[0]) {
                return false; // genuinely UNSAT, not just limit
            }
            failureReport.clear();
        }
        if (failureReport.isEmpty()) {
            failureReport.add("No valid timetable exists for the given constraints.");
        }
        return false;
    }

    private boolean solveRecursive(List<SchedulingUnit> units, List<TimeSlot> slots,
                                    GenerationSession generation, List<ClassSchedule> created,
                                    List<String> failureReport, int[] counter,
                                    boolean[] iterationLimitReached, ConflictGrid grid,
                                    Map<Object, Set<Integer>> usedDaysByCourse,
                                    Map<SchedulingUnit, Integer> placedCounts,
                                    Map<String, int[]> sectionDayLoads, SolverContext solverCtx,
                                    Map<SchedulingUnit, Set<SchedulingUnit>> dependencies,
                                    Map<SchedulingUnit, List<PlacementOption>> candidateCache) {
        if (unitIdx(units, placedCounts) >= units.size()) return true;

        solverCtx.totalNodes++;
        solverDiag.nodesExplored++;
        if (++counter[0] > MAX_NODES_PER_ATTEMPT || solverCtx.isTimeUp()) {
            iterationLimitReached[0] = true;
            return false;
        }

        int depth = unitIdx(units, placedCounts);
        if (depth > solverDiag.depthMax) solverDiag.depthMax = depth;

        // Snapshot diagnostics before MRV
        int gvpBefore = solverDiag.gvpCalls;
        int canPlaceBefore = solverDiag.canPlaceCalls;

        // Most-constrained-first (with candidate cache)
        SchedulingUnit bestUnit = selectMostConstrainedUnit(units, slots, grid, usedDaysByCourse, placedCounts,
                    candidateCache, dependencies);
        if (bestUnit == null) return true;

        int gvpInMRV = solverDiag.gvpCalls - gvpBefore;
        int canPlaceInMRV = solverDiag.canPlaceCalls - canPlaceBefore;

        Set<Integer> usedDays = usedDaysByCourse.getOrDefault(courseKey(bestUnit), new HashSet<>());
        List<PlacementOption> options = generateValidPlacements(bestUnit, usedDays, slots, grid);

        // Record per-depth stats (MRV GVP calls + final GVP call)
        solverDiag.depthHistogram[depth]++;
        solverDiag.unplacedAtDepth[depth] += (solverDiag.mrvUnitsScanned); // cumulative
        solverDiag.gvpCallsAtDepth[depth] += gvpInMRV + 1; // MRV calls + 1 for final generation
        solverDiag.canPlaceAtDepth[depth] += solverDiag.canPlaceCalls - canPlaceBefore;
        solverDiag.optionsAtDepth[depth] = options.size();

        // === EXP-F: cached-vs-fresh candidate comparison (instrument only) ===
        if (INSTR_TRACE && depth <= 40) {
            List<PlacementOption> cached = candidateCache.get(bestUnit);
            if (cached != null) {
                boolean sameOrder = cached.size() == options.size();
                if (sameOrder) {
                    for (int i = 0; i < cached.size(); i++) {
                        PlacementOption co = cached.get(i), fo = options.get(i);
                        if (co.day != fo.day || co.startIdx != fo.startIdx
                                || co.endOrder != fo.endOrder) { sameOrder = false; break; }
                    }
                }
                log.info("[EXPCACHE] depth={} unit={} cachedCount={} freshCount={} sameOrder={}",
                        depth, bestUnit.label, cached.size(), options.size(), sameOrder);
            }
        }
        // === END EXP-F ===

        // === SEARCH PATH TRACE (first 10 depths only) ===
        if (depth < 10 && solverDiag.nodesExplored <= 500) {
            String courseCode = bestUnit.assignment != null
                    ? bestUnit.assignment.getCourse().getCourseCode()
                    : (bestUnit.group != null ? bestUnit.group.getCourse().getCourseCode() : "?");
            log.info("TRACE depth={} unit={} course={} options={} mrvScanned={}",
                    depth, bestUnit.label, courseCode, options.size(), solverDiag.mrvUnitsScanned);
            if (!options.isEmpty() && options.size() <= 5) {
                for (PlacementOption opt : options) {
                    log.info("  option: {} P{}-P{}", DAY_NAMES[opt.day], opt.startIdx + 1,
                            opt.startIdx + bestUnit.periodsPerSession);
                }
            }

            // === PRIORITY LOGGING (first 20 scheduling decisions) ===
            if (depth < 20 && solverDiag.nodesExplored <= 500) {
                int sp = getStructuralPriority(bestUnit);
                boolean combined = bestUnit.isCombined();
                String sectionStr = bestUnit.sectionIds.stream()
                        .map(id -> id.toString().substring(0, 8))
                        .collect(java.util.stream.Collectors.joining(","));
                log.info("[PRIORITY] depth={} selected={} cmr={} shape={} priority={} combined={} anchor={} options={} section={}",
                        depth, courseCode, cmrString(bestUnit), structuralTierLabel(sp), sp,
                        combined, solverDiag.anchorPhase, options.size(), sectionStr);
            }
            // === END PRIORITY LOGGING ===
        }
        // === END SEARCH PATH TRACE ===

        if (options.isEmpty()) {
            // === FAILURE CERTIFICATE ===
            // Emitted ONLY for the first failure of the current attempt tree
            // (mirrors the diagnoseUnit gate below); repeated failures are normal
            // backtracking and must not flood the log with identical certificates.
            if (failureReport.isEmpty()) {
                String courseCode = bestUnit.assignment != null
                        ? bestUnit.assignment.getCourse().getCourseCode()
                        : (bestUnit.group != null ? bestUnit.group.getCourse().getCourseCode() : "?");
                String sectionName = bestUnit.sectionIds.stream()
                        .map(UUID::toString).collect(java.util.stream.Collectors.joining(","));
                String staffStr = bestUnit.staffIds.stream()
                        .map(UUID::toString).collect(java.util.stream.Collectors.joining(","));
                String cmr = bestUnit.sessionsPerWeek + "x" + bestUnit.periodsPerSession;
                String type = bestUnit.isElective() ? "ELECTIVE" : "REQUIRED";

                log.info("FAILURE CERTIFICATE: depth={}, unit={}, course={}, section={}, staff={}, cmr={}, type={}, combined={}",
                        depth, bestUnit.label, courseCode, sectionName, staffStr, cmr, type,
                        bestUnit.isCombined());

                // Classify every rejected placement
                Map<String, Integer> rejectionCounts = new LinkedHashMap<>();
                for (int day = WORKING_DAY_START; day <= WORKING_DAY_END; day++) {
                    if (usedDays.contains(day)) {
                        rejectionCounts.merge("USED_DAY", 1, Integer::sum);
                        continue;
                    }
                    for (int startIdx = 0; startIdx + bestUnit.periodsPerSession <= slots.size(); startIdx++) {
                        if (!consecutiveSlots(slots, startIdx, bestUnit.periodsPerSession)) continue;
                        PlacementOption probe = new PlacementOption(day, startIdx, bestUnit.periodsPerSession);
                        String reason = classifyRejection(bestUnit, probe, slots, grid, usedDaysByCourse);
                        rejectionCounts.merge(reason.contains("(") ? reason.substring(0, reason.indexOf('(')) : reason, 1, Integer::sum);
                    }
                }
                log.info("  Rejection summary: {}", rejectionCounts);
                log.info("  Used days: {}", usedDays);
                log.info("  Frozen staff at depth {}: {}", depth, grid.getFrozenStaffCount());
                log.info("  Grid staff occupancy sample:");
                for (UUID staffId : bestUnit.staffIds) {
                    Map<Integer, Integer> dayMasks = grid.staff.get(staffId);
                    if (dayMasks != null) {
                        for (int d = WORKING_DAY_START; d <= WORKING_DAY_END; d++) {
                            Integer mask = dayMasks.get(d);
                            if (mask != null && mask != 0) {
                                log.info("    staff={} day={} mask={}", staffId, DAY_NAMES[d], Integer.toBinaryString(mask));
                            }
                        }
                    }
                }
            }
            // === END FAILURE CERTIFICATE ===

            if (failureReport.isEmpty()) {
                diagnoseUnit(bestUnit, slots, usedDays, created, failureReport);
            }
            return false;
        }

        // Score options
        long t0 = System.nanoTime();
        Map<PlacementOption, Integer> optionScores = new HashMap<>();
        for (PlacementOption opt : options) {
            optionScores.put(opt, evaluatePlacement(bestUnit, opt, units, slots, grid, usedDaysByCourse, placedCounts, sectionDayLoads));
        }
        solverDiag.scoringTimeNs += System.nanoTime() - t0;
        solverDiag.scoringCalls += options.size();
        options.sort((a, b) -> Integer.compare(optionScores.get(b), optionScores.get(a)));

        if (INSTR_TRACE && depth <= 40) {
            String cc = bestUnit.assignment != null
                    ? bestUnit.assignment.getCourse().getCourseCode()
                    : (bestUnit.group != null ? bestUnit.group.getCourse().getCourseCode() : "?");
            String secStr = bestUnit.sectionIds.stream()
                    .map(UUID::toString).collect(java.util.stream.Collectors.joining(","));
            String cmr = bestUnit.sessionsPerWeek + "x" + bestUnit.periodsPerSession;
            log.info("[EXPTRACE] depth={} unit={} course={} sec={} cmr={} prio={} combined={} mrvCands={}",
                    depth, bestUnit.label, cc, secStr, cmr,
                    getStructuralPriority(bestUnit), bestUnit.isCombined(), options.size());
            int shown = 0;
            for (PlacementOption opt : options) {
                if (shown >= 5) break;
                int dayBal = dayBalancePenalty(bestUnit, opt, sectionDayLoads);
                int consec = consecutivePreservationPenalty(bestUnit, opt, grid);
                int tot = optionScores.get(opt);
                log.info("[EXPCAND] day={} name={} P{}-P{} score={} dayBal={} consec={}",
                        opt.day, DAY_NAMES[opt.day], opt.startIdx + 1,
                        opt.startIdx + bestUnit.periodsPerSession, tot, dayBal, consec);
                shown++;
            }
        }

        Random rand = SOLVE_RANDOM.get();
        if (!ABLATE_RAND) { // Mode 5: skip randomization
            for (int i = 1; i < options.size(); i++) {
                if (rand.nextDouble() < 0.25) {
                    PlacementOption tmp = options.get(i);
                    options.set(i, options.get(i - 1));
                    options.set(i - 1, tmp);
                }
            }
        }
        if (INSTR_TRACE && !options.isEmpty() && depth <= 40) {
            PlacementOption chosen = options.get(0);
            log.info("[EXPCHOSEN] depth={} day={} P{}-P{} score={}",
                    depth, DAY_NAMES[chosen.day], chosen.startIdx + 1,
                    chosen.startIdx + bestUnit.periodsPerSession, optionScores.get(chosen));
        }

        // Invalidate bestUnit's own cache (it's about to be placed)
        candidateCache.remove(bestUnit);

        for (PlacementOption opt : options) {
            int occurrence = usedDays.size() + 1;
            int periodCount = opt.endOrder - opt.startOrder + 1;
            ClassSchedule sched = buildSchedule(generation, bestUnit, opt.day, opt.startIdx, slots);
            created.add(sched);
            solverDiag.placements++;
            placedCounts.merge(bestUnit, 1, Integer::sum);
            grid.place(bestUnit.staffIds, bestUnit.sectionIds, bestUnit.semesterId,
                    opt.day, opt.startOrder, opt.endOrder, bestUnit.electiveGroup, bestUnit,
                    occurrence);
            for (UUID sectionId : bestUnit.sectionIds) {
                int[] loads = sectionDayLoads.computeIfAbsent(bestUnit.semesterId + "|" + sectionId, k -> new int[6]);
                loads[opt.day] += periodCount;
            }
            usedDays.add(opt.day);
            usedDaysByCourse.put(courseKey(bestUnit), usedDays);

            // Invalidate caches for units affected by this placement
            invalidateAffected(bestUnit, dependencies, candidateCache);

            // Bounded combined-anchor diagnostic: emitted once per combined group
            // the first time its FULL placement (all sessions) is established.
            if (bestUnit.isCombined()
                    && placedCounts.getOrDefault(bestUnit, 0) == bestUnit.sessionsPerWeek
                    && bestUnit.anchorSectionId != null
                    && combinedAnchorEmitted.add(bestUnit.group.getGroupId().toString())) {
                emitCombinedAnchor(bestUnit, grid);
            }

            if (solveRecursive(units, slots, generation, created, failureReport, counter, iterationLimitReached, grid, usedDaysByCourse, placedCounts, sectionDayLoads, solverCtx, dependencies, candidateCache)) {
                return true;
            }

            // Backtrack
            created.remove(created.size() - 1);
            solverDiag.backtracks++;
            placedCounts.merge(bestUnit, -1, Integer::sum);
            if (placedCounts.get(bestUnit) <= 0) {
                placedCounts.remove(bestUnit);
            }
            grid.remove(bestUnit.staffIds, bestUnit.sectionIds, bestUnit.semesterId,
                    opt.day, opt.startOrder, opt.endOrder, bestUnit.electiveGroup, bestUnit,
                    occurrence);
            for (UUID sectionId : bestUnit.sectionIds) {
                int[] loads = sectionDayLoads.get(bestUnit.semesterId + "|" + sectionId);
                if (loads != null) {
                    loads[opt.day] -= periodCount;
                }
            }
            usedDays.remove(opt.day);
            if (usedDays.isEmpty()) {
                usedDaysByCourse.remove(courseKey(bestUnit));
            }

            // Invalidate caches for units affected by this undo
            invalidateAffected(bestUnit, dependencies, candidateCache);
        }

        return false;
    }

    private int unitIdx(List<SchedulingUnit> units, Map<SchedulingUnit, Integer> placedCounts) {
        // Count how many units have all their sessions placed (component-accurate:
        // each unit counts only its own placed schedules, not its course's total)
        int placed = 0;
        for (SchedulingUnit u : units) {
            if (placedCounts.getOrDefault(u, 0) >= u.sessionsPerWeek) placed++;
            else break;
        }
        return placed;
    }

    /**
     * Precompute dependency graph: for each unit, which other units share
     * a staff member or section ID. When unit A is placed, only units
     * in dependencies[A] may have their candidate lists invalidated.
     * Complexity: O(N^2 * maxResourcesPerUnit) â€” done once before solving.
     */
    private Map<SchedulingUnit, Set<SchedulingUnit>> computeDependencies(List<SchedulingUnit> units) {
        Map<SchedulingUnit, Set<SchedulingUnit>> deps = new HashMap<>();
        for (SchedulingUnit u : units) {
            deps.put(u, new HashSet<>());
        }
        for (int i = 0; i < units.size(); i++) {
            SchedulingUnit a = units.get(i);
            for (int j = i + 1; j < units.size(); j++) {
                SchedulingUnit b = units.get(j);
                if (a.semesterId != null && !a.semesterId.equals(b.semesterId)) continue;
                if (!Collections.disjoint(a.staffIds, b.staffIds)
                        || !Collections.disjoint(a.sectionIds, b.sectionIds)) {
                    deps.get(a).add(b);
                    deps.get(b).add(a);
                }
            }
        }
        return deps;
    }

    /**
     * MRV with candidate cache. Units whose candidates are still valid
     * (not invalidated by a peer's placement) are looked up from cache
     * in O(1) instead of recomputing O(slots Ã— canPlace) per unit.
     */
    // ========== STRUCTURAL PRIORITY ==========
    // Priority 1 = 4x1 (most constrained â€” needs 4 different days)
    // Priority 2 = mixed 2x1 + 1x2 (one course requirement spanning both shapes)
    // Priority 3 = 2x2 (needs 2 consecutive-period windows)
    // Priority 4 = everything else (pure 2x1, pure 1x2, 3x1, 1x1, electives, ...)

    /**
     * Classify a course's COMPLETE requirement shape into a structural tier.
     * Each CMR row becomes its own scheduling unit, but the mixed 2x1 + 1x2
     * requirement is one business course spanning two rows, so it must be
     * classified at the course level, not per row.
     */
    static int classifyCourseShape(List<CourseMeetingRequirement> reqs) {
        int onePeriodSessions = 0;
        int twoPeriodSessions = 0;
        boolean has2x1 = false;
        boolean has1x2 = false;
        boolean allOnePeriod = true;
        for (CourseMeetingRequirement r : reqs) {
            int spw = r.getSessionsPerWeek();
            int pps = r.getPeriodsPerSession();
            if (pps == 1) {
                onePeriodSessions += spw;
                if (spw == 2) has2x1 = true;
            } else {
                allOnePeriod = false;
                if (pps == 2) {
                    twoPeriodSessions += spw;
                    if (spw == 1) has1x2 = true;
                }
            }
        }
        if (allOnePeriod && onePeriodSessions >= 4) return 1;   // 4x1
        if (has2x1 && has1x2) return 2;                          // mixed 2x1 + 1x2
        if (twoPeriodSessions >= 2) return 3;                    // 2x2
        return 4;                                                // other
    }

    /**
     * Structural scheduling priority for a unit.
     * Lower number = higher urgency = should be placed first.
     * Purely based on the course's CMR shape; does NOT consider candidate count.
     * Precomputed when the unit is built (per-course aggregate classification).
     */
    static int getStructuralPriority(SchedulingUnit u) {
        return u.structuralPriority;
    }

    static String cmrString(SchedulingUnit u) {
        return u.sessionsPerWeek + "x" + u.periodsPerSession;
    }

    /** Human-readable structural tier label for diagnostics. */
    static String structuralTierLabel(int sp) {
        return switch (sp) {
            case 1 -> "4x1";
            case 2 -> "mixed 2x1+1x2";
            case 3 -> "2x2";
            default -> "other";
        };
    }

    private SchedulingUnit selectMostConstrainedUnit(List<SchedulingUnit> units,
                                                       List<TimeSlot> slots, ConflictGrid grid,
                                                       Map<Object, Set<Integer>> usedDaysByCourse,
                                                       Map<SchedulingUnit, Integer> placedCounts,
                                                       Map<SchedulingUnit, List<PlacementOption>> candidateCache,
                                                       Map<SchedulingUnit, Set<SchedulingUnit>> dependencies) {
        SchedulingUnit best = null;
        int bestPriority = Integer.MAX_VALUE;
        boolean bestCombined = false;
        int minOptions = Integer.MAX_VALUE;
        int unplacedCount = 0;

        // Anchor phase: while ANY unplaced combined (linked) group remains, the
        // combined groups win over every structural tier. This is where combined
        // slots are decided ONCE (Section A authoritative), before any section-only
        // unit can fragment the shared A/B/CT grids. After all combined groups are
        // fully placed, anchorPhase turns false and the normal 4x1 > mixed > 2x2
        // > others MRV ordering applies (no combined units remain to tie-break).
        boolean anyUnplacedCombined = false;
        SchedulingUnit anchorCandidate = null;
        int minCombinedOptions = Integer.MAX_VALUE;

        for (SchedulingUnit u : units) {
            if (placedCounts.getOrDefault(u, 0) >= u.sessionsPerWeek) continue;
            unplacedCount++;

            int priority = getStructuralPriority(u);
            boolean combined = u.isCombined();

            Set<Integer> usedDays = usedDaysByCourse.getOrDefault(courseKey(u), new HashSet<>());
            long t0 = System.nanoTime();

            List<PlacementOption> options = candidateCache.get(u);
            if (options == null || usedDays.size() > 0) {
                options = generateValidPlacements(u, usedDays, slots, grid);
                candidateCache.put(u, options);
                solverDiag.cacheMisses++;
            } else {
                solverDiag.cacheHits++;
            }

            solverDiag.mrvTimeNs += System.nanoTime() - t0;
            int optionsCount = options.size();

            solverDiag.mrvUnitsScanned++;
            solverDiag.totalOptionsReturned += optionsCount;

            if (optionsCount == 0) {
                solverDiag.anchorPhase = combined;
                return u;
            }

            if (combined) {
                // Anchor phase candidate: most-constrained combined group first.
                anyUnplacedCombined = true;
                if (optionsCount < minCombinedOptions) {
                    anchorCandidate = u;
                    minCombinedOptions = optionsCount;
                }
            }

            // Selection order: structural priority, then combined-class first,
            // then MRV (fewer options), then deterministic first-seen tie-break.
            // Zero-domain units are returned immediately above (unchanged).
            if (best == null) {
                best = u;
                bestPriority = priority;
                bestCombined = combined;
                minOptions = optionsCount;
            } else if (priority < bestPriority) {
                best = u;
                bestPriority = priority;
                bestCombined = combined;
                minOptions = optionsCount;
            } else if (priority == bestPriority) {
                if (combined && !bestCombined) {
                    best = u;
                    bestCombined = true;
                    minOptions = optionsCount;
                } else if (combined == bestCombined && optionsCount < minOptions) {
                    best = u;
                    minOptions = optionsCount;
                }
            }
        }
        solverDiag.mrvCalls++;
        solverDiag.anchorPhase = anchorCandidate != null;
        if (anchorCandidate != null) {
            return anchorCandidate;
        }
        return best;
    }

    /**
     * Emits the bounded combined-anchor diagnostics. One linked combined unit is
     * placed in ALL member sections simultaneously, so the anchor section's
     * windows ARE the authoritative windows that every other member section
     * inherits (they occupy the exact same day/start/end entries in the grid).
     */
    private void emitCombinedAnchor(SchedulingUnit u, ConflictGrid grid) {
        String groupId = u.group.getGroupId().toString();
        String code = u.group.getCourse().getCourseCode();
        List<String> wins = new ArrayList<>();
        Map<UUID, Map<Integer, Map<Object, ConflictGrid.SectionOcc>>> secDays = grid.sections.get(u.semesterId);
        if (secDays != null) {
            Map<Integer, Map<Object, ConflictGrid.SectionOcc>> dayOcc = secDays.get(u.anchorSectionId);
            if (dayOcc != null) {
                for (int d = WORKING_DAY_START; d <= WORKING_DAY_END; d++) {
                    Map<Object, ConflictGrid.SectionOcc> occ = dayOcc.get(d);
                    if (occ == null) continue;
                    ConflictGrid.SectionOcc o = occ.get(u);
                    if (o != null) wins.add(DAY_NAMES[d] + " P" + o.start + "-P" + o.end);
                }
            }
        }
        String placements = wins.isEmpty() ? "?" : String.join(", ", wins);
        log.info("[COMBINED-ANCHOR] group={} course={} sections={} anchorSection={} placements=[{}]",
                groupId, code, u.sectionNamesLabel, u.anchorSectionName, placements);
        for (String member : u.sectionNamesLabel.split("\\+")) {
            if (member.equals(u.anchorSectionName)) continue;
            log.info("[COMBINED-INHERIT] group={} section={} sourceSection={} placements=[{}]",
                    groupId, member, u.anchorSectionName, placements);
        }
    }

    /** Invalidate candidate caches for all units dependent on the placed unit. */
    private void invalidateAffected(SchedulingUnit placed,
                                     Map<SchedulingUnit, Set<SchedulingUnit>> dependencies,
                                     Map<SchedulingUnit, List<PlacementOption>> candidateCache) {
        Set<SchedulingUnit> affected = dependencies.get(placed);
        if (affected != null) {
            for (SchedulingUnit dep : affected) {
                if (candidateCache.remove(dep) != null) {
                    solverDiag.cacheInvalidations++;
                }
            }
        }
    }

private List<PlacementOption> generateValidPlacements(SchedulingUnit unit, Set<Integer> usedDays,
                                                          List<TimeSlot> slots, ConflictGrid grid) {
        // Elective co-location is a hard constraint: once any group member has
        // established the window for weekly occurrence (usedDays.size()+1) in this
        // section, every other member must use that exact window. A component may
        // only co-locate when its shape matches the established window: a 1-period
        // component must never be forced into a 2-period window (and vice versa).
        int occurrence = usedDays.size() + 1;
        int[] forced = (unit.isElective() && !unit.sectionIds.isEmpty())
                ? grid.forcedWindow(unit.electiveGroup, unit.sectionIds.iterator().next(),
                        unit.semesterId, occurrence)
                : null;
        if (forced != null && (forced[2] - forced[1] + 1) != unit.periodsPerSession) {
            forced = null;
        }

        solverDiag.gvpCalls++;
        List<PlacementOption> options = new ArrayList<>();
        for (int day = WORKING_DAY_START; day <= WORKING_DAY_END; day++) {
            if (usedDays.contains(day)) continue;

            for (int startIdx = 0; startIdx + unit.periodsPerSession <= slots.size(); startIdx++) {
                if (!consecutiveSlots(slots, startIdx, unit.periodsPerSession)) continue;
                int startOrder = startIdx + 1;
                int endOrder = startIdx + unit.periodsPerSession;
                if (forced != null && (day != forced[0] || startOrder != forced[1] || endOrder != forced[2])) {
                    continue;
                }
                solverDiag.canPlaceCalls++;
                if (!grid.canPlace(unit.staffIds, unit.sectionIds, unit.semesterId,
                        day, startOrder, endOrder, unit.electiveGroup)) continue;

                options.add(new PlacementOption(day, startIdx, unit.periodsPerSession));
            }
        }
        return options;
    }

    private int evaluatePlacement(SchedulingUnit unit, PlacementOption opt,
                                   List<SchedulingUnit> units,
                                   List<TimeSlot> slots, ConflictGrid grid,
                                   Map<Object, Set<Integer>> usedDaysByCourse,
                                   Map<SchedulingUnit, Integer> placedCounts,
                                   Map<String, int[]> sectionDayLoads) {
        // Soft candidate-ordering heuristic:
        //   1. day-balance penalty (existing behaviour: spread weekday load)
        //   2. consecutive-window preservation penalty (new: keep free runs intact
        //      so later 2-period sessions still have adjacent capacity).
        // Both are soft. A hard conflict is rejected earlier in canPlace();
        // a worse score merely lowers priority. Randomization (the 25%
        // perturbation in solveRecursive) still diversifies ties.
        int dayBal = dayBalancePenalty(unit, opt, sectionDayLoads);
        int consec = consecutivePreservationPenalty(unit, opt, grid);

        // Ablation dispatch (EXP-ONLY; default path = current production logic).
        if (ABLATE_SOFT) return 0;                      // Mode 2: no soft scoring at all
        String sm = SCORE_ABLATE;
        if (sm != null) {
            switch (sm) {
                case "zero": return 0;
                case "daybal": return -dayBal;
                case "consec": return (int) -(long) CONSECUTIVE_PRESERVATION_WEIGHT * consec;
                case "both": {
                    return (int) -(dayBal + (long) CONSECUTIVE_PRESERVATION_WEIGHT * consec);
                }
                case "current":
                default: break;
            }
        }
        int penalty = dayBal;
        if (CONSECUTIVE_PRESERVATION_WEIGHT > 0 && !ABLATE_CONSEC) {
            penalty += (long) CONSECUTIVE_PRESERVATION_WEIGHT * consec;
        }
        if (ABLATE_DAYBAL) penalty = 0;                 // Mode 4: only consec remains
        else if (ABLATE_CONSEC) penalty = dayBal;       // Mode 3: only day-balance remains
        return -penalty;
    }

    /**
     * Consecutive-capacity preservation penalty (soft, ordering-only).
     * For each affected section, computes how many free adjacent 2-period
     * windows (P1-P2, P2-P3, P3-P4, P4-P5, P5-P6) on the placement day would
     * be destroyed by this candidate. Lower is better: candidates that keep
     * longer free runs intact preserve consecutive capacity for future
     * two-period sessions. Computed from the section occupancy masks already
     * tracked by the ConflictGrid in O(1)-ish bitmask arithmetic â€” no DB access,
     * no full re-enumeration, and no change to canPlace() semantics.
     */
    private int consecutivePreservationPenalty(SchedulingUnit unit, PlacementOption opt,
                                               ConflictGrid grid) {
        int penalty = 0;
        int placementMask = ConflictGrid.periodMask(opt.startOrder, opt.endOrder);
        Map<UUID, Map<Integer, Map<Object, ConflictGrid.SectionOcc>>> bySem =
                grid.sections.get(unit.semesterId);
        for (UUID sectionId : unit.sectionIds) {
            int occ = 0;
            if (bySem != null) {
                Map<Integer, Map<Object, ConflictGrid.SectionOcc>> byDay = bySem.get(sectionId);
                if (byDay != null) {
                    Map<Object, ConflictGrid.SectionOcc> occs = byDay.get(opt.day);
                    if (occs != null) {
                        for (ConflictGrid.SectionOcc o : occs.values()) occ |= o.mask;
                    }
                }
            }
            int before = freeAdjacentWindows(occ);
            int after = freeAdjacentWindows(occ | placementMask);
            penalty += (before - after);
        }
        return penalty;
    }

    /**
     * Counts free adjacent 2-period windows (P1-P2 .. P5-P6) in a 6-bit period mask
     * (bit0 = P1 .. bit5 = P6). Only consecutive window capacity is counted.
     */
    static int freeAdjacentWindows(int mask) {
        int count = 0;
        for (int p = 1; p <= 5; p++) {
            int window = (1 << (p - 1)) | (1 << p);
            if ((mask & window) == 0) count++;
        }
        return count;
    }

    /**
     * Number of free adjacent 2-period windows destroyed by applying a placement
     * bitmask onto a section's current occupancy mask. Basis for the capacity
     * reservation heuristic: a placement that breaks free runs (middle of a run)
     * destroys more windows than one at the edge of a run, and would-be pairs for
     * future 2-period sessions are preserved by preferring the latter.
     * Pure function â€” no DB / no grid dependency; unit-testable.
     */
    static int destroyedAdjacentWindows(int occupiedMask, int placementMask) {
        return freeAdjacentWindows(occupiedMask)
                - freeAdjacentWindows(occupiedMask | placementMask);
    }

    /**
     * Sum of squared deviations of the affected sections' weekday COURSE loads
     * after this placement would be applied. Lower is better; 0 = perfectly
     * balanced. Only COURSE periods count - LMS/ASSIGNMENT/BREAK never appear
     * in the load arrays.
     */
    private int dayBalancePenalty(SchedulingUnit unit, PlacementOption opt,
                                  Map<String, int[]> sectionDayLoads) {
        int worst = 0;
        int periodCount = opt.endOrder - opt.startOrder + 1;
        for (UUID sectionId : unit.sectionIds) {
            int[] base = sectionDayLoads.get(unit.semesterId + "|" + sectionId);
            int[] loads = base == null ? new int[6] : base.clone();
            loads[opt.day] += periodCount;
            int penalty = dayImbalancePenalty(loads);
            if (penalty > worst) worst = penalty;
        }
        return worst;
    }

    /** Sum of squared deviations of COURSE loads over days 1..5; 0 = perfectly balanced. */
    static int dayImbalancePenalty(int[] loads) {
        double total = 0;
        for (int d = WORKING_DAY_START; d <= WORKING_DAY_END; d++) total += loads[d];
        double avg = total / (WORKING_DAY_END - WORKING_DAY_START + 1);
        double ssd = 0;
        for (int d = WORKING_DAY_START; d <= WORKING_DAY_END; d++) {
            double dev = loads[d] - avg;
            ssd += dev * dev;
        }
        return (int) Math.round(ssd);
    }

    private boolean forwardCheck(List<SchedulingUnit> units,
                                  List<TimeSlot> slots, ConflictGrid grid,
                                  Map<Object, Set<Integer>> usedDaysByCourse,
                                  Map<SchedulingUnit, Integer> placedCounts) {
        for (SchedulingUnit u : units) {
            if (placedCounts.getOrDefault(u, 0) >= u.sessionsPerWeek) continue;

            int remainingSessions = u.sessionsPerWeek - placedCounts.getOrDefault(u, 0);
            Set<Integer> used = usedDaysByCourse.getOrDefault(courseKey(u), new HashSet<>());

            // Check 1: enough unused days for remaining sessions
            int availableDays = 0;
            for (int d = WORKING_DAY_START; d <= WORKING_DAY_END; d++) {
                if (!used.contains(d)) availableDays++;
            }
            if (availableDays < remainingSessions) {
                return false;
            }

            // Check 2: enough total UNIQUE free periods across available days
            // Use a bitmask to count unique free period indices (avoid overcounting overlapping blocks)
            int freePeriodMask = 0;
            for (int d = WORKING_DAY_START; d <= WORKING_DAY_END; d++) {
                if (used.contains(d)) continue;
                for (int startIdx = 0; startIdx + u.periodsPerSession <= slots.size(); startIdx++) {
                    if (!consecutiveSlots(slots, startIdx, u.periodsPerSession)) continue;
                    int startOrder = startIdx + 1;
                    int endOrder = startIdx + u.periodsPerSession;
                    if (grid.canPlace(u.staffIds, u.sectionIds, u.semesterId,
                            d, startOrder, endOrder, u.electiveGroup)) {
                        // Mark each period index in this block as free
                        for (int p = startOrder; p <= endOrder; p++) {
                            freePeriodMask |= (1 << (d * 10 + p)); // day*10 + period
                        }
                    }
                }
            }
            int uniqueFreePeriods = Integer.bitCount(freePeriodMask);
            int requiredPeriods = remainingSessions * u.periodsPerSession;
            if (uniqueFreePeriods < requiredPeriods) {
                return false;
            }

            // Check 3: at least one valid placement for next session (existing check)
            List<PlacementOption> opts = generateValidPlacements(u, used, slots, grid);
            if (opts.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private ClassSchedule buildSchedule(GenerationSession generation, SchedulingUnit unit,
                                         int day, int startIdx, List<TimeSlot> slots) {
        ClassSchedule schedule = new ClassSchedule();
        schedule.setGeneration(generation);
        schedule.setTeachingAssignment(unit.assignment);
        schedule.setTeachingGroup(unit.group);
        schedule.setDayOfWeek(day);
        schedule.setStartSlot(slots.get(startIdx));
        schedule.setEndSlot(slots.get(startIdx + unit.periodsPerSession - 1));
        schedule.setScheduleType(ScheduleType.COURSE);
        schedule.setMeetingType(unit.meetingType);
        schedule.setScheduleStatus(ScheduleStatus.PENDING);
        return schedule;
}

    private void diagnoseUnit(SchedulingUnit unit, List<TimeSlot> slots, Set<Integer> usedDays,
                              List<ClassSchedule> created, List<String> failureReport) {
        StringBuilder sb = new StringBuilder();
        sb.append(unit.label).append("\n");
        sb.append("  Required: ").append(unit.sessionsPerWeek)
                .append(" session(s) x ").append(unit.periodsPerSession).append(" period(s)\n");

        boolean anyValid = false;
        for (int day = WORKING_DAY_START; day <= WORKING_DAY_END; day++) {
            boolean dayUsed = usedDays.contains(day);
            for (int startIdx = 0; startIdx + unit.periodsPerSession <= slots.size(); startIdx++) {
                int startOrder = startIdx + 1;
                int endOrder = startIdx + unit.periodsPerSession;
                String window = DAY_NAMES[day] + " P" + startOrder + "-P" + endOrder;

                if (dayUsed) {
                    sb.append("  ").append(window)
                            .append(" -> USED_DAY (this unit already has a session on this day)\n");
                    continue;
                }

                if (!consecutiveSlots(slots, startIdx, unit.periodsPerSession)) {
                    sb.append("  ").append(window)
                            .append(" -> INVALID_CONSECUTIVE_SLOT (periods are not consecutive)\n");
                    continue;
                }

                List<String> reasons = new ArrayList<>();
                for (ClassSchedule s : created) {
                    if (s.getDayOfWeek() != day || s.getScheduleType() != ScheduleType.COURSE) continue;
                    int otherStart = s.getStartSlot().getDisplayOrder();
                    int otherEnd = s.getEndSlot().getDisplayOrder();
                    if (startOrder > otherEnd || endOrder < otherStart) continue;

                    boolean sameGroup = sameElectiveGroupUnit(unit, s);
                    if (!Collections.disjoint(unit.staffIds, ClassScheduleService.coveredStaff(s))) {
                        String cc = ClassScheduleService.courseCodeOf(s);
                        reasons.add(sameGroup
                                ? "ELECTIVE_SHARE_NOT_ALLOWED " + (cc != null ? cc : "unknown")
                                + " (P" + otherStart + "-P" + otherEnd
                                + ") â€”Ã¢â‚¬Â same lecturer; lecturer conflicts always win"
                                : "STAFF_CONFLICT " + (cc != null ? cc : "unknown")
                                + " (P" + otherStart + "-P" + otherEnd + ")");
                    }
                    // Section conflicts are scoped by semester â€”Ã¢â‚¬Â different-semester students
                    // sharing the same section label (e.g. Section A) don't actually conflict.
                    UUID sSemId = scheduleSemesterId(s);
                    if (unit.semesterId != null && unit.semesterId.equals(sSemId)
                            && !Collections.disjoint(unit.sectionIds, ClassScheduleService.coveredSections(s))) {
                        String cc = ClassScheduleService.courseCodeOf(s);
                        reasons.add(sameGroup
                                ? "ELECTIVE_SHARE_NOT_ALLOWED " + (cc != null ? cc : "unknown")
                                + " (P" + otherStart + "-P" + otherEnd
                                + ") â€”Ã¢â‚¬Â only IDENTICAL windows may be shared by one elective group"
                                : "SECTION_CONFLICT " + (cc != null ? cc : "unknown")
                                + " (P" + otherStart + "-P" + otherEnd + ")");
                    }
                }

                if (reasons.isEmpty()) {
                    anyValid = true;
                    sb.append("  ").append(window).append(" -> VALID\n");
                } else {
                    sb.append("  ").append(window).append(" -> ")
                            .append(String.join("; ", reasons)).append("\n");
                }
            }
        }

        if (!anyValid) {
            sb.append("  No valid consecutive ").append(unit.periodsPerSession)
                    .append("-period slot available on any day\n");
        }

        failureReport.add(sb.toString());
    }

    /** Extract semester UUID from a class schedule (for semester-scoped section checks). */
    private static UUID scheduleSemesterId(ClassSchedule s) {
        if (s.getTeachingAssignment() != null && s.getTeachingAssignment().getCourse() != null
                && s.getTeachingAssignment().getCourse().getSemester() != null) {
            return s.getTeachingAssignment().getCourse().getSemester().getSemesterId();
        }
        if (s.getTeachingGroup() != null && s.getTeachingGroup().getCourse() != null
                && s.getTeachingGroup().getCourse().getSemester() != null) {
            return s.getTeachingGroup().getCourse().getSemester().getSemesterId();
        }
        return null;
    }

    /** True when a placed schedule is a member of the same elective group as the unit. */
    private static boolean sameElectiveGroupUnit(SchedulingUnit unit, ClassSchedule s) {
        if (!unit.isElective() || s.getTeachingAssignment() == null) return false;
        Course c = s.getTeachingAssignment().getCourse();
        if (c == null || c.isRequired()) return false;
        Semester sem = c.getSemester();
        return sem != null && unit.semesterId != null && unit.semesterId.equals(sem.getSemesterId());
    }

    /** True when two schedules belong to the same elective group (is_required=false, same semester). */
    private static boolean sameElectiveGroup(ClassSchedule a, ClassSchedule b) {
        Course ca = courseOfSchedule(a);
        Course cb = courseOfSchedule(b);
        if (ca == null || cb == null || ca.isRequired() || cb.isRequired()) return false;
        Semester sa = ca.getSemester();
        Semester sb = cb.getSemester();
        return sa != null && sb != null && sa.getSemesterId().equals(sb.getSemesterId());
    }

    private static Course courseOfSchedule(ClassSchedule s) {
        if (s.getTeachingAssignment() != null) return s.getTeachingAssignment().getCourse();
        if (s.getTeachingGroup() != null) return s.getTeachingGroup().getCourse();
        return null;
    }

    // ========== CONSECUTIVE SLOT CHECK ==========

    private boolean consecutiveSlots(List<TimeSlot> slots, int startIdx, int perSession) {
        for (int i = startIdx; i < startIdx + perSession - 1; i++) {
            var end = slots.get(i).getEndTime();
            var nextStart = slots.get(i + 1).getStartTime();
            // The lunch break (12:00-13:00) falls between period 3 and 4.
            // A 2-period session may bridge it as a fallback when no fully
            // consecutive window exists (original delivered behaviour).
            if (!end.equals(nextStart)) {
                int curPeriod = slots.get(i).getPeriodNo();
                int nextPeriod = slots.get(i + 1).getPeriodNo();
                if (curPeriod == 3 && nextPeriod == 4) {
                    return true;
                }
                return false;
            }
        }
        return true;
    }

    // ========== SPECIAL PERIOD PLACEMENT ==========

    /**
     * Genuinely free (day, period) slots for ONE (semester, section) grid.
     * Occupancy requires BOTH the semester AND the section to match -
     * Semester 2/A must never block Semester 4/A.
     */
    private List<int[]> freeSlotsFor(UUID semesterId, UUID sectionId,
                                     List<TimeSlot> slots, List<ClassSchedule> created) {
        List<int[]> freeSlots = new ArrayList<>();
        for (int day = WORKING_DAY_START; day <= WORKING_DAY_END; day++) {
            final int dayOfWeek = day;
            for (TimeSlot slot : slots) {
                final int order = slot.getDisplayOrder();
                boolean occupiedByCourse = created.stream().anyMatch(s ->
                        s.getScheduleType() == ScheduleType.COURSE
                                && ClassScheduleService.coveredSections(s).contains(sectionId)
                                && sameSemesterOfId(s, semesterId)
                                && s.getDayOfWeek() != null && s.getDayOfWeek() == dayOfWeek
                                && s.getStartSlot().getDisplayOrder() <= order
                                && s.getEndSlot().getDisplayOrder() >= order);
                if (!occupiedByCourse) freeSlots.add(new int[]{day, order});
            }
        }
        return freeSlots;
    }

    /**
     * Optional fillers for ONE (semester, section) grid: EVERY genuinely free
     * (day, period) ends up with exactly one filler, alternating LMS /
     * ASSIGNMENT so the two counts never differ by more than 1 (LMS first).
     *
     * Fillers already sitting on this grid's free slots (placed while
     * processing earlier grids) count toward the balance; only still-empty
     * slots receive new rows, so a slot shared by several grids is stored once
     * (schema keeps specials section-less via
     * chk_class_schedules_teaching_assignment).
     * Best-effort safety: fillers never displace, move, or fail courses; the
     * curriculum capacity calculation never sees them.
     */
    private void placeSectionSpecial(GenerationSession generation,
                                     UUID semesterId, UUID sectionId,
                                     List<int[]> freeSlots,
                                     List<TimeSlot> slots,
                                     List<ClassSchedule> created, Set<String> placedKeys) {

        // 2. Existing fillers on those slots count toward this grid's balance.
        int lms = 0;
        int assignment = 0;
        List<int[]> emptySlots = new ArrayList<>();
        for (int[] fs : freeSlots) {
            ScheduleType existing = fillerTypeAt(created, fs[0], fs[1]);
            if (existing == ScheduleType.LMS) lms++;
            else if (existing == ScheduleType.ASSIGNMENT) assignment++;
            else emptySlots.add(fs);
        }

        // 3. Fill the still-empty slots driving this grid toward the exact
        //    ceil/floor split (LMS = ceil(N/2)) counting fillers already
        //    present; every free slot ends up filled.
        int wantL = (int) Math.ceil(freeSlots.size() / 2.0);
        int wantA = freeSlots.size() - wantL;
        for (int[] fs : emptySlots) {
            ScheduleType type;
            if (lms < wantL && assignment < wantA) {
                type = lms <= assignment ? ScheduleType.LMS : ScheduleType.ASSIGNMENT;
            } else if (lms < wantL) {
                type = ScheduleType.LMS;
            } else if (assignment < wantA) {
                type = ScheduleType.ASSIGNMENT;
            } else {
                // inherited prefix already satisfies the split; keep filling
                // remaining slots alternating so nothing stays unused.
                type = lms <= assignment ? ScheduleType.LMS : ScheduleType.ASSIGNMENT;
            }
            String key = type + "#" + semesterId + "#" + sectionId + "#" + fs[0] + "#" + fs[1];
            if (!placedKeys.add(key)) continue;
            if (type == ScheduleType.LMS) lms++;
            else assignment++;
            for (TimeSlot slot : slots) {
                if (slot.getDisplayOrder() != fs[1]) continue;
                ClassSchedule schedule = new ClassSchedule();
                schedule.setGeneration(generation);
                schedule.setDayOfWeek(fs[0]);
                schedule.setStartSlot(slot);
                schedule.setEndSlot(slot);
                schedule.setScheduleType(type);
                schedule.setScheduleStatus(ScheduleStatus.PENDING);
                created.add(schedule);
                break;
            }
        }
    }

    /** Semester match for occupancy checks (specials carry no semester of their own). */
    private static boolean sameSemesterOfId(ClassSchedule s, UUID semesterId) {
        Semester sem = semesterOf(s);
        return sem != null && sem.getSemesterId().equals(semesterId);
    }

    /** Type of the special row occupying (day, period), if any. */
    private static ScheduleType fillerTypeAt(List<ClassSchedule> created, int day, int period) {
        for (ClassSchedule s : created) {
            if (s.getScheduleType() == ScheduleType.COURSE || s.getDayOfWeek() == null) continue;
            if (s.getDayOfWeek() == day
                    && s.getStartSlot().getDisplayOrder() <= period
                    && s.getEndSlot().getDisplayOrder() >= period) {
                return s.getScheduleType();
            }
        }
        return null;
    }

    /** Semester number lookup from generation-scope UUID key. */
    private int semesterNumberOf(UUID semesterId) {
        Semester sem = semesterRepository.findById(semesterId).orElse(null);
        return sem != null ? sem.getSemesterNo() : 0;
    }
    // ========== DATA VALIDATION ==========

    /**
     * Extract the curriculum semester from course_code.
     * Course codes follow pattern: PREFIX-NUMBER (e.g., CST-1102, CS-3215, CT-2234, E-1101, M-1201, P-1101).
     * The first two digits of the numeric portion determine the semester:
     * 11xx -> 1, 12xx -> 2, 21xx -> 3, 22xx -> 4, 31xx -> 5, 32xx -> 6, 41xx -> 7, 42xx -> 8.
     */
    private Integer extractCurriculumSemester(String courseCode) {
        if (courseCode == null) return null;
        int dashIdx = courseCode.lastIndexOf('-');
        if (dashIdx < 0 || dashIdx + 3 >= courseCode.length()) return null;
        String numeric = courseCode.substring(dashIdx + 1);
        if (numeric.length() < 2) return null;
        char c0 = numeric.charAt(0);
        char c1 = numeric.charAt(1);
        if (!Character.isDigit(c0) || !Character.isDigit(c1)) return null;
        int d0 = c0 - '0';
        int d1 = c1 - '0';
        if (d0 < 1 || d0 > 4 || d1 < 1 || d1 > 2) return null;
        return (d0 - 1) * 2 + d1;
    }

    /**
     * Validate that a course's course_code matches its assigned semester_id in the database.
     */
    private String validateCourseSemesterCode(Course course) {
        Integer expectedSem = extractCurriculumSemester(course.getCourseCode());
        if (expectedSem == null) return null;
        Semester sem = course.getSemester();
        if (sem != null && sem.getSemesterNo() != expectedSem) {
            return "course code " + course.getCourseCode() + " implies Semester " + expectedSem
                    + " but course is assigned to Semester " + sem.getSemesterNo();
        }
        return null;
    }

    /**
     * Validate that a course's curriculum semester matches the generation scope.
     */
    private String validateCourseGenerationScope(Course course, Map<UUID, Set<UUID>> scope) {
        Integer expectedSem = extractCurriculumSemester(course.getCourseCode());
        if (expectedSem == null) return null;
        Semester sem = course.getSemester();
        if (sem == null) return null;
        if (!scope.containsKey(sem.getSemesterId())) {
            return "course code " + course.getCourseCode() + " belongs to Semester " + expectedSem
                    + " but generation scope does not include Semester " + sem.getSemesterNo();
        }
        return null;
    }

    private String validateCsCtSeparation(TeachingAssignment a, Map<UUID, Set<UUID>> scope) {
        String code = a.getCourse().getCourseCode();
        if (code == null) return null;
        Semester sem = a.getCourse().getSemester();
        if (sem == null || sem.getSemesterNo() < 3) return null;
        String sectionName = a.getSection().getSectionName();
        boolean isCT = code.startsWith("CT");
        boolean isCS = code.startsWith("CS") && !code.startsWith("CST");
        if (isCT && !"CT".equals(sectionName)) {
            return "CT course " + code + " must be assigned to CT section, not " + sectionName;
        }
        if (isCS && "CT".equals(sectionName)) {
            return "CS course " + code + " must not be assigned to CT section";
        }
        return null;
    }

    private String validateLecturerOwnership(TeachingAssignment a) {
        var staffUnit = a.getStaff().getUnit();
        var courseUnit = a.getCourse().getUnit();
        if (staffUnit != null && courseUnit != null
                && !staffUnit.getUnitId().equals(courseUnit.getUnitId())) {
            return "lecturer " + a.getStaff().getStaffName()
                    + " belongs to " + staffUnit.getUnitName()
                    + " but course " + a.getCourse().getCourseCode()
                    + " belongs to " + courseUnit.getUnitName();
        }
        return null;
    }

    // ========== PUBLISH ==========

    public GenerationSessionResponse publish(UUID generationId) {
        hodAccessService.requireHod();
        lobbyAccessService.requireSharedDraftAccess(generationId);
        GenerationSession generation = findGeneration(generationId);
        if (generation.getStatus() != GenerationStatus.COMPLETED) {
            throw new BusinessRuleException("Only a completed generation can be published");
        }
        List<ClassSchedule> schedules = scheduleRepository.findByGeneration_GenerationIdWithDetails(generationId);
        if (schedules.isEmpty()) {
            throw new BusinessRuleException("Cannot publish an empty timetable");
        }
        validateConflictsForPublish(schedules);
        validateCompletenessForPublish(generation, schedules);
        // Publishing a newer timetable replaces the term's current published one:
        // demote it back to a completed draft so it remains viewable in history.
        generationRepository.findFirstByTerm_TermIdAndStatusOrderByCreatedAtDesc(
                        generation.getTerm().getTermId(), GenerationStatus.PUBLISHED)
                .ifPresent(published -> {
                    if (!published.getGenerationId().equals(generationId)) {
                        published.setStatus(GenerationStatus.COMPLETED);
                        published.setPublishedAt(null);
                        generationRepository.save(published);
                        List<ClassSchedule> oldSchedules =
                                scheduleRepository.findByGeneration_GenerationId(published.getGenerationId());
                        for (ClassSchedule schedule : oldSchedules) {
                            schedule.setScheduleStatus(ScheduleStatus.PENDING);
                        }
                        scheduleRepository.saveAll(oldSchedules);
                    }
                });
        generation.setStatus(GenerationStatus.PUBLISHED);
        generation.setPublishedAt(Instant.now());
        for (ClassSchedule schedule : schedules) {
            schedule.setScheduleStatus(ScheduleStatus.CONFIRMED);
        }
        scheduleRepository.saveAll(schedules);
        GenerationSessionResponse response = toResponse(generationRepository.save(generation));

        lobbyRepository.findByGeneration_GenerationId(generationId).ifPresent(lobby -> {
            lobby.setStatus(LobbyStatus.COMPLETED);
            lobbyRepository.save(lobby);
            realtimeEventService.publish(lobby.getLobbyId(),
                    TimetableRealtimeEventService.TIMETABLE_PUBLISHED,
                    Map.of("generationId", generationId, "lobbyId", lobby.getLobbyId()));
        });
        return response;
    }

    /**
     * Broadcasts a live drag/drop gesture to every connected lobby member.
     *
     * <p>Only the HOD who currently holds the editing lock can drag, so this is
     * always a single-sender stream; every other member's browser renders the
     * remote dragging state until the {@code end} event arrives.
     */
    public void publishDragStatus(UUID generationId, DragStatusRequest request) {
        Staff staff = hodAccessService.requireHod();
        lobbyAccessService.requireSharedDraftAccess(generationId);
        String type;
        if (request == null || request.action() == null) {
            throw new BusinessRuleException("Missing drag action");
        }
        switch (request.action()) {
            case "start" -> type = TimetableRealtimeEventService.DRAG_STARTED;
            case "move" -> type = TimetableRealtimeEventService.DRAG_MOVED;
            case "end" -> type = TimetableRealtimeEventService.DRAG_ENDED;
            default -> throw new BusinessRuleException("Unknown drag action: " + request.action());
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("generationId", generationId);
        payload.put("scheduleId", request.scheduleId());
        payload.put("staffId", staff.getStaffId());
        payload.put("staffName", staff.getStaffName());
        payload.put("day", request.day());
        payload.put("period", request.period());
        payload.put("span", request.span());
        realtimeEventService.publishForGeneration(generationId, type, payload);
    }

    /**
     * Broadcasts a live undo/redo swap animation to every connected HOD browser.
     *
     * <p>The lock holder's browser publishes the two cells being exchanged so
     * every other browser renders the same animated swap overlay while the save
     * request is in flight. Purely presentational — the database remains
     * authoritative and clients re-fetch on reconnect.
     */
    public void publishSwapAnimation(UUID generationId, SwapAnimRequest request) {
        if (request == null || request.day() == null || request.period() == null
                || request.dayTo() == null || request.periodTo() == null) {
            throw new BusinessRuleException("Missing swap animation cells");
        }
        Staff staff = hodAccessService.requireHod();
        lobbyAccessService.requireSharedDraftAccess(generationId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("generationId", generationId);
        payload.put("scheduleId", request.scheduleId());
        payload.put("staffId", staff.getStaffId());
        payload.put("staffName", staff.getStaffName());
        payload.put("day", request.day());
        payload.put("period", request.period());
        payload.put("dayTo", request.dayTo());
        payload.put("periodTo", request.periodTo());
        realtimeEventService.publishForGeneration(generationId, TimetableRealtimeEventService.SWAP_ANIMATED, payload);
    }

    /**
     * Broadcasts a lobby-scoped {@code TIMETABLE_EDIT_STARTED} event so every
     * HOD currently connected to the generation's lobby navigates to the same
     * dedicated timetable edit workspace. The client is the authoritative
     * destination; this event only carries identity so all browsers converge on
     * the same generation.
     */
    public void notifyEditStarted(UUID generationId) {
        lobbyRepository.findByGeneration_GenerationId(generationId).ifPresent(lobby -> {
            realtimeEventService.publish(lobby.getLobbyId(),
                    TimetableRealtimeEventService.EDIT_STARTED,
                    Map.of("generationId", generationId, "lobbyId", lobby.getLobbyId()));
        });
    }

    public GenerationSessionResponse cancel(UUID generationId) {
        hodAccessService.requireHod();
        lobbyAccessService.requireSharedDraftAccess(generationId);
        GenerationSession generation = findGeneration(generationId);
        if (generation.getStatus() == GenerationStatus.PUBLISHED) {
            throw new BusinessRuleException("A published timetable cannot be cancelled");
        }
        List<ClassSchedule> schedules = scheduleRepository.findByGeneration_GenerationId(generationId);
        scheduleRepository.deleteAll(schedules);
        generation.setStatus(GenerationStatus.FAILED);
        generation.setFinishedAt(Instant.now());
        GenerationSessionResponse response = toResponse(generationRepository.save(generation));
        realtimeEventService.publishForGeneration(generationId,
                TimetableRealtimeEventService.TIMETABLE_DELETED,
                Map.of("generationId", generationId));

        // Cancelling the shared draft also deactivates the lobby that owns it:
        // an "active lobby + active generation" pair is what makes clients
        // auto-redirect into this workspace, so both must be turned off together
        // or every HOD would be bounced straight back into a cancelled draft.
        lobbyRepository.findByGeneration_GenerationId(generationId)
                .filter(l -> l.getStatus() != LobbyStatus.CANCELLED)
                .ifPresent(lobby -> {
                    lobby.setStatus(LobbyStatus.CANCELLED);
                    lobbyRepository.save(lobby);
                    realtimeEventService.publish(lobby.getLobbyId(),
                            TimetableRealtimeEventService.LOBBY_CANCELLED,
                            Map.of("lobbyId", lobby.getLobbyId(), "generationId", generationId));
                });
        return response;
    }

    public void delete(UUID generationId) {
        hodAccessService.requireHod();
        lobbyAccessService.requireSharedDraftAccess(generationId);
        GenerationSession generation = findGeneration(generationId);
        if (attendanceRepository.countBySession_Schedule_Generation_GenerationId(generationId) > 0) {
            throw new BusinessRuleException(
                    "This timetable already has attendance records; deleting it would erase historical "
                            + "attendance. Keep it or cancel it instead.");
        }
        UUID lobbyId = lobbyRepository.findByGeneration_GenerationId(generationId)
                .map(lobby -> lobby.getLobbyId()).orElse(null);
        scheduleRepository.deleteAll(scheduleRepository.findByGeneration_GenerationId(generationId));
        scheduleRepository.flush();
        generationRepository.delete(generation);
        if (lobbyId != null) {
            realtimeEventService.publish(lobbyId, TimetableRealtimeEventService.TIMETABLE_DELETED,
                    Map.of("generationId", generationId, "lobbyId", lobbyId));
        }
    }

    public List<ScheduleResponse> getSchedules(UUID generationId) {
        GenerationSession session = findGeneration(generationId);
        if (session.getStatus() != GenerationStatus.PUBLISHED) {
            if (hodAccessService.currentHod().isEmpty()) {
                throw new BusinessRuleException("You do not have access to this timetable");
            }
            // Any HOD may review a COMPLETED draft (the "View Timetable" flow);
            // only in-progress drafts stay restricted to the lobby.
            if (session.getStatus() != GenerationStatus.COMPLETED
                    && !lobbyAccessService.canAccessSharedDraft(generationId)) {
                throw new BusinessRuleException(
                        "Only the lobby leader or joined lobby members can access this shared draft");
            }
        }
        return scheduleRepository.findByGeneration_GenerationIdWithDetails(generationId).stream()
                .sorted(Comparator.comparing(ClassSchedule::getDayOfWeek)
                        .thenComparing(s -> s.getStartSlot().getDisplayOrder()))
                .map(ClassScheduleService::toResponse).toList();
    }

    // ========== PUBLISH CONFLICT REVALIDATION ==========

    private String describeConflictSlot(ClassSchedule s) {
        TimeSlot start = s.getStartSlot();
        String time = start != null && s.getEndSlot() != null
                ? start.getStartTime() + " - " + s.getEndSlot().getEndTime() : "?";
        int period = start != null ? start.getPeriodNo() : 0;
        return DAY_NAMES[s.getDayOfWeek()] + " P" + period + " (" + time + ")";
    }

    private void validateConflictsForPublish(List<ClassSchedule> schedules) {
        List<String> conflicts = new ArrayList<>();
        for (int day = WORKING_DAY_START; day <= WORKING_DAY_END; day++) {
            final int dayOfWeek = day;
            List<ClassSchedule> daySchedules = schedules.stream()
                    .filter(s -> s.getDayOfWeek() != null && s.getDayOfWeek() == dayOfWeek)
                    .filter(s -> s.getScheduleStatus() != ScheduleStatus.CANCELLED)
                    .toList();
            for (int i = 0; i < daySchedules.size(); i++) {
                ClassSchedule a = daySchedules.get(i);
                for (int j = i + 1; j < daySchedules.size(); j++) {
                    ClassSchedule b = daySchedules.get(j);
                    boolean specialPair = a.getScheduleType() != ScheduleType.COURSE
                            || b.getScheduleType() != ScheduleType.COURSE;
                    if (!specialPair) {
                        if (b.getScheduleType() == ScheduleType.COURSE
                                && a.getTeachingGroup() != null && b.getTeachingGroup() != null
                                && a.getTeachingGroup().getGroupId().equals(b.getTeachingGroup().getGroupId())) {
                            conflicts.add(describeConflictSlot(a) + " â€”Ã¢â‚¬Â " + ClassScheduleService.courseCodeOf(a)
                                    + " is scheduled more than once on " + DAY_NAMES[day] + " for the same section");
                            continue;
                        }
                        if (b.getScheduleType() == ScheduleType.COURSE
                                && ClassScheduleService.courseCodeOf(a) != null
                                && ClassScheduleService.courseCodeOf(a).equals(ClassScheduleService.courseCodeOf(b))
                                && !Collections.disjoint(ClassScheduleService.coveredSections(a),
                                        ClassScheduleService.coveredSections(b))) {
                            conflicts.add(describeConflictSlot(a) + " â€”Ã¢â‚¬Â " + ClassScheduleService.courseCodeOf(a)
                                    + " is scheduled more than once on " + DAY_NAMES[day] + " for the same section");
                            continue;
                        }
                    }
                    if (!overlapsSlots(a, b)) continue;
                    boolean conflict;
                    String reason;
                    if (specialPair) {
                        // Specials are optional per-section fillers: they clash only when
                        // the shared slot belongs to a section the special itself covers.
                        // Two different sections' fillers may legitimately share a period.
                        boolean sectionShared = !Collections.disjoint(
                                ClassScheduleService.coveredSections(a),
                                ClassScheduleService.coveredSections(b));
                        conflict = sectionShared;
                        reason = sectionShared
                                ? "a special period (LMS/ASSIGNMENT) cannot share this slot with a course"
                                : null;
                    } else if (!Collections.disjoint(ClassScheduleService.coveredStaff(a),
                            ClassScheduleService.coveredStaff(b))) {
                        // Check same-section elective alternatives first: when both
                        // schedules are in the SAME section and belong to the SAME
                        // elective group, the same lecturer may teach them because
                        // students choose one alternative.
                        Set<UUID> secA = ClassScheduleService.coveredSections(a);
                        Set<UUID> secB = ClassScheduleService.coveredSections(b);
                        boolean sameSectionElective = sameElectiveGroup(a, b)
                                && secA.equals(secB);
                        conflict = !sameSectionElective;
                        reason = sameSectionElective ? null
                                : "the same lecturer is double-booked";
                    } else if (Collections.disjoint(ClassScheduleService.coveredSections(a),
                            ClassScheduleService.coveredSections(b))) {
                        conflict = false;
                        reason = null;
                    } else if (!sameSemester(a, b)) {
                        // Sections are shared rows across semesters: different-semester
                        // cohorts may legitimately co-exist in the same slot (the solver
                        // is semester-scoped); only same-semester co-existence conflicts.
                        conflict = false;
                        reason = null;
                    } else {
                        // Overlapping sections are fine only for identical-window
                        // co-location of the same elective group.
                        conflict = !sameElectiveGroup(a, b);
                        reason = conflict ? "the same section is double-booked" : null;
                    }
                    if (conflict) {
                        conflicts.add(describeConflictSlot(a) + " â€”Ã¢â‚¬Â " + scheduleLabel(a)
                                + " conflicts with " + scheduleLabel(b) + " (" + reason + ")");
                    }
                }
            }
        }
        if (!conflicts.isEmpty()) {
            throw new TimetableConflictException(conflicts);
        }
    }

    private boolean overlapsSlots(ClassSchedule a, ClassSchedule b) {
        return a.getStartSlot().getDisplayOrder() <= b.getEndSlot().getDisplayOrder()
                && b.getStartSlot().getDisplayOrder() <= a.getEndSlot().getDisplayOrder();
    }

    /** Semester of the course behind a schedule (assignment or group). */
    private static Semester semesterOf(ClassSchedule s) {
        if (s.getTeachingAssignment() != null) {
            return s.getTeachingAssignment().getCourse().getSemester();
        }
        if (s.getTeachingGroup() != null) {
            return s.getTeachingGroup().getCourse().getSemester();
        }
        return null;
    }

    /** True when both schedules belong to the same semester (or semester is unknown). */
    private static boolean sameSemester(ClassSchedule a, ClassSchedule b) {
        Semester sa = semesterOf(a);
        Semester sb = semesterOf(b);
        if (sa == null || sb == null) return true;
        return sa.getSemesterId().equals(sb.getSemesterId());
    }

    private String scheduleLabel(ClassSchedule s) {
        if (s.getScheduleType() == ScheduleType.COURSE) {
            if (s.getTeachingAssignment() != null) {
                return s.getTeachingAssignment().getCourse().getCourseCode() + " ("
                        + s.getTeachingAssignment().getStaff().getStaffName() + ", "
                        + s.getTeachingAssignment().getSection().getSectionName() + ")";
            }
            if (s.getTeachingGroup() != null) {
                List<String> names = new ArrayList<>();
                for (TeachingAssignmentGroupMember m : s.getTeachingGroup().getMembers()) {
                    names.add(m.getAssignment().getSection().getSectionName());
                }
                names.sort(Comparator.naturalOrder());
                return s.getTeachingGroup().getCourse().getCourseCode()
                        + " (" + String.join(" + ", names) + ")";
            }
        }
        return s.getScheduleType() + " block";
    }

    // ========== PUBLISH COMPLETENESS ==========

    private void validateCompletenessForPublish(GenerationSession generation,
                                                List<ClassSchedule> allSchedules) {
        List<String> failures = new ArrayList<>();

        for (ClassSchedule s : allSchedules) {
            if (s.getScheduleType() != ScheduleType.COURSE
                    && s.getScheduleType() != ScheduleType.LMS
                    && s.getScheduleType() != ScheduleType.ASSIGNMENT) {
                failures.add("Invalid schedule type " + s.getScheduleType()
                        + " (" + scheduleLabel(s) + ")");
            }
        }

        for (ClassSchedule s : allSchedules) {
            if (s.getDayOfWeek() == null || s.getDayOfWeek() < WORKING_DAY_START
                    || s.getDayOfWeek() > WORKING_DAY_END) {
                failures.add(scheduleLabel(s) + " is scheduled outside Monday-Friday");
            }
            TimeSlot start = s.getStartSlot();
            TimeSlot end = s.getEndSlot();
            if (start == null || end == null) {
                failures.add(scheduleLabel(s) + " has no valid time slots");
                continue;
            }
            int startOrder = start.getDisplayOrder();
            int endOrder = end.getDisplayOrder();
            if (startOrder < 1 || startOrder > 6 || endOrder < 1 || endOrder > 6) {
                failures.add(scheduleLabel(s) + " uses a slot outside the 6 daily periods");
            }
            if (startOrder > endOrder) {
                failures.add(scheduleLabel(s) + " has a start slot after its end slot");
            }
        }

        List<ClassSchedule> courseSchedules = allSchedules.stream()
                .filter(s -> s.getScheduleType() == ScheduleType.COURSE
                        && s.getScheduleStatus() != ScheduleStatus.CANCELLED)
                .toList();

        Map<UUID, List<TeachingAssignmentGroupMember>> membersByGroup = new LinkedHashMap<>();
        for (TeachingAssignmentGroupMember m : groupMemberRepository
                .findWithDetailsByTermId(generation.getTerm().getTermId())) {
            if (m.getAssignment().getAssignmentStatus() == AssignmentStatus.CANCELLED) continue;
            membersByGroup.computeIfAbsent(m.getGroup().getGroupId(), k -> new ArrayList<>()).add(m);
        }
        Set<UUID> groupedAssignmentIds = new HashSet<>();
        membersByGroup.values().forEach(list -> list.forEach(
                m -> groupedAssignmentIds.add(m.getAssignment().getAssignmentId())));

        PersistedScope persisted = parseScope(generation.getScopeJson());
        Map<UUID, Set<UUID>> scopeMap = toScopeMap(persisted);
        boolean useScope = persisted != null;

        if (useScope) {
            for (ClassSchedule s : courseSchedules) {
                if (s.getTeachingGroup() != null) {
                    List<TeachingAssignmentGroupMember> members =
                            membersByGroup.get(s.getTeachingGroup().getGroupId());
                    if (members == null) {
                        failures.add(scheduleLabel(s) + " references an unknown combined group");
                        continue;
                    }
                    for (TeachingAssignmentGroupMember m : members) {
                        if (!inScope(m.getAssignment(), scopeMap)) {
                            failures.add("Cross-semester course in " + scheduleLabel(s)
                                    + ": " + m.getAssignment().getCourse().getCourseCode()
                                    + " is not part of the selected scope");
                        }
                    }
                } else if (s.getTeachingAssignment() != null
                        && !inScope(s.getTeachingAssignment(), scopeMap)) {
                    failures.add("Cross-semester course in " + scheduleLabel(s)
                            + ": " + s.getTeachingAssignment().getCourse().getCourseCode()
                            + " is not part of the selected scope");
                }
            }
        }

        Map<UUID, List<ClassSchedule>> byAssignment = new HashMap<>();
        Map<UUID, List<ClassSchedule>> byGroup = new HashMap<>();
        for (ClassSchedule s : courseSchedules) {
            if (s.getTeachingAssignment() != null) {
                byAssignment.computeIfAbsent(s.getTeachingAssignment().getAssignmentId(),
                        k -> new ArrayList<>()).add(s);
            } else if (s.getTeachingGroup() != null) {
                byGroup.computeIfAbsent(s.getTeachingGroup().getGroupId(),
                        k -> new ArrayList<>()).add(s);
            }
        }

        for (ClassSchedule s : courseSchedules) {
            if (s.getTeachingAssignment() != null
                    && groupedAssignmentIds.contains(s.getTeachingAssignment().getAssignmentId())) {
                failures.add(scheduleLabel(s)
                        + ": a combined-class member assignment is scheduled individually");
            }
        }

        List<TeachingAssignment> expectedAssignments;
        if (useScope) {
            expectedAssignments = assignmentRepository
                    .findWithDetailsByTermId(generation.getTerm().getTermId()).stream()
                    .filter(a -> a.getAssignmentStatus() != AssignmentStatus.CANCELLED)
                    .filter(a -> inScope(a, scopeMap))
                    .filter(a -> !groupedAssignmentIds.contains(a.getAssignmentId()))
                    .toList();
        } else {
            expectedAssignments = assignmentRepository
                    .findWithDetailsByTermId(generation.getTerm().getTermId()).stream()
                    .filter(a -> byAssignment.containsKey(a.getAssignmentId()))
                    .filter(a -> !groupedAssignmentIds.contains(a.getAssignmentId()))
                    .toList();
        }
        Map<UUID, List<CourseMeetingRequirement>> cmrsByCourse = batchLoadCmrs(allSchedules);

        for (TeachingAssignment assignment : expectedAssignments) {
            validateUnitPeriods(failures, assignment.getCourse(), describe(assignment),
                    byAssignment.get(assignment.getAssignmentId()), cmrsByCourse);
        }

        for (Map.Entry<UUID, List<TeachingAssignmentGroupMember>> entry : membersByGroup.entrySet()) {
            List<TeachingAssignmentGroupMember> members = entry.getValue();
            TeachingAssignmentGroup group = members.get(0).getGroup();
            Course course = group.getCourse();
            Semester sem = course.getSemester();
            List<String> sectionNames = members.stream()
                    .map(m -> m.getAssignment().getSection().getSectionName())
                    .sorted().toList();

            boolean inScopeFlag;
            if (!useScope) {
                inScopeFlag = byGroup.containsKey(group.getGroupId());
            } else if (scopeMap.isEmpty()) {
                inScopeFlag = true;
            } else if (sem == null || !scopeMap.containsKey(sem.getSemesterId())) {
                inScopeFlag = false;
            } else {
                Set<UUID> scopeSections = scopeMap.get(sem.getSemesterId());
                inScopeFlag = scopeSections == null || members.stream().allMatch(m ->
                        scopeSections.contains(m.getAssignment().getSection().getSectionId()));
            }
            if (!inScopeFlag) continue;

            String label = "Semester " + (sem != null ? sem.getSemesterNo() : "?")
                    + " / Section " + String.join(" + ", sectionNames)
                    + " / " + course.getCourseCode();
            validateUnitPeriods(failures, course, label, byGroup.get(group.getGroupId()), cmrsByCourse);
        }

        validateCurriculumCoverage(failures, generation, useScope, scopeMap, allSchedules);

        if (!failures.isEmpty()) {
            throw new BusinessRuleException(
                    "Timetable cannot be published:\n" + String.join("\n", failures));
        }
    }

    /**
     * Curriculum completeness: every required course whose eligibility covers a
     * selected section's cohort must have a scheduled delivery reaching that
     * section (directly or through a combined group). This is independent of
     * how the delivery is represented - it validates the curriculum contract,
     * not merely the assignment rows.
     */
    private void validateCurriculumCoverage(List<String> failures,
                                            GenerationSession generation,
                                            boolean useScope,
                                            Map<UUID, Set<UUID>> scopeMap,
                                            List<ClassSchedule> schedules) {
        if (!useScope || scopeMap.isEmpty()) {
            return;
        }
        Map<UUID, Set<UUID>> coveredByCourse = new HashMap<>();
        for (ClassSchedule schedule : schedules) {
            if (schedule.getScheduleType() != ScheduleType.COURSE) continue;
            Course course = courseOf(schedule);
            if (course == null || course.getSemester() == null) continue;
            Set<UUID> scopeSections = scopeMap.get(course.getSemester().getSemesterId());
            if (scopeSections == null) continue;
            for (UUID sectionId : ClassScheduleService.coveredSections(schedule)) {
                if (scopeSections.contains(sectionId)) {
                    coveredByCourse.computeIfAbsent(course.getCourseId(), k -> new HashSet<>())
                            .add(sectionId);
                }
            }
        }
        for (Map.Entry<UUID, Set<UUID>> scopeEntry : scopeMap.entrySet()) {
            UUID semesterId = scopeEntry.getKey();
            Set<UUID> sectionIds = scopeEntry.getValue();
            if (sectionIds == null) {
                // Whole-semester generation: coverage expectations follow the
                // existing deliveries only.
                continue;
            }
            List<Course> semesterCourses = courseRepository.findBySemester_SemesterId(semesterId);
            for (UUID sectionId : sectionIds) {
                Section section = sectionRepository.findById(sectionId).orElse(null);
                if (section == null) continue;
                if (!curriculumEligibilityService.isDedicatedCohortSection(section)) {
                    // Mixed-major sections are HOD-managed; the curriculum
                    // contract is enforced on dedicated-cohort sections.
                    continue;
                }
                for (Course course : semesterCourses) {
                    if (!course.isRequired()) continue;
                    String ownerCode = curriculumEligibilityService.ownerMajorCode(course);
                    if (!curriculumEligibilityService.isStructurallyEligible(course, section,
                course.getSemester() != null ? course.getSemester().getSemesterNo() : 0)) {
                        continue;
                    }
                    if (!coveredByCourse.getOrDefault(course.getCourseId(), Set.of()).contains(sectionId)) {
                        failures.add("Curriculum gap: Semester "
                                + (course.getSemester() != null ? course.getSemester().getSemesterNo() : "?")
                                + " / Section " + section.getSectionName()
                                + " / " + course.getCourseCode()
                                + " (" + ownerCode + ") is required curriculum but has no scheduled delivery");
                    }
                }
            }
        }
    }

    private Course courseOf(ClassSchedule schedule) {
        if (schedule.getTeachingAssignment() != null) {
            return schedule.getTeachingAssignment().getCourse();
        }
        if (schedule.getTeachingGroup() != null) {
            return schedule.getTeachingGroup().getCourse();
        }
        return null;
    }

    /**
     * Curriculum-to-delivery binding. For every explicitly selected section,
     * each required course whose curriculum eligibility covers the section's
     * cohort must have a delivery representation reaching that section (direct
     * assignment or combined group). Missing deliveries are closed by creating
     * a section-specific {@link TeachingAssignment} - the same entity the HOD
     * creates manually - so the course flows into buildSchedulingUnits() and
     * produces ClassSchedule rows like any other unit.
     *
     * <p>Electives (is_required=false) are never auto-bound: elective choice
     * remains a deliberate HOD decision. Cancelled assignments are never
     * resurrected: an explicit cancellation is a data decision, not a gap.
     */
    private List<TeachingAssignment> ensureCurriculumDeliveries(AcademicTerm term,
                                                                Map<UUID, Set<UUID>> scope,
                                                                List<TeachingAssignment> assignments,
                                                                Map<UUID, List<TeachingAssignmentGroupMember>> membersByGroup) {
        if (scope.isEmpty()) {
            return List.of();
        }
        // Section names double as dedicated-cohort identifiers (e.g. a section
        // literally named "CT"): used below to keep an owner-major's
        // specialised deliveries inside its own dedicated section instead of
        // cascading them into every mixed cohort as well.
        Set<String> dedicatedSectionNames = new HashSet<>();
        for (Section s : sectionRepository.findAll()) {
            if (s.getSectionName() != null) {
                dedicatedSectionNames.add(s.getSectionName().trim().toUpperCase());
            }
        }
        Map<UUID, Set<UUID>> coveredByCourse = new HashMap<>();
        for (TeachingAssignment a : assignments) {
            coveredByCourse.computeIfAbsent(a.getCourse().getCourseId(), k -> new HashSet<>())
                    .add(a.getSection().getSectionId());
        }
        for (List<TeachingAssignmentGroupMember> members : membersByGroup.values()) {
            Course course = members.get(0).getGroup().getCourse();
            Set<UUID> sections = coveredByCourse.computeIfAbsent(course.getCourseId(), k -> new HashSet<>());
            for (TeachingAssignmentGroupMember m : members) {
                sections.add(m.getAssignment().getSection().getSectionId());
            }
        }
        List<TeachingAssignment> created = new ArrayList<>();
        for (Map.Entry<UUID, Set<UUID>> scopeEntry : scope.entrySet()) {
            UUID semesterId = scopeEntry.getKey();
            Set<UUID> sectionIds = scopeEntry.getValue();
            if (sectionIds == null) {
                // Whole-semester generation: no explicit section selection, so
                // delivery binding stays a manual decision.
                continue;
            }
            for (UUID sectionId : sectionIds) {
                Section section = sectionRepository.findById(sectionId).orElse(null);
                if (section == null) continue;
                boolean dedicated = curriculumEligibilityService.isDedicatedCohortSection(section);
                for (Course course : courseRepository.findBySemester_SemesterId(semesterId)) {
                    if (!course.isRequired()) continue;
                    // Curriculum membership is decided by courses.semester_id +
                    // cohort/major eligibility - NEVER by existing
                    // TeachingAssignment rows. A missing delivery is created
                    // below through the normal assignment mechanism.
                    if (!curriculumEligibilityService.isStructurallyEligible(course, section,
                course.getSemester() != null ? course.getSemester().getSemesterNo() : 0)) {
                        continue;
                    }
                    String ownerMajor;
                    try {
                        ownerMajor = curriculumEligibilityService.ownerMajorCode(course);
                    } catch (BusinessRuleException e) {
                        continue; // ownership data broken; surfaced elsewhere
                    }
                    // A major that owns a DEDICATED delivery section (name ==
                    // major code, e.g. "CT") receives its specialised required
                    // courses there; mixed sections must not duplicate that
                    // delivery (CT-2236 stays on the CT section instead of
                    // cascading into A/B/C where CT students are also enrolled).
                    // Shared-owner curriculum (CST/general - the CST-2235 class)
                    // self-heals into EVERY eligible section regardless of
                    // pre-existing TeachingAssignment rows.
                    if (!dedicated && dedicatedSectionNames.contains(ownerMajor.toUpperCase())) {
                        continue;
                    }
                    if (coveredByCourse.getOrDefault(course.getCourseId(), Set.of()).contains(sectionId)) {
                        continue;
                    }
                    if (assignmentRepository.existsByTerm_TermIdAndCourse_CourseIdAndSection_SectionId(
                            term.getTermId(), course.getCourseId(), sectionId)) {
                        // A row exists but is cancelled (or filtered): that is an
                        // explicit data decision, not a gap to auto-fill.
                        continue;
                    }
                    Staff lecturer = resolveLecturer(term, course);
                    if (lecturer == null) {
                        log.warn("Curriculum binding: no eligible lecturer in unit of {} - "
                                        + "section {} stays uncovered",
                                course.getCourseCode(), section.getSectionName());
                        continue;
                    }
                    TeachingAssignment binding = new TeachingAssignment();
                    binding.setCourse(course);
                    binding.setStaff(lecturer);
                    binding.setSection(section);
                    binding.setTerm(term);
                    binding.setAssignmentStatus(AssignmentStatus.ACTIVE);
                    binding.setAssignedAt(Instant.now());
                    created.add(assignmentRepository.save(binding));
                    coveredByCourse.computeIfAbsent(course.getCourseId(), k -> new HashSet<>())
                            .add(sectionId);
                    log.info("Curriculum binding: {} -> section {} (lecturer {})",
                            course.getCourseCode(), section.getSectionName(), lecturer.getStaffName());
                }
            }
        }
        return created;
    }

    /**
     * Lecturer resolution for auto-bound deliveries. Prefers a lecturer who
     * already teaches this course this term (proven capability, guaranteed
     * course.unit == lecturer.unit); otherwise the least-loaded ACTIVE
     * lecturer of the course's owning unit. Availability itself is enforced
     * later by the solver's conflict grid.
     */
    private Staff resolveLecturer(AcademicTerm term, Course course) {
        UUID unitId = course.getUnit() != null ? course.getUnit().getUnitId() : null;
        if (unitId == null) {
            return null;
        }
        List<TeachingAssignment> termAssignments = assignmentRepository
                .findWithDetailsByTermId(term.getTermId()).stream()
                .filter(a -> a.getAssignmentStatus() != AssignmentStatus.CANCELLED)
                .toList();
        Optional<Staff> currentLecturer = termAssignments.stream()
                .filter(a -> a.getCourse().getCourseId().equals(course.getCourseId()))
                .map(TeachingAssignment::getStaff)
                .filter(s -> s.getUnit() != null && s.getUnit().getUnitId().equals(unitId))
                .min(Comparator.comparing(Staff::getStaffNo,
                        Comparator.nullsLast(Comparator.naturalOrder())));
        if (currentLecturer.isPresent()) {
            return currentLecturer.get();
        }
        Map<UUID, Long> loadByStaff = new HashMap<>();
        for (TeachingAssignment a : termAssignments) {
            loadByStaff.merge(a.getStaff().getStaffId(), 1L, Long::sum);
        }
        return staffRepository.findAll().stream()
                .filter(s -> s.getUnit() != null && s.getUnit().getUnitId().equals(unitId))
                .min(Comparator
                        .comparingLong((Staff s) -> loadByStaff.getOrDefault(s.getStaffId(), 0L))
                        .thenComparing(Staff::getStaffNo,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .orElse(null);
    }

    /**
     * Generation-time visibility: logs required curriculum deliveries that no
     * assignment or group binds to a selected section. These courses cannot be
     * scheduled; publish completeness will reject the timetable until the data
     * gap is closed.
     */
    private void warnUnboundCurriculumDeliveries(GenerationSession generation,
                                                 Map<UUID, Set<UUID>> scope,
                                                 List<TeachingAssignment> assignments,
                                                 Map<UUID, List<TeachingAssignmentGroupMember>> membersByGroup) {
        if (scope.isEmpty()) {
            return;
        }
        Map<UUID, Set<UUID>> boundByCourse = new HashMap<>();
        for (TeachingAssignment a : assignments) {
            boundByCourse.computeIfAbsent(a.getCourse().getCourseId(), k -> new HashSet<>())
                    .add(a.getSection().getSectionId());
        }
        for (List<TeachingAssignmentGroupMember> members : membersByGroup.values()) {
            Course course = members.get(0).getGroup().getCourse();
            Set<UUID> sections = boundByCourse.computeIfAbsent(course.getCourseId(), k -> new HashSet<>());
            for (TeachingAssignmentGroupMember m : members) {
                sections.add(m.getAssignment().getSection().getSectionId());
            }
        }
        List<String> gaps = new ArrayList<>();
        for (Map.Entry<UUID, Set<UUID>> scopeEntry : scope.entrySet()) {
            for (UUID sectionId : scopeEntry.getValue()) {
                Section section = sectionRepository.findById(sectionId).orElse(null);
                if (section == null) continue;
                // Mixed sections are included here too: shared-owner gaps are
                // auto-bound during generation, so any warning that survives to
                // this point is a genuine major-specific delivery the HOD still
                // has to assign manually.
                for (Course course : courseRepository.findBySemester_SemesterId(scopeEntry.getKey())) {
                    if (!course.isRequired()) continue;
                    if (!curriculumEligibilityService.isStructurallyEligible(course, section,
                semesterNumberOf(scopeEntry.getKey()))) {
                        continue;
                    }
                    if (!boundByCourse.getOrDefault(course.getCourseId(), Set.of()).contains(sectionId)) {
                        gaps.add(section.getSectionName() + "/" + course.getCourseCode());
                    }
                }
            }
        }
        if (!gaps.isEmpty()) {
            log.warn("Generation {}: {} required curriculum deliveries have no assignment/group "
                            + "and will not be scheduled: {}{}",
                    generation.getGenerationId(), gaps.size(),
                    String.join(", ", gaps.subList(0, Math.min(20, gaps.size()))),
                    gaps.size() > 20 ? " ..." : "");
        }
    }

    private void validateUnitPeriods(List<String> failures, Course course, String unitLabel,
                                     List<ClassSchedule> unitSchedules,
                                     Map<UUID, List<CourseMeetingRequirement>> cmrsByCourse) {
        List<CourseMeetingRequirement> requirements = cmrsByCourse
                .getOrDefault(course.getCourseId(), List.of()).stream()
                .sorted(Comparator.comparing(r -> r.getMeetingType()))
                .toList();

        int expectedPeriods = 0;
        List<Integer> expectedLengths = new ArrayList<>();
        List<String> requiredLines = new ArrayList<>();
        for (CourseMeetingRequirement req : requirements) {
            expectedPeriods += req.getSessionsPerWeek() * req.getPeriodsPerSession();
            for (int i = 0; i < req.getSessionsPerWeek(); i++) {
                expectedLengths.add(req.getPeriodsPerSession());
            }
            requiredLines.add(req.getSessionsPerWeek() + " session(s) x "
                    + req.getPeriodsPerSession() + " period(s) [" + req.getMeetingType() + "]");
        }

        List<ClassSchedule> schedules = unitSchedules != null ? unitSchedules : List.of();
        int actualPeriods = 0;
        List<Integer> actualLengths = new ArrayList<>();
        for (ClassSchedule s : schedules) {
            int length = s.getEndSlot().getDisplayOrder() - s.getStartSlot().getDisplayOrder() + 1;
            actualPeriods += length;
            actualLengths.add(length);
        }

        if (expectedPeriods == 0) {
            if (actualPeriods > 0) {
                failures.add(unitLabel + ": the course has no course meeting requirement "
                        + "but " + actualPeriods + " period(s) are scheduled");
            }
            return;
        }

        Collections.sort(expectedLengths);
        Collections.sort(actualLengths);
        if (actualPeriods != expectedPeriods || !expectedLengths.equals(actualLengths)) {
            failures.add(unitLabel
                    + "\n    Required: " + expectedPeriods + " period(s)/week"
                    + " (" + String.join(", ", requiredLines) + ")"
                    + "\n    Scheduled: " + actualPeriods + " period(s)"
                    + " in " + actualLengths.size() + " session(s)");
        }
    }

    private Map<UUID, List<CourseMeetingRequirement>> batchLoadCmrs(List<ClassSchedule> allSchedules) {
        Set<UUID> courseIds = new HashSet<>();
        for (ClassSchedule s : allSchedules) {
            if (s.getTeachingAssignment() != null) {
                courseIds.add(s.getTeachingAssignment().getCourse().getCourseId());
            } else if (s.getTeachingGroup() != null) {
                courseIds.add(s.getTeachingGroup().getCourse().getCourseId());
            }
        }
        Map<UUID, List<CourseMeetingRequirement>> map = new HashMap<>();
        if (!courseIds.isEmpty()) {
            for (CourseMeetingRequirement r : requirementRepository.findAllByCourse_CourseIdIn(courseIds)) {
                map.computeIfAbsent(r.getCourse().getCourseId(), k -> new ArrayList<>()).add(r);
            }
        }
        return map;
    }

    // ========== SCOPE PERSISTENCE ==========

public record PersistedScope(UUID examTypeId, List<PersistedSemester> semesters,
                              Boolean autoBindCurriculum) {}
    public record PersistedSemester(UUID semesterId, List<UUID> sectionIds) {}

    private String toScopeJson(UUID examTypeId, Map<UUID, Set<UUID>> scope,
                               boolean autoBindCurriculum) {
        List<PersistedSemester> semesters = scope.entrySet().stream()
                .map(e -> new PersistedSemester(e.getKey(),
                        e.getValue() == null ? null : new ArrayList<>(e.getValue())))
                .toList();
        try {
            return objectMapper.writeValueAsString(
                    new PersistedScope(examTypeId, semesters, autoBindCurriculum));
        } catch (JsonProcessingException e) {
            throw new BusinessRuleException("Could not persist generation scope: " + e.getMessage());
        }
    }

    private PersistedScope parseScope(String scopeJson) {
        if (scopeJson == null || scopeJson.isBlank()) return null;
        try {
            return objectMapper.readValue(scopeJson, PersistedScope.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private boolean shouldAutoBindCurriculum(PersistedScope persisted) {
        return persisted == null || persisted.autoBindCurriculum() == null
                || persisted.autoBindCurriculum();
    }

    private Map<UUID, Set<UUID>> toScopeMap(PersistedScope persisted) {
        Map<UUID, Set<UUID>> scope = new HashMap<>();
        if (persisted != null) {
            for (PersistedSemester ps : persisted.semesters()) {
                Set<UUID> sects = ps.sectionIds() == null ? null : new HashSet<>(ps.sectionIds());
                if (sects != null) scope.put(ps.semesterId(), sects);
            }
        }
        return scope;
    }

    // ========== HELPERS ==========

    private Integer resolveParity(UUID examTypeId) {
        if (examTypeId == null) return null;
        ExamType examType = examTypeRepository.findById(examTypeId)
                .orElseThrow(() -> new ResourceNotFoundException("Exam type not found"));
        String name = examType.getExamTypeName();
        if (name == null
                || !(name.toLowerCase().contains("mid") || name.toLowerCase().contains("final"))) {
            throw new BusinessRuleException(
                    "Only 'Mid Term' or 'Final Term' exam types can drive timetable generation");
        }
        return name.toLowerCase().contains("mid") ? 1 : 0;
    }

    /**
     * Mid Term section restrictions: semesters 5-8 use A, B, CT (not C).
     * Semesters 1-4 use all sections (A, B, C; CT excluded for semesters 1-2).
     * Returns null when no restriction applies.
     */
    private static Set<String> midTermAllowedSections(int semesterNo) {
        if (semesterNo >= 5) {
            return Set.of("A", "B", "CT");
        }
        return null;
    }

    /**
     * Emits a diagnostic dump of the exact generation scope and solver input.
     * Pure logging (no behavior change). Uses ONLY data already resident in the
     * preloaded {@link GenerationSnapshot} so no DB queries run inside the worker.
     */
    private void logScopeDiagnostic(
            GenerationSnapshot snapshot, Map<UUID, Set<UUID>> scope,
            List<TeachingAssignment> scopedAssignments,
            List<TeachingAssignment> scopedSingletons,
            Map<UUID, List<TeachingAssignmentGroupMember>> scopedMembersByGroup,
            Set<UUID> scopedGroupedIds) {

        java.util.function.Function<UUID, String> sectionName = id -> {
            for (Section s : snapshot.getSections()) {
                if (s.getSectionId().equals(id)) return s.getSectionName();
            }
            return "?";
        };

        log.info("========== GENERATION SCOPE DIAGNOSTIC ==========");
        log.info("Selected semesters:");
        for (Map.Entry<UUID, Set<UUID>> e : scope.entrySet()) {
            Integer semNo = snapshot.getSemester(e.getKey()) != null
                    ? snapshot.getSemester(e.getKey()).getSemesterNo() : null;
            List<String> secs = new ArrayList<>();
            if (e.getValue() != null) {
                for (UUID sec : e.getValue()) secs.add(sectionName.apply(sec));
                Collections.sort(secs);
            }
            log.info("  Sem-{}: {}",
                    semNo != null ? semNo : "?",
                    e.getValue() == null ? "ALL" : String.join(",", secs));
        }

        // AUTO-INCLUDED COMBINED GROUPS (already scoped by semester + member section)
        log.info("AUTO-INCLUDED COMBINED GROUPS:");
        if (scopedMembersByGroup.isEmpty()) {
            log.info("  (none)");
        }
        for (Map.Entry<UUID, List<TeachingAssignmentGroupMember>> e : scopedMembersByGroup.entrySet()) {
            List<TeachingAssignmentGroupMember> members = e.getValue();
            if (members.isEmpty()) continue;
            Course course = members.get(0).getGroup().getCourse();
            Semester sem = course.getSemester();
            List<String> secNames = new ArrayList<>();
            for (TeachingAssignmentGroupMember m : members) secNames.add(sectionName.apply(m.getAssignment().getSection().getSectionId()));
            Collections.sort(secNames);
            log.info("  Sem-{} / {} / [{}] (members={})",
                    sem != null ? sem.getSemesterNo() : "?",
                    course.getCourseCode(), String.join("+", secNames), members.size());
        }

        // IGNORED COMBINED GROUPS (group course semester out of scope, OR
        // every member section outside the selected sections)
        log.info("IGNORED COMBINED GROUPS (semester or all member sections outside scope):");
        Map<UUID, List<TeachingAssignmentGroupMember>> all = new LinkedHashMap<>();
        for (TeachingAssignmentGroupMember m : snapshot.getAllGroupMembers()) {
            all.computeIfAbsent(m.getGroup().getGroupId(), k -> new ArrayList<>()).add(m);
        }
        boolean anyIgnored = false;
        for (Map.Entry<UUID, List<TeachingAssignmentGroupMember>> e : all.entrySet()) {
            List<TeachingAssignmentGroupMember> allMembers = e.getValue();
            if (allMembers.isEmpty()) continue;
            TeachingAssignmentGroup group = allMembers.get(0).getGroup();
            Course course = group.getCourse();
            Semester sem = course.getSemester();
            boolean semInScope = scope.isEmpty()
                    || (sem != null && scope.containsKey(sem.getSemesterId()));
            // is it already included? (any member in an included group)
            boolean included = scopedMembersByGroup.containsKey(e.getKey());
            if (included) continue;
            List<String> secNames = new ArrayList<>();
            for (TeachingAssignmentGroupMember m : allMembers) secNames.add(sectionName.apply(m.getAssignment().getSection().getSectionId()));
            Collections.sort(secNames);
            log.info("  Sem-{} / {} / [{}] reason={}{}",
                    sem != null ? sem.getSemesterNo() : "?",
                    course.getCourseCode(), String.join("+", secNames),
                    semInScope ? "member section(s) not selected" : "semester not selected",
                    semInScope && scope.get(sem != null ? sem.getSemesterId() : null) == null
                            ? " (all sections selected)" : "");
            anyIgnored = true;
        }
        if (!anyIgnored) log.info("  (none)");

        // SOLVER INPUT SUMMARY by semester
        log.info("SOLVER INPUT SUMMARY:");
        Map<Integer, int[]> bySem = new LinkedHashMap<>();
        for (TeachingAssignment a : scopedAssignments) {
            Integer semNo = a.getCourse().getSemester() != null
                    ? a.getCourse().getSemester().getSemesterNo() : -1;
            int[] c = bySem.computeIfAbsent(semNo, k -> new int[3]);
            boolean grouped = scopedGroupedIds.contains(a.getAssignmentId());
            if (grouped) c[1]++; else c[2]++;
            c[0]++;
        }
        for (Map.Entry<Integer, int[]> e : bySem.entrySet()) {
            log.info("  Sem-{}: assignments={}, grouped={}, singleton={}",
                    e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2]);
        }
        log.info("  Combined groups in solver: {}", scopedMembersByGroup.size());
        log.info("========== END GENERATION SCOPE DIAGNOSTIC ==========");
    }

    private boolean inScope(TeachingAssignment a, Map<UUID, Set<UUID>> scope) {
        if (scope.isEmpty()) return true;
        Semester sem = a.getCourse().getSemester();
        if (sem == null || !scope.containsKey(sem.getSemesterId())) return false;
        Set<UUID> sections = scope.get(sem.getSemesterId());
        return sections == null || sections.contains(a.getSection().getSectionId());
    }

    /**
     * True when a combined-class group belongs to a semester inside the generation
     * scope. Uses the group course's actual semester relationship (same identity
     * used by {@link #inScope(TeachingAssignment, Map)} and the scope validation) â€”
     * never the course code. All members of a group share the same group course,
     * so the whole group is either in scope or out of scope.
     */
    private boolean groupSemesterInScope(TeachingAssignmentGroupMember m,
                                         Map<UUID, Set<UUID>> scope) {
        if (scope.isEmpty()) return true;
        Semester sem = m.getGroup().getCourse().getSemester();
        return sem != null && scope.containsKey(sem.getSemesterId());
    }

    /** Group raw group members by group ID (used during snapshot preload). */
    private Map<UUID, List<TeachingAssignmentGroupMember>> groupMembersByGroupId(
            List<TeachingAssignmentGroupMember> members) {
        Map<UUID, List<TeachingAssignmentGroupMember>> byGroup = new LinkedHashMap<>();
        for (TeachingAssignmentGroupMember m : members) {
            byGroup.computeIfAbsent(m.getGroup().getGroupId(), k -> new ArrayList<>()).add(m);
        }
        return byGroup;
    }

    private String describe(TeachingAssignment a) {
        Integer semNo = a.getCourse().getSemester() != null
                ? a.getCourse().getSemester().getSemesterNo() : null;
        return "Semester " + (semNo != null ? semNo : "?")
                + " / Section " + a.getSection().getSectionName()
                + " / " + a.getCourse().getCourseCode();
    }

    private String describeGroup(List<TeachingAssignmentGroupMember> members) {
        TeachingAssignmentGroup group = members.get(0).getGroup();
        Integer semNo = group.getCourse().getSemester() != null
                ? group.getCourse().getSemester().getSemesterNo() : null;
        List<String> names = members.stream()
                .map(m -> m.getAssignment().getSection().getSectionName())
                .sorted()
                .toList();
        return "Semester " + (semNo != null ? semNo : "?")
                + " / Sections " + String.join(" + ", names)
                + " / " + group.getCourse().getCourseCode();
    }

    public GenerationSession findGeneration(UUID generationId) {
        return generationRepository.findById(generationId)
                .orElseThrow(() -> new ResourceNotFoundException("Generation session not found"));
    }

    static GenerationSessionResponse toResponse(GenerationSession session) {
        return new GenerationSessionResponse(
                session.getGenerationId(),
                session.getTerm().getTermId(),
                session.getTerm().getAcademicYear(),
                session.getGeneratedByStaff().getStaffId(),
                session.getGeneratedByStaff().getStaffNo(),
                session.getStatus(),
                session.getStartedAt(),
                session.getPublishedAt(),
                session.getFinishedAt(),
                session.getCreatedAt(),
                session.getFailureReport());
    }
}



