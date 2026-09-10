package com.unicconnect.service;

import com.unicconnect.dto.request.ScheduleRequest;
import com.unicconnect.dto.request.SwapCellRequest;
import com.unicconnect.dto.request.SwapScheduleRequest;
import com.unicconnect.dto.response.ScheduleResponse;
import com.unicconnect.dto.response.SwapScheduleResponse;
import com.unicconnect.entity.*;
import com.unicconnect.exception.BusinessRuleException;
import com.unicconnect.exception.LecturerConflictException;
import com.unicconnect.exception.ResourceNotFoundException;
import com.unicconnect.exception.ValidationException;
import com.unicconnect.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class ClassScheduleService {

    private final ClassScheduleRepository scheduleRepository;
    private final GenerationSessionRepository generationRepository;
    private final TeachingAssignmentRepository assignmentRepository;
    private final TeachingAssignmentGroupRepository teachingGroupRepository;
    private final TimeSlotRepository timeSlotRepository;
    private final HodAccessService hodAccessService;
    private final TimetableEditLockService editLockService;
    private final TimetableRealtimeEventService realtimeEventService;

    public ClassScheduleService(ClassScheduleRepository scheduleRepository,
                                GenerationSessionRepository generationRepository,
                                TeachingAssignmentRepository assignmentRepository,
                                TeachingAssignmentGroupRepository teachingGroupRepository,
                                TimeSlotRepository timeSlotRepository,
                                HodAccessService hodAccessService,
                                TimetableEditLockService editLockService,
                                TimetableRealtimeEventService realtimeEventService) {
        this.scheduleRepository = scheduleRepository;
        this.generationRepository = generationRepository;
        this.assignmentRepository = assignmentRepository;
        this.teachingGroupRepository = teachingGroupRepository;
        this.timeSlotRepository = timeSlotRepository;
        this.hodAccessService = hodAccessService;
        this.editLockService = editLockService;
        this.realtimeEventService = realtimeEventService;
    }

    public List<ScheduleResponse> getAll(UUID termId, UUID sectionId, UUID staffId, Integer dayOfWeek) {
        List<ClassSchedule> schedules;
        if (termId != null) {
            schedules = scheduleRepository.findByTermIdWithDetails(termId);
            // Normal users must never see draft schedules: only the published
            // generation for the term (if any) is visible to non-HOD callers.
            if (hodAccessService.currentHod().isEmpty()) {
                UUID publishedId = generationRepository
                        .findFirstByTerm_TermIdAndStatusOrderByCreatedAtDesc(
                                termId, GenerationStatus.PUBLISHED)
                        .map(GenerationSession::getGenerationId)
                        .orElse(null);
                schedules = schedules.stream()
                        .filter(s -> publishedId != null
                                && s.getGeneration().getGenerationId().equals(publishedId))
                        .toList();
            }
        } else if (sectionId != null) {
            schedules = scheduleRepository.findBySectionIdWithDetails(sectionId);
        } else if (staffId != null) {
            schedules = scheduleRepository.findByStaffIdWithDetails(staffId);
        } else if (dayOfWeek != null) {
            schedules = scheduleRepository.findByDayOfWeekWithDetails(dayOfWeek);
        } else {
            schedules = scheduleRepository.findAllWithDetails();
        }
        return schedules.stream()
                .sorted(Comparator.comparing(ClassSchedule::getDayOfWeek)
                        .thenComparing(s -> s.getStartSlot().getDisplayOrder()))
                .map(ClassScheduleService::toResponse).toList();
    }

    /**
     * Only the schedules of the term's published generation — the "normal" view.
     */
    public List<ScheduleResponse> getPublished(UUID termId) {
        UUID publishedId = generationRepository
                .findFirstByTerm_TermIdAndStatusOrderByCreatedAtDesc(termId, GenerationStatus.PUBLISHED)
                .map(GenerationSession::getGenerationId)
                .orElse(null);
        if (publishedId == null) {
            return List.of();
        }
        return scheduleRepository.findByGeneration_GenerationId(publishedId).stream()
                .filter(s -> s.getScheduleStatus() != ScheduleStatus.CANCELLED)
                .sorted(Comparator.comparing(ClassSchedule::getDayOfWeek)
                        .thenComparing(s -> s.getStartSlot().getDisplayOrder()))
                .map(ClassScheduleService::toResponse).toList();
    }

    public ScheduleResponse getById(UUID scheduleId) {
        return toResponse(findSchedule(scheduleId));
    }

    @Transactional
    public ScheduleResponse create(ScheduleRequest request) {
        hodAccessService.requireHod();
        GenerationSession generation = generationRepository.findById(request.generationId())
                .orElseThrow(() -> new ResourceNotFoundException("Generation session not found"));
        requireEditable(generation);
        editLockService.requireLockOwned(generation.getGenerationId());
        ClassSchedule schedule = new ClassSchedule();
        apply(schedule, request);
        ScheduleResponse response = toResponse(scheduleRepository.save(schedule));
        realtimeEventService.publishForGeneration(generation.getGenerationId(),
                TimetableRealtimeEventService.SCHEDULE_CREATED,
                Map.of("generationId", generation.getGenerationId(),
                        "scheduleId", response.scheduleId()));
        return response;
    }

    @Transactional
    public ScheduleResponse update(UUID scheduleId, ScheduleRequest request) {
        hodAccessService.requireHod();
        ClassSchedule schedule = findSchedule(scheduleId);
        GenerationSession generation = schedule.getGeneration();
        requireEditable(generation);
        editLockService.requireLockOwned(generation.getGenerationId());
        apply(schedule, request);
        ScheduleResponse response = toResponse(scheduleRepository.save(schedule));
        realtimeEventService.publishForGeneration(generation.getGenerationId(),
                TimetableRealtimeEventService.SCHEDULE_UPDATED,
                Map.of("generationId", generation.getGenerationId(),
                        "scheduleId", scheduleId));
        return response;
    }

    /**
     * Swap two schedules (or move one when the drop cell is empty).
     *
     * <p>When the target cell is occupied by another schedule the swap is
     * simulated first. If the simulated swap creates conflicts and the caller has
     * not confirmed ({@code force=false}), the swap is <b>not</b> applied and the
     * conflict descriptions are returned so the UI can ask
     * "Are you sure you want to switch these periods?". Only an explicit
     * {@code force=true} confirmation applies a conflicting swap.
     */
    @Transactional
    public SwapScheduleResponse swap(UUID generationId, SwapScheduleRequest request) {
        hodAccessService.requireHod();
        ClassSchedule source = scheduleRepository.findById(request.scheduleId())
                .orElseThrow(() -> new ResourceNotFoundException("Class schedule not found"));
        if (!source.getGeneration().getGenerationId().equals(generationId)) {
            throw new ValidationException("Schedule does not belong to this generation");
        }
        GenerationSession generation = source.getGeneration();
        requireEditable(generation);
        editLockService.requireLockOwned(generationId);

        if (request.targetDay() < 1 || request.targetDay() > 5) {
            throw new ValidationException("Schedules may only be placed Monday-Friday (day 1-5)");
        }
        List<TimeSlot> slots = timeSlotRepository.findAllByOrderByDisplayOrderAscPeriodNoAsc();
        TimeSlot targetStart = slotByPeriod(slots, request.targetPeriod());
        if (targetStart == null) {
            throw new ValidationException("Target period does not exist");
        }
        int sourceSpan = source.getEndSlot().getPeriodNo() - source.getStartSlot().getPeriodNo();
        TimeSlot sourceNewEnd = slotByPeriod(slots, request.targetPeriod() + sourceSpan);
        if (sourceNewEnd == null) {
            throw new ValidationException("That move would overflow the timetable");
        }

        UUID sourceId = source.getScheduleId();
        final ClassSchedule sourceSchedule = source;
        ClassSchedule target;
        if (request.targetScheduleId() != null) {
            // Pinned partner: exchange with exactly the occupant the editing
            // surface offered, as long as it still occupies the drop cell. This
            // keeps the executed swap identical to the one the HOD confirmed.
            UUID pinned = request.targetScheduleId();
            target = scheduleRepository.findByGeneration_GenerationId(generationId).stream()
                    .filter(s -> s.getScheduleStatus() != ScheduleStatus.CANCELLED)
                    .filter(s -> s.getScheduleId().equals(pinned))
                    .filter(s -> !s.getScheduleId().equals(sourceId))
                    .filter(s -> s.getDayOfWeek().equals(request.targetDay()))
                    .filter(s -> s.getStartSlot().getPeriodNo().equals(request.targetPeriod()))
                    .findFirst()
                    .orElse(null);
            if (target == null) {
                throw new ValidationException("Swap partner no longer occupies the drop cell");
            }
        } else {
            // Only a same-semester schedule occupies the drop cell from the
            // editing surface's point of view (each semester is edited on its own
            // grid tab). A different-semester schedule stacked at the same
            // day+period is NOT a swap partner: dropping there is a plain MOVE,
            // which is then validated (including lecturer availability) across
            // every semester.
            target = scheduleRepository.findByGeneration_GenerationId(generationId).stream()
                    .filter(s -> s.getScheduleStatus() != ScheduleStatus.CANCELLED)
                    .filter(s -> s.getDayOfWeek().equals(request.targetDay()))
                    .filter(s -> s.getStartSlot().getPeriodNo().equals(request.targetPeriod()))
                    .filter(s -> !s.getScheduleId().equals(sourceId))
                    .filter(s -> sameSemester(s, sourceSchedule))
                    .findFirst()
                    .orElse(null);
        }

        if (target == null) {
            // Plain move: reuse the single-schedule update path (conflicts reject).
            ScheduleResponse moved = update(source.getScheduleId(),
                    new ScheduleRequest(generationId,
                            source.getTeachingAssignment() != null
                                    ? source.getTeachingAssignment().getAssignmentId() : null,
                            source.getTeachingGroup() != null
                                    ? source.getTeachingGroup().getGroupId() : null,
                            request.targetDay(),
                            targetStart.getSlotId(),
                            sourceNewEnd.getSlotId(),
                            source.getScheduleType(),
                            source.getScheduleStatus(),
                            source.getMeetingType()));
            return new SwapScheduleResponse(false, List.of(), List.of(moved));
        }

        // Swap: both schedules exchange positions.
        Integer sourceDay = source.getDayOfWeek();
        int targetSpan = target.getEndSlot().getPeriodNo() - target.getStartSlot().getPeriodNo();
        TimeSlot targetNewStart = source.getStartSlot();
        TimeSlot targetNewEnd = slotByPeriod(slots, source.getStartSlot().getPeriodNo() + targetSpan);
        if (targetNewEnd == null) {
            throw new ValidationException("That swap would overflow the timetable");
        }

        // Lecturer conflicts are hard blocks: neither the dragged nor the
        // displaced course may leave its lecturer double-booked, across every
        // semester of the active generation. This runs before any commit and is
        // not bypassed by force=true.
        validateLecturerConflicts(generation,
                List.of(Placement.of(source, request.targetDay(), targetStart, sourceNewEnd),
                        Placement.of(target, sourceDay, targetNewStart, targetNewEnd)));

        List<String> conflicts = new ArrayList<>();
        conflicts.addAll(collectSwapConflicts(source, request.targetDay(), targetStart, sourceNewEnd, target));
        conflicts.addAll(collectSwapConflicts(target, sourceDay, targetNewStart, targetNewEnd, source));

        if (!conflicts.isEmpty() && !request.force()) {
            return new SwapScheduleResponse(false, conflicts, null);
        }

        source.setDayOfWeek(request.targetDay());
        source.setStartSlot(targetStart);
        source.setEndSlot(sourceNewEnd);
        target.setDayOfWeek(sourceDay);
        target.setStartSlot(targetNewStart);
        target.setEndSlot(targetNewEnd);
        source = scheduleRepository.save(source);
        target = scheduleRepository.save(target);

        ScheduleResponse sourceResponse = toResponse(source);
        ScheduleResponse targetResponse = toResponse(target);
        realtimeEventService.publishForGeneration(generationId,
                TimetableRealtimeEventService.SCHEDULE_UPDATED,
                Map.of("generationId", generationId, "scheduleId", sourceResponse.scheduleId()));
        realtimeEventService.publishForGeneration(generationId,
                TimetableRealtimeEventService.SCHEDULE_UPDATED,
                Map.of("generationId", generationId, "scheduleId", targetResponse.scheduleId()));
        return new SwapScheduleResponse(true, List.of(), List.of(sourceResponse, targetResponse));
    }

    /**
     * Cell-granularity swap for the "single cell" drag mode.
     *
     * <p>The user grabs one period of a schedule and drops it onto a single other
     * cell. The two cells exchange their course content and each source schedule
     * that spans several periods is decomposed around its swapped cell, so the
     * courses stay contiguous wherever possible. Swapping the P2 half of a P1-P2
     * session with a single period at P3 swaps cells P2 and P3 only: the block
     * keeps its P1 cell and gains a second session at P3, and the displaced
     * course takes the vacated P2 cell.
     *
     * <p>Swap partners are resolved within the same semester only (each semester
     * is edited on its own grid tab); a drop onto an empty, cross-semester or
     * unpartnered cell falls back to the plain block move/swap. Shared-group
     * sessions and section-less special blocks decompose around the grabbed cell
     * just like single-section courses. Lecturer availability — based on the
     * lecturer's other classes across every semester of the generation — is
     * re-checked for every resulting part and is a soft (forceable) block so the
     * HOD may resolve overlaps manually.
     */
    @Transactional
    public SwapScheduleResponse swapCell(UUID generationId, SwapCellRequest request) {
        hodAccessService.requireHod();
        GenerationSession generation = generationRepository.findById(generationId)
                .orElseThrow(() -> new ResourceNotFoundException("Generation not found"));
        requireEditable(generation);
        editLockService.requireLockOwned(generationId);

        if (request.sourceDay() < 1 || request.sourceDay() > 5
                || request.targetDay() < 1 || request.targetDay() > 5) {
            throw new ValidationException("Schedules may only be placed Monday-Friday (day 1-5)");
        }
        List<TimeSlot> slots = timeSlotRepository.findAllByOrderByDisplayOrderAscPeriodNoAsc();
        TimeSlot sourceCellSlot = slotByPeriod(slots, request.sourcePeriod());
        TimeSlot targetCellSlot = slotByPeriod(slots, request.targetPeriod());
        if (sourceCellSlot == null || targetCellSlot == null) {
            throw new ValidationException("Target period does not exist");
        }

        // The schedules at the two cells are resolved together: on this grid a
        // day+period cell can be shared by several semesters/sections, so the
        // source and target must belong to the SAME table (same semester +
        // section) — matching how the client qualifies a swap partner. When the
        // caller still names a live row that covers the cell (first swap), it is
        // preferred; otherwise (undo/redo after a split replaced the rows) any
        // qualifying row of that table is used.
        List<ClassSchedule> coveringSource = coveringSchedules(generationId, request.sourceDay(), request.sourcePeriod());
        List<ClassSchedule> coveringTarget = coveringSchedules(generationId, request.targetDay(), request.targetPeriod());
        if (coveringSource.isEmpty()) {
            throw new ValidationException("No schedule occupies the grabbed cell");
        }
        if (request.sourceDay().equals(request.targetDay())
                && request.sourcePeriod().equals(request.targetPeriod())) {
            return new SwapScheduleResponse(false, List.of(), List.of());
        }

        ClassSchedule named = coveringSource.stream()
                .filter(s -> s.getScheduleId().equals(request.scheduleId()))
                .findFirst()
                .orElse(null);
        ClassSchedule source = named != null ? named : coveringSource.get(0);
        ClassSchedule targetNamed = request.targetScheduleId() == null ? null
                : coveringTarget.stream()
                        .filter(s -> s.getScheduleId().equals(request.targetScheduleId()))
                        .findFirst()
                        .orElse(null);

        ClassSchedule target = null;
        if (source != null) {
            // Prefer the row the caller named on the source cell (fresh swap),
            // then any same-table (same semester + section) course on the target
            // cell. A named target always wins so the occupant the client saw on
            // the drop cell is honoured exactly.
            ClassSchedule src = source;
            List<ClassSchedule> partners = coveringTarget.stream()
                    .filter(t -> !t.getScheduleId().equals(src.getScheduleId()))
                    .filter(t -> targetNamed == null
                            || t.getScheduleId().equals(targetNamed.getScheduleId()))
                    .filter(t -> isCourseSession(t) && sameSemester(src, t) && sameSection(src, t))
                    .toList();
            if (!partners.isEmpty()) {
                target = partners.get(0);
            }
        }
        if (target == null && targetNamed != null
                && (source == null || !targetNamed.getScheduleId().equals(source.getScheduleId()))) {
            target = targetNamed;
        }
        if (target == null && source != null) {
            // The drop cell may host a course-less special block (assignment /
            // LMS / exam) with no section of its own — a legitimate cell occupant
            // even though it belongs to no semester+section table.
            ClassSchedule src = source;
            target = coveringTarget.stream()
                    .filter(t -> !t.getScheduleId().equals(src.getScheduleId()))
                    .filter(ClassScheduleService::isSpecialBlock)
                    .findFirst()
                    .orElse(null);
        }
        if (target == null && source != null) {
            // Pair on the OTHER cell: any same-table row that pairs back to a
            // source candidate (undo/redo after a split replaced the rows).
            for (ClassSchedule t : coveringTarget) {
                if (t.getScheduleId().equals(source.getScheduleId())) continue;
                for (ClassSchedule s : coveringSource) {
                    if (sameSemester(t, s) && sameSection(t, s)) {
                        source = s;
                        target = t;
                        break;
                    }
                }
                if (target != null) break;
            }
        }

        // Empty or combined cells without a qualified partner fall back to the
        // plain block move/swap. Empty drops are pure moves; everything else —
        // courses, shared-group sessions and section-less special blocks — is
        // decomposed around the exchanged cell.
        if (target == null || !decomposable(source) || !decomposable(target)) {
            return swap(generationId, new SwapScheduleRequest(source.getScheduleId(),
                    request.targetDay(), request.targetPeriod(), request.force(), null));
        }

        Integer sourceDay = source.getDayOfWeek();
        Integer targetDay = target.getDayOfWeek();
        int sStart = source.getStartSlot().getPeriodNo();
        int sEnd = source.getEndSlot().getPeriodNo();
        int sCell = request.sourcePeriod();
        int tStart = target.getStartSlot().getPeriodNo();
        int tEnd = target.getEndSlot().getPeriodNo();
        int tCell = request.targetPeriod();
        if (request.sourceDay().equals(request.targetDay()) && sCell == tCell) {
            return new SwapScheduleResponse(false, List.of(), List.of());
        }

        List<Placement> placements = new ArrayList<>();
        if (sStart <= sCell - 1) placements.add(Placement.of(source, sourceDay,
                slotByPeriod(slots, sStart), slotByPeriod(slots, sCell - 1)));
        if (sCell + 1 <= sEnd) placements.add(Placement.of(source, sourceDay,
                slotByPeriod(slots, sCell + 1), slotByPeriod(slots, sEnd)));
        placements.add(Placement.of(source, targetDay, targetCellSlot, targetCellSlot));
        if (tStart <= tCell - 1) placements.add(Placement.of(target, targetDay,
                slotByPeriod(slots, tStart), slotByPeriod(slots, tCell - 1)));
        if (tCell + 1 <= tEnd) placements.add(Placement.of(target, targetDay,
                slotByPeriod(slots, tCell + 1), slotByPeriod(slots, tEnd)));
        placements.add(Placement.of(target, sourceDay, sourceCellSlot, sourceCellSlot));

        // Only the two classes being swapped matter: a swap is clean unless one
        // of the involved lecturers already teaches elsewhere at an exchanged
        // cell. These stay soft so the caller may force and resolve overlaps
        // manually for review.
        List<String> conflicts = collectCellSwapConflicts(generation, placements,
                Set.of(source.getScheduleId(), target.getScheduleId()));
        if (!conflicts.isEmpty() && !request.force()) {
            return new SwapScheduleResponse(false, conflicts, null);
        }

        List<ScheduleResponse> created = new ArrayList<>();
        for (Placement p : placements) {
            ClassSchedule template = p.scheduleId().equals(source.getScheduleId()) ? source : target;
            ClassSchedule part = new ClassSchedule();
            part.setGeneration(generation);
            part.setTeachingAssignment(p.assignment());
            part.setTeachingGroup(p.group());
            part.setDayOfWeek(p.day());
            part.setStartSlot(p.start());
            part.setEndSlot(p.end());
            part.setScheduleType(template.getScheduleType());
            part.setMeetingType(template.getMeetingType());
            part.setScheduleStatus(template.getScheduleStatus());
            created.add(toResponse(scheduleRepository.save(part)));
        }
        scheduleRepository.delete(source);
        scheduleRepository.delete(target);

        // "Only one special per cell": a swap never leaves two pinned special
        // blocks stacked on one cell. When the swapped-in special occupies a cell
        // that still hosts another special (Assignment / LMS / exam), that
        // leftover moves to the partner cell of the exchange so each cell shows
        // exactly the special the user swapped with. The swap's own new parts are
        // excluded so they are never displaced.
        Set<UUID> createdIds = new HashSet<>();
        for (ScheduleResponse r : created) createdIds.add(r.scheduleId());
        relocateDisplacedSpecials(generation, slots, source, target, placements, createdIds,
                sourceDay, request.sourcePeriod(), targetDay, request.targetPeriod());

        realtimeEventService.publishForGeneration(generationId,
                TimetableRealtimeEventService.SCHEDULE_UPDATED,
                Map.of("generationId", generationId, "scheduleId", source.getScheduleId()));
        realtimeEventService.publishForGeneration(generationId,
                TimetableRealtimeEventService.SCHEDULE_UPDATED,
                Map.of("generationId", generationId, "scheduleId", target.getScheduleId()));
        return new SwapScheduleResponse(true, List.of(), created);
    }

    /**
     * Soft (forceable) conflict scan for a single-cell swap. Only the two classes
     * being swapped matter: a conflict is reported when one of the swapped
     * classes' lecturers already teaches anywhere in the active generation at a
     * covered day+period, so unrelated sections, semesters, courses or special
     * blocks never produce noise.
     */
    private List<String> collectCellSwapConflicts(GenerationSession generation, List<Placement> placements,
                                                  Set<UUID> removedIds) {
        // Occupancy base: every other schedule in the active generation, keyed by
        // (staffId, day, period).
        Map<UUID, Map<String, OccupiedInfo>> occupied = new HashMap<>();
        for (ClassSchedule existing : scheduleRepository.findByGeneration_GenerationId(
                generation.getGenerationId())) {
            if (existing.getScheduleStatus() == ScheduleStatus.CANCELLED) continue;
            if (removedIds.contains(existing.getScheduleId())) continue;
            for (UUID staffId : coveredStaff(existing)) {
                for (int p = existing.getStartSlot().getPeriodNo();
                        p <= existing.getEndSlot().getPeriodNo(); p++) {
                    occupied.computeIfAbsent(staffId, k -> new HashMap<>())
                            .put(key(existing.getDayOfWeek(), p), new OccupiedInfo(existing, null));
                }
            }
        }

        List<String> messages = new ArrayList<>();
        for (Placement placement : placements) {
            Set<UUID> staffIds = placementStaff(placement.assignment(), placement.group(), null);
            for (UUID staffId : staffIds) {
                Map<String, OccupiedInfo> staffMap = occupied.get(staffId);
                if (staffMap != null) {
                    for (int p = placement.start().getPeriodNo();
                            p <= placement.end().getPeriodNo(); p++) {
                        if (staffMap.get(key(placement.day(), p)) != null) {
                            messages.add("another engagement of the same lecturer");
                            break;
                        }
                    }
                }
                // Proposed positions become occupancy so a swap between two classes
                // of the same lecturer is cross-checked too.
                for (int p = placement.start().getPeriodNo();
                        p <= placement.end().getPeriodNo(); p++) {
                    occupied.computeIfAbsent(staffId, k -> new HashMap<>())
                            .put(key(placement.day(), p), new OccupiedInfo(null, placement));
                }
            }
        }
        return messages.stream().distinct().toList();
    }

    private TimeSlot slotByPeriod(List<TimeSlot> slots, int periodNo) {
        return slots.stream().filter(t -> t.getPeriodNo().equals(periodNo)).findFirst().orElse(null);
    }

    /**
     * Keeps at most one pinned special block (Assignment / LMS / exam) per cell.
     * For each placed special part of a cell swap, any OTHER section-less special
     * still covering that cell is relocated to the partner cell of the exchange —
     * the cell the other swapped class moved to — so the user never sees
     * "Assignment + LMS" stacked by a swap. Undoing the swap moves the leftover
     * back, restoring the original layout.
     */
    private void relocateDisplacedSpecials(GenerationSession generation, List<TimeSlot> slots,
                                           ClassSchedule source, ClassSchedule target,
                                           List<Placement> placements, Set<UUID> createdIds,
                                           Integer sourceDay, Integer sourcePeriod,
                                           Integer targetDay, Integer targetPeriod) {
        Set<UUID> excluded = new HashSet<>(createdIds);
        excluded.add(source.getScheduleId());
        excluded.add(target.getScheduleId());
        List<ClassSchedule> all = scheduleRepository.findByGeneration_GenerationId(
                generation.getGenerationId());
        for (Placement p : placements) {
            ClassSchedule owner = p.scheduleId().equals(source.getScheduleId()) ? source : target;
            if (owner.getScheduleType() == ScheduleType.COURSE) continue;
            int placedDay = p.day();
            int placedPeriod = p.start().getPeriodNo();
            int partnerDay;
            int partnerPeriod;
            if (p.scheduleId().equals(source.getScheduleId())) {
                // The swapped-in special came from the source cell; the displaced
                // leftover follows the partner class to its destination.
                partnerDay = sourceDay;
                partnerPeriod = sourcePeriod;
            } else {
                partnerDay = targetDay;
                partnerPeriod = targetPeriod;
            }
            TimeSlot partnerSlot = slotByPeriod(slots, partnerPeriod);
            for (ClassSchedule other : all) {
                if (other.getScheduleStatus() == ScheduleStatus.CANCELLED) continue;
                if (excluded.contains(other.getScheduleId())) continue;
                if (other.getScheduleType() == ScheduleType.COURSE) continue;
                if (!other.getDayOfWeek().equals(placedDay)) continue;
                int oStart = other.getStartSlot().getPeriodNo();
                int oEnd = other.getEndSlot().getPeriodNo();
                if (placedPeriod < oStart || placedPeriod > oEnd) continue;
                other.setDayOfWeek(partnerDay);
                other.setStartSlot(partnerSlot);
                other.setEndSlot(partnerSlot);
                scheduleRepository.save(other);
                realtimeEventService.publishForGeneration(generation.getGenerationId(),
                        TimetableRealtimeEventService.SCHEDULE_UPDATED,
                        Map.of("generationId", generation.getGenerationId(),
                                "scheduleId", other.getScheduleId()));
            }
        }
    }

    /**
     * Non-throwing conflict scan for one side of a simulated swap: would
     * {@code moved} at ({@code day}, {@code start}-{@code end}) collide with any
     * schedule other than itself and its swap partner? Mirrors the rules of
     * {@link #validateNoConflicts} but returns human-readable descriptions.
     */
    private List<String> collectSwapConflicts(ClassSchedule moved, int day, TimeSlot start, TimeSlot end,
                                              ClassSchedule partner) {
        List<String> messages = new ArrayList<>();
        List<ClassSchedule> daySchedules = scheduleRepository.findByGeneration_GenerationId(
                moved.getGeneration().getGenerationId()).stream()
                .filter(s -> s.getDayOfWeek().equals(day))
                .filter(s -> !s.getScheduleId().equals(moved.getScheduleId()))
                .filter(s -> !s.getScheduleId().equals(partner.getScheduleId()))
                .filter(s -> s.getScheduleStatus() != ScheduleStatus.CANCELLED)
                .toList();

        if (moved.getScheduleType() == ScheduleType.COURSE) {
            Set<UUID> candidateStaff = new HashSet<>();
            if (moved.getTeachingGroup() != null) {
                for (TeachingAssignmentGroupMember m : moved.getTeachingGroup().getMembers()) {
                    candidateStaff.add(m.getAssignment().getStaff().getStaffId());
                }
            } else if (moved.getTeachingAssignment() != null) {
                candidateStaff.add(moved.getTeachingAssignment().getStaff().getStaffId());
            }

            // Only the two classes being swapped are reviewed: a swap is clean
            // unless one of the involved lecturers already teaches somewhere in
            // the exchanged window. Other courses of other lecturers and the
            // section-less special blocks (assignment / LMS / exam) are never
            // conflict sources — the same policy as the single-cell swap.
            for (ClassSchedule other : daySchedules) {
                if (other.getScheduleType() != ScheduleType.COURSE) continue;
                if (!overlaps(start, end, List.of(other))) continue;
                if (!Collections.disjoint(candidateStaff, coveredStaff(other))) {
                    messages.add(describeSwapConflict(moved, other, day,
                            "another engagement of the same lecturer"));
                }
            }
        } else {
            for (ClassSchedule other : daySchedules) {
                if (overlaps(start, end, List.of(other))
                        && other.getScheduleType() == ScheduleType.COURSE) {
                    messages.add(describeSwapConflict(moved, other, day,
                            "a COURSE session already occupies this slot"));
                }
            }
        }
        return messages;
    }

    private String describeSwapConflict(ClassSchedule moved, ClassSchedule other, int day, String reason) {
        String movedLabel = courseCodeOf(moved) != null
                ? courseCodeOf(moved) + " (" + moved.getScheduleType() + ")"
                : String.valueOf(moved.getScheduleType());
        String otherLabel = courseCodeOf(other) != null
                ? courseCodeOf(other) + " (" + other.getScheduleType() + ", "
                        + DAY_NAME[other.getDayOfWeek()] + " P" + other.getStartSlot().getPeriodNo()
                        + (other.getEndSlot().getPeriodNo() != other.getStartSlot().getPeriodNo()
                                ? "-P" + other.getEndSlot().getPeriodNo() : "")
                        + ")"
                : String.valueOf(other.getScheduleType());
        return movedLabel + " would conflict with " + otherLabel + " on day " + day + ": " + reason;
    }

    private static final String[] DAY_NAME = {"", "Mon", "Tue", "Wed", "Thu", "Fri"};

    private static final String[] FULL_DAY_NAME = {"", "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"};

    @Transactional
    public void delete(UUID scheduleId) {
        hodAccessService.requireHod();
        ClassSchedule schedule = findSchedule(scheduleId);
        GenerationSession generation = schedule.getGeneration();
        requireEditable(generation);
        editLockService.requireLockOwned(generation.getGenerationId());
        scheduleRepository.deleteById(scheduleId);
        realtimeEventService.publishForGeneration(generation.getGenerationId(),
                TimetableRealtimeEventService.SCHEDULE_DELETED,
                Map.of("generationId", generation.getGenerationId(),
                        "scheduleId", scheduleId));
    }

    private void requireEditable(GenerationSession generation) {
        if (generation.getStatus() == GenerationStatus.PUBLISHED) {
            throw new BusinessRuleException("Cannot modify schedules of a published timetable");
        }
    }

    private void apply(ClassSchedule schedule, ScheduleRequest request) {
        GenerationSession generation = generationRepository.findById(request.generationId())
                .orElseThrow(() -> new ResourceNotFoundException("Generation session not found"));
        if (generation.getStatus() == GenerationStatus.PUBLISHED) {
            throw new BusinessRuleException("Cannot modify schedules of a published timetable");
        }

        TeachingAssignment assignment = null;
        TeachingAssignmentGroup teachingGroup = null;
        if (request.scheduleType() == ScheduleType.COURSE) {
            boolean hasAssignment = request.teachingAssignmentId() != null;
            boolean hasGroup = request.teachingGroupId() != null;
            if (hasAssignment == hasGroup) {
                throw new ValidationException(
                        "COURSE schedules require exactly one of teachingAssignmentId or teachingGroupId");
            }
            if (hasGroup) {
                teachingGroup = teachingGroupRepository.findById(request.teachingGroupId())
                        .orElseThrow(() -> new ResourceNotFoundException("Teaching group not found"));
                if (!teachingGroup.getTerm().getTermId().equals(generation.getTerm().getTermId())) {
                    throw new ValidationException("Teaching group does not belong to this generation's term");
                }
                if (teachingGroup.getMembers().isEmpty()) {
                    throw new ValidationException("Teaching group has no member assignments");
                }
            } else {
                assignment = assignmentRepository.findById(request.teachingAssignmentId())
                        .orElseThrow(() -> new ResourceNotFoundException("Teaching assignment not found"));
                if (!assignment.getTerm().getTermId().equals(generation.getTerm().getTermId())) {
                    throw new ValidationException("Teaching assignment does not belong to this generation's term");
                }
            }
        } else {
            if (request.teachingAssignmentId() != null || request.teachingGroupId() != null) {
                throw new ValidationException("teachingAssignmentId/teachingGroupId must be null for "
                        + request.scheduleType() + " schedules");
            }
        }

        TimeSlot startSlot = timeSlotRepository.findById(request.startSlotId())
                .orElseThrow(() -> new ResourceNotFoundException("Start time slot not found"));
        TimeSlot endSlot = timeSlotRepository.findById(request.endSlotId())
                .orElseThrow(() -> new ResourceNotFoundException("End time slot not found"));
        if (startSlot.getDisplayOrder() > endSlot.getDisplayOrder()) {
            throw new ValidationException("startSlot must not be after endSlot");
        }
        if (request.dayOfWeek() < 1 || request.dayOfWeek() > 5) {
            throw new ValidationException("Schedules may only be placed Monday-Friday (day 1-5)");
        }

        validateLecturerConflicts(generation,
                new Placement(schedule.getScheduleId(), assignment, teachingGroup,
                        request.dayOfWeek(), startSlot, endSlot));
        validateNoConflicts(generation, schedule, request, startSlot, endSlot);

        schedule.setGeneration(generation);
        schedule.setTeachingAssignment(assignment);
        schedule.setTeachingGroup(teachingGroup);
        schedule.setDayOfWeek(request.dayOfWeek());
        schedule.setStartSlot(startSlot);
        schedule.setEndSlot(endSlot);
        schedule.setScheduleType(request.scheduleType());
        if (request.meetingType() != null) {
            schedule.setMeetingType(request.meetingType());
        }
        if (request.scheduleStatus() != null) {
            schedule.setScheduleStatus(request.scheduleStatus());
        }
    }

    /**
     * Authoritative cross-semester lecturer-conflict check for the proposed
     * post-edit positions.
     *
     * <p>A lecturer may not teach two sessions on the same day + period in ANY
     * semester/section of the active generation (the whole generation is the
     * conflict domain, not just the semester currently displayed). Combined
     * (group) classes are a single teaching session, so each member lecturer is
     * marked occupied once per period. The schedules whose positions change are
     * excluded from the existing-occupancy base and their proposed periods
     * become occupancy, so a schedule is never flagged against its own source
     * cell and a swap between two classes of the same lecturer is still
     * cross-checked. Lecturer conflicts are hard blocks: they never apply, even
     * with {@code force=true}.
     */
    private void validateLecturerConflicts(GenerationSession generation, Placement placement) {
        validateLecturerConflicts(generation, List.of(placement));
    }

    private void validateLecturerConflicts(GenerationSession generation, List<Placement> placements) {
        findLecturerConflict(generation, placements).ifPresent(c -> {
            throw new LecturerConflictException(c.lecturerId(), c.lecturerName(), c.day(), c.period(),
                    c.conflictingSemester(), c.conflictingSection(), c.conflictingCourseCode());
        });
    }

    private Optional<ConflictInfo> findLecturerConflict(GenerationSession generation, List<Placement> placements) {
        Set<UUID> movingIds = placements.stream()
                .map(Placement::scheduleId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        // Occupancy base: every other schedule in the active generation, keyed by
        // (staffId, day, period). This spans all semesters and sections.
        Map<UUID, Map<String, OccupiedInfo>> occupied = new HashMap<>();
        for (ClassSchedule existing : scheduleRepository.findByGeneration_GenerationId(
                generation.getGenerationId())) {
            if (existing.getScheduleStatus() == ScheduleStatus.CANCELLED) continue;
            if (movingIds.contains(existing.getScheduleId())) continue;
            for (UUID staffId : coveredStaff(existing)) {
                for (int p = existing.getStartSlot().getPeriodNo();
                        p <= existing.getEndSlot().getPeriodNo(); p++) {
                    occupied.computeIfAbsent(staffId, k -> new HashMap<>())
                            .put(key(existing.getDayOfWeek(), p), new OccupiedInfo(existing, null));
                }
            }
        }

        for (Placement placement : placements) {
            Map<UUID, String> staffNames = new HashMap<>();
            Set<UUID> staffIds = placementStaff(placement.assignment(), placement.group(), staffNames);
            for (UUID staffId : staffIds) {
                Map<String, OccupiedInfo> staffMap = occupied.get(staffId);
                if (staffMap == null) continue;
                for (int p = placement.start().getPeriodNo();
                        p <= placement.end().getPeriodNo(); p++) {
                    OccupiedInfo other = staffMap.get(key(placement.day(), p));
                    if (other != null) {
                        return Optional.of(buildConflict(staffId, staffNames.get(staffId),
                                placement.day(), p, other));
                    }
                }
            }
            // Proposed positions become occupancy so a swap between two classes of
            // the same lecturer is cross-checked against the other proposed class.
            for (UUID staffId : staffIds) {
                for (int p = placement.start().getPeriodNo();
                        p <= placement.end().getPeriodNo(); p++) {
                    occupied.computeIfAbsent(staffId, k -> new HashMap<>())
                            .put(key(placement.day(), p), new OccupiedInfo(null, placement));
                }
            }
        }
        return Optional.empty();
    }

    private ConflictInfo buildConflict(UUID lecturerId, String lecturerName, int day, int period,
                                       OccupiedInfo other) {
        String section = other.sectionNames().stream().sorted().collect(Collectors.joining(" + "));
        Semester semester = other.semester();
        return new ConflictInfo(lecturerId, lecturerName, FULL_DAY_NAME[day], "P" + period,
                semester != null ? semester.getSemesterNo() : null,
                section == null || section.isBlank() ? null : section,
                other.courseCode());
    }

    private static String key(int day, int period) {
        return day + "|" + period;
    }

    /** Staff covered by an assignment/group placement plus their names. */
    private static Set<UUID> placementStaff(TeachingAssignment assignment, TeachingAssignmentGroup group,
                                            Map<UUID, String> names) {
        Set<UUID> ids = new HashSet<>();
        if (group != null) {
            for (TeachingAssignmentGroupMember m : group.getMembers()) {
                Staff staff = m.getAssignment().getStaff();
                ids.add(staff.getStaffId());
                if (names != null) names.put(staff.getStaffId(), staff.getStaffName());
            }
        } else if (assignment != null) {
            Staff staff = assignment.getStaff();
            ids.add(staff.getStaffId());
            if (names != null) names.put(staff.getStaffId(), staff.getStaffName());
        }
        return ids;
    }

    /** Section names covered by a schedule: assignment section or every group member section. */
    static List<String> sectionNamesOf(ClassSchedule s) {
        List<String> names = new ArrayList<>();
        if (s.getTeachingAssignment() != null) {
            names.add(s.getTeachingAssignment().getSection().getSectionName());
        } else if (s.getTeachingGroup() != null) {
            for (TeachingAssignmentGroupMember m : s.getTeachingGroup().getMembers()) {
                names.add(m.getAssignment().getSection().getSectionName());
            }
        }
        return names;
    }

    /** A proposed post-edit position for one schedule (assignment/group resolved server-side). */
    private record Placement(UUID scheduleId, TeachingAssignment assignment, TeachingAssignmentGroup group,
                             int day, TimeSlot start, TimeSlot end) {

        static Placement of(ClassSchedule s, int day, TimeSlot start, TimeSlot end) {
            return new Placement(s.getScheduleId(), s.getTeachingAssignment(), s.getTeachingGroup(),
                    day, start, end);
        }
    }

    /** One occupant of a (staff, day, period) cell — either an existing schedule or a proposed placement. */
    private record OccupiedInfo(ClassSchedule schedule, Placement placement) {

        String courseCode() {
            if (schedule != null) return ClassScheduleService.courseCodeOf(schedule);
            if (placement.group() != null) return placement.group().getCourse().getCourseCode();
            if (placement.assignment() != null) return placement.assignment().getCourse().getCourseCode();
            return null;
        }

        List<String> sectionNames() {
            if (schedule != null) return ClassScheduleService.sectionNamesOf(schedule);
            List<String> names = new ArrayList<>();
            if (placement.group() != null) {
                for (TeachingAssignmentGroupMember m : placement.group().getMembers()) {
                    names.add(m.getAssignment().getSection().getSectionName());
                }
            } else if (placement.assignment() != null) {
                names.add(placement.assignment().getSection().getSectionName());
            }
            return names;
        }

        Semester semester() {
            if (schedule != null) return ClassScheduleService.semesterOf(schedule);
            Course course;
            if (placement.group() != null) {
                course = placement.group().getCourse();
            } else {
                course = placement.assignment() != null ? placement.assignment().getCourse() : null;
            }
            return course != null ? course.getSemester() : null;
        }
    }

    private record ConflictInfo(UUID lecturerId, String lecturerName, String day, String period,
                                Integer conflictingSemester, String conflictingSection,
                                String conflictingCourseCode) {}

    /**
     * Conflict validation shared by create/update. For combined teaching groups
     * the conflict surface is every member section and every member lecturer,
     * and a course may never occur twice on the same day for the same section.
     */
    private void validateNoConflicts(GenerationSession generation, ClassSchedule self,
                                     ScheduleRequest request, TimeSlot startSlot, TimeSlot endSlot) {
        List<ClassSchedule> daySchedules = scheduleRepository.findByGeneration_GenerationId(
                request.generationId()).stream()
                .filter(s -> s.getDayOfWeek().equals(request.dayOfWeek()))
                .filter(s -> !s.getScheduleId().equals(self != null ? self.getScheduleId() : null))
                .filter(s -> s.getScheduleStatus() != ScheduleStatus.CANCELLED)
                .toList();

        if (request.scheduleType() == ScheduleType.COURSE) {
            Set<UUID> candidateStaff = new HashSet<>();
            Set<UUID> candidateSections = new HashSet<>();
            String candidateCourse;
            Course candidateCourseEntity = null;
            UUID candidateGroupId = request.teachingGroupId();
            if (candidateGroupId != null) {
                TeachingAssignmentGroup group = teachingGroupRepository.findById(candidateGroupId)
                        .orElseThrow(() -> new ResourceNotFoundException("Teaching group not found"));
                for (TeachingAssignmentGroupMember m : group.getMembers()) {
                    candidateStaff.add(m.getAssignment().getStaff().getStaffId());
                    candidateSections.add(m.getAssignment().getSection().getSectionId());
                }
                candidateCourse = group.getCourse().getCourseCode();
                candidateCourseEntity = group.getCourse();
            } else {
                TeachingAssignment assignment = assignmentRepository.findById(request.teachingAssignmentId())
                        .orElseThrow();
                candidateStaff.add(assignment.getStaff().getStaffId());
                candidateSections.add(assignment.getSection().getSectionId());
                candidateCourse = assignment.getCourse().getCourseCode();
                candidateCourseEntity = assignment.getCourse();
            }

            for (ClassSchedule other : daySchedules) {
                boolean special = other.getScheduleType() != ScheduleType.COURSE;

                // RULE 13: one session per teaching unit per day.
                if (!special) {
                    if (candidateGroupId != null) {
                        if (other.getTeachingGroup() != null
                                && other.getTeachingGroup().getGroupId().equals(candidateGroupId)) {
                            throw new BusinessRuleException(
                                    "Combined course already has a session on day " + request.dayOfWeek());
                        }
                    } else if (other.getTeachingAssignment() != null
                            && other.getTeachingAssignment().getAssignmentId()
                                    .equals(request.teachingAssignmentId())) {
                        throw new BusinessRuleException(
                                "Course already has a session for this teaching assignment on day "
                                        + request.dayOfWeek());
                    }
                }

                if (overlaps(startSlot, endSlot, List.of(other))) {
                    Set<UUID> otherStaff = coveredStaff(other);
                    Set<UUID> otherSections = coveredSections(other);
                    // Elective co-location: same elective group (is_required=false,
                    // same semester) may share a window ONLY when the window is
                    // IDENTICAL (same day/start/end) and the lecturers differ;
                    // the lecturer conflict rule always wins.
                    boolean sameElectiveGroup = !special && other.getTeachingAssignment() != null
                            && sameElectiveGroup(candidateCourseEntity, other.getTeachingAssignment().getCourse());
                    boolean identicalWindow = startSlot.getDisplayOrder() == other.getStartSlot().getDisplayOrder()
                            && endSlot.getDisplayOrder() == other.getEndSlot().getDisplayOrder();
                    boolean sectionOverlap = !Collections.disjoint(candidateSections, otherSections);
                    // Sections are shared rows across semesters: different-semester
                    // cohorts may legitimately co-exist in one slot (the solver is
                    // semester-scoped); only same-semester co-existence conflicts.
                    boolean sameSemester = sameSemesterCourse(candidateCourseEntity, other);
                    if (special || !Collections.disjoint(candidateStaff, otherStaff)
                            || (sameSemester && !sameElectiveGroup && sectionOverlap)
                            || (sameSemester && sameElectiveGroup && !identicalWindow && sectionOverlap)) {
                        String reason = special ? "a " + other.getScheduleType() + " period"
                                : (!Collections.disjoint(candidateStaff, otherStaff)
                                        ? "another engagement of the same lecturer"
                                        : (sameElectiveGroup
                                                ? "a partial overlap of an elective-group window"
                                                : "another schedule for the same section"));
                        throw new BusinessRuleException("Schedule conflicts with " + reason
                                + " on day " + request.dayOfWeek());
                    }
                    // Same course must not appear twice in one day for the same section.
                    if (!special && candidateCourse != null
                            && candidateCourse.equals(courseCodeOf(other))
                            && !Collections.disjoint(candidateSections, otherSections)) {
                        throw new BusinessRuleException(
                                "Course " + candidateCourse + " is already scheduled for one of these sections on day "
                                        + request.dayOfWeek());
                    }
                }
            }
        } else {
            for (ClassSchedule other : daySchedules) {
                if (overlaps(startSlot, endSlot, List.of(other))
                        && other.getScheduleType() == ScheduleType.COURSE) {
                    throw new BusinessRuleException("COURSE schedule already occupies this slot on day "
                            + request.dayOfWeek());
                }
            }
        }
    }

    // ---------- Shared-teaching helpers ----------

    /** Staff covered by a schedule: its assignment lecturer or every group member lecturer. */
    static Set<UUID> coveredStaff(ClassSchedule s) {
        if (s.getTeachingAssignment() != null) {
            return Set.of(s.getTeachingAssignment().getStaff().getStaffId());
        }
        if (s.getTeachingGroup() != null) {
            Set<UUID> ids = new HashSet<>();
            for (TeachingAssignmentGroupMember m : s.getTeachingGroup().getMembers()) {
                ids.add(m.getAssignment().getStaff().getStaffId());
            }
            return ids;
        }
        return Set.of();
    }

    /** Sections covered by a schedule: its assignment section or every group member section. */
    static Set<UUID> coveredSections(ClassSchedule s) {
        if (s.getTeachingAssignment() != null) {
            return Set.of(s.getTeachingAssignment().getSection().getSectionId());
        }
        if (s.getTeachingGroup() != null) {
            Set<UUID> ids = new HashSet<>();
            for (TeachingAssignmentGroupMember m : s.getTeachingGroup().getMembers()) {
                ids.add(m.getAssignment().getSection().getSectionId());
            }
            return ids;
        }
        return Set.of();
    }

    static String courseCodeOf(ClassSchedule s) {
        if (s.getTeachingAssignment() != null) {
            return s.getTeachingAssignment().getCourse().getCourseCode();
        }
        if (s.getTeachingGroup() != null) {
            return s.getTeachingGroup().getCourse().getCourseCode();
        }
        return null;
    }

    /**
     * True when the candidate course and the other schedule's course belong to the
     * same elective group: both is_required=false and assigned to the same semester.
     * Groups of electives may co-locate on identical windows when lecturers differ.
     */
    private static boolean sameElectiveGroup(Course candidate, Course other) {
        if (candidate == null || other == null
                || candidate.isRequired() || other.isRequired()) {
            return false;
        }
        Semester sa = candidate.getSemester();
        Semester sb = other.getSemester();
        return sa != null && sb != null && sa.getSemesterId().equals(sb.getSemesterId());
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

    /** True when the candidate course and the other schedule are same-semester (or unknown). */
    private static boolean sameSemesterCourse(Course candidate, ClassSchedule other) {
        Semester os = semesterOf(other);
        if (candidate == null || candidate.getSemester() == null || os == null) return true;
        return candidate.getSemester().getSemesterId().equals(os.getSemesterId());
    }

    /** True when two schedules belong to the same semester (unknown semester is treated as same). */
    private static boolean sameSemester(ClassSchedule a, ClassSchedule b) {
        Semester sa = semesterOf(a);
        Semester sb = semesterOf(b);
        if (sa == null || sb == null) return true;
        return sa.getSemesterId().equals(sb.getSemesterId());
    }

    /** True when both schedules belong to the same section (shared/combined ones are treated as same table). */
    private static boolean sameSection(ClassSchedule a, ClassSchedule b) {
        TeachingAssignment aa = a.getTeachingAssignment();
        TeachingAssignment ab = b.getTeachingAssignment();
        if (aa == null || aa.getSection() == null || ab == null || ab.getSection() == null) return true;
        return aa.getSection().getSectionId().equals(ab.getSection().getSectionId());
    }

    /** Active (non-cancelled) schedules of a generation that cover the given day+period cell. */
    private List<ClassSchedule> coveringSchedules(UUID generationId, Integer day, Integer period) {
        return scheduleRepository.findByGeneration_GenerationId(generationId).stream()
                .filter(s -> s.getScheduleStatus() != ScheduleStatus.CANCELLED)
                .filter(s -> s.getDayOfWeek().equals(day))
                .filter(s -> period >= s.getStartSlot().getPeriodNo()
                        && period <= s.getEndSlot().getPeriodNo())
                .toList();
    }

    /** True when the schedule is a real course session (assignment or shared group). */
    private static boolean isCourseSession(ClassSchedule s) {
        return s.getTeachingAssignment() != null || s.getTeachingGroup() != null;
    }

    /** True when the schedule is a course-less special block (assignment/LMS/exam) with no section. */
    private static boolean isSpecialBlock(ClassSchedule s) {
        return s.getTeachingAssignment() == null && s.getTeachingGroup() == null;
    }

    /** Every schedule row is decomposable: courses, shared-group sessions and
     * section-less special blocks all split around the exchanged cell. */
    private static boolean decomposable(ClassSchedule s) {
        return s != null;
    }

    static boolean overlaps(TimeSlot start, TimeSlot end, List<ClassSchedule> others) {
        for (ClassSchedule other : others) {
            if (start.getDisplayOrder() <= other.getEndSlot().getDisplayOrder()
                    && end.getDisplayOrder() >= other.getStartSlot().getDisplayOrder()) {
                return true;
            }
        }
        return false;
    }

    public ClassSchedule findSchedule(UUID scheduleId) {
        return scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new ResourceNotFoundException("Class schedule not found"));
    }

    static ScheduleResponse toResponse(ClassSchedule schedule) {
        UUID teachingAssignmentId = null;
        UUID teachingGroupId = null;
        String courseCode = null;
        String courseName = null;
        String staffName = null;
        String sectionName = null;
        Integer semesterNo = null;
        List<String> sections = new ArrayList<>();
        List<String> staffNames = new ArrayList<>();

        if (schedule.getTeachingAssignment() != null) {
            teachingAssignmentId = schedule.getTeachingAssignment().getAssignmentId();
            courseCode = schedule.getTeachingAssignment().getCourse().getCourseCode();
            courseName = schedule.getTeachingAssignment().getCourse().getCourseName();
            staffName = schedule.getTeachingAssignment().getStaff().getStaffName();
            sectionName = schedule.getTeachingAssignment().getSection().getSectionName();
            sections.add(sectionName);
            staffNames.add(staffName);
            semesterNo = schedule.getTeachingAssignment().getCourse().getSemester() != null
                    ? schedule.getTeachingAssignment().getCourse().getSemester().getSemesterNo() : null;
        } else if (schedule.getTeachingGroup() != null) {
            TeachingAssignmentGroup group = schedule.getTeachingGroup();
            teachingGroupId = group.getGroupId();
            courseCode = group.getCourse().getCourseCode();
            courseName = group.getCourse().getCourseName();
            List<String> memberSections = new ArrayList<>();
            for (TeachingAssignmentGroupMember m : group.getMembers()) {
                memberSections.add(m.getAssignment().getSection().getSectionName());
                staffNames.add(m.getAssignment().getStaff().getStaffName());
            }
            memberSections.sort(Comparator.naturalOrder());
            sections.addAll(memberSections);
            staffNames = staffNames.stream().distinct().sorted().collect(Collectors.toList());
            sectionName = String.join(" + ", memberSections);
            staffName = String.join(", ", staffNames);
            semesterNo = group.getCourse().getSemester() != null
                    ? group.getCourse().getSemester().getSemesterNo() : null;
        }

        return new ScheduleResponse(
                schedule.getScheduleId(),
                schedule.getGeneration().getGenerationId(),
                teachingAssignmentId,
                teachingGroupId,
                courseCode,
                courseName,
                staffName,
                sectionName,
                semesterNo,
                schedule.getDayOfWeek(),
                schedule.getStartSlot().getSlotId(),
                schedule.getStartSlot().getPeriodNo(),
                schedule.getStartSlot().getStartTime().toString(),
                schedule.getEndSlot().getSlotId(),
                schedule.getEndSlot().getPeriodNo(),
                schedule.getEndSlot().getEndTime().toString(),
                schedule.getScheduleStatus(),
                schedule.getScheduleType(),
                schedule.getMeetingType(),
                sections,
                staffNames,
                schedule.getCreatedAt());
    }
}
