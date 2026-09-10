package com.unicconnect.service;

import com.unicconnect.entity.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Immutable in-memory snapshot of all data needed for timetable generation.
 * Created ONCE per generation session when the lobby enters generation mode.
 * All DB queries happen during the PRELOADING phase; no repository calls
 * happen inside the solver — only this snapshot is used.
 *
 * <p>Lifecycle: CREATED → PRELOADING → PRELOADED → GENERATING → COMPLETED / FAILED
 *
 * <p>The snapshot partitions preloaded data into per-semester structures so
 * each semester's solver only sees its OWN data. Frozen occupancy from
 * completed semesters is tracked separately by the ConflictGrid.
 */
public class GenerationSnapshot {

    private static final Logger log = LoggerFactory.getLogger(GenerationSnapshot.class);

    public enum Status {
        CREATED, PRELOADING, PRELOADED, GENERATING, COMPLETED, FAILED
    }

    // ===== IDENTITY =====
    private final UUID snapshotId;
    private final UUID termId;
    private final UUID examTypeId;
    private final UUID generationId;
    private volatile Status status;

    // ===== PRELOADED DATA (immutable after PRELOADED) =====
    private List<TeachingAssignment> allAssignments;
    private List<TeachingAssignment> scopedAssignments;
    private List<TeachingAssignment> scopedSingletons;
    private List<TeachingAssignmentGroupMember> allGroupMembers;
    private Map<UUID, List<TeachingAssignmentGroupMember>> scopedMembersByGroup;
    private Set<UUID> scopedGroupedAssignmentIds;
    private Map<UUID, List<CourseMeetingRequirement>> requirementsByCourse;
    private List<TimeSlot> timeSlots;
    private List<Section> sections;
    private List<Semester> semesters;
    private Map<UUID, Semester> semesterById;

    // ===== SCOPE =====
    private Map<UUID, Set<UUID>> scope; // semesterId -> sectionIds (null = all)

    // ===== PER-SEMESTER PARTITIONS =====
    private final LinkedHashMap<UUID, SemesterPartition> semesterPartitions = new LinkedHashMap<>();

    // ===== TIMING / METRICS =====
    private long preloadStartTime;
    private long preloadEndTime;
    private int dbQueryCount;
    private int totalAssignmentsLoaded;
    private int totalGroupMembersLoaded;
    private int totalRequirementsLoaded;

    // ===== IN-MEMORY CACHE (thread-safe) =====
    private static final ConcurrentHashMap<UUID, GenerationSnapshot> activeSnapshots = new ConcurrentHashMap<>();

    public GenerationSnapshot(UUID termId, UUID examTypeId, UUID generationId) {
        this.snapshotId = UUID.randomUUID();
        this.termId = termId;
        this.examTypeId = examTypeId;
        this.generationId = generationId;
        this.status = Status.CREATED;
        this.semesterById = new HashMap<>();
    }

    // ===== STATIC LIFECYCLE MANAGEMENT =====

    public static GenerationSnapshot create(UUID termId, UUID examTypeId, UUID generationId) {
        GenerationSnapshot snap = new GenerationSnapshot(termId, examTypeId, generationId);
        activeSnapshots.put(generationId, snap);
        log.info("[SNAPSHOT] CREATE generationId={} snapshotId={} termId={} examTypeId={}",
                generationId, snap.snapshotId, termId, examTypeId);
        return snap;
    }

    public static Optional<GenerationSnapshot> findActive(UUID generationId) {
        GenerationSnapshot snap = activeSnapshots.get(generationId);
        log.info("[SNAPSHOT] LOOKUP generationId={} found={} status={}",
                generationId, snap != null, snap != null ? snap.status : null);
        return Optional.ofNullable(snap);
    }

    public static void remove(UUID generationId) {
        GenerationSnapshot removed = activeSnapshots.remove(generationId);
        if (removed != null) {
            log.info("[SNAPSHOT] REMOVED generationId={} snapshotId={}", generationId, removed.snapshotId);
        }
    }

    // ===== READINESS / LIFECYCLE =====

    /** A snapshot is READY (usable for generation) once its source is preloaded. */
    public boolean isReady() { return status == Status.PRELOADED || status == Status.GENERATING; }

    // ===== LIFECYCLE TRANSITIONS =====

    public void markPreloading() {
        this.status = Status.PRELOADING;
        this.preloadStartTime = System.currentTimeMillis();
        log.info("[SNAPSHOT] PREPARING generationId={} snapshotId={}", generationId, snapshotId);
    }

    public void markPreloaded() {
        this.status = Status.PRELOADED;
        this.preloadEndTime = System.currentTimeMillis();
        log.info("[SNAPSHOT] READY generationId={} snapshotId={} preloadTime={}ms dbQueries={} " +
                 "assignments={} groupMembers={} requirements={} timeSlots={} semesters={}",
                generationId, snapshotId, getPreloadTimeMs(), dbQueryCount,
                scopedAssignments != null ? scopedAssignments.size() : 0,
                allGroupMembers != null ? allGroupMembers.size() : 0,
                requirementsByCourse != null ? requirementsByCourse.values().stream().mapToInt(List::size).sum() : 0,
                timeSlots != null ? timeSlots.size() : 0,
                semesters != null ? semesters.size() : 0);
    }

    public void markGenerating() {
        this.status = Status.GENERATING;
        log.info("[SNAPSHOT] GENERATING generationId={} snapshotId={}", generationId, snapshotId);
    }

    public void markCompleted() {
        this.status = Status.COMPLETED;
        log.info("[SNAPSHOT] COMPLETED generationId={} snapshotId={}", generationId, snapshotId);
    }

    /** Keeps the snapshot associated with an ACTIVE generation after it completes. */
    public void keep() {
        log.info("[SNAPSHOT] KEEP generationId={} snapshotId={}", generationId, snapshotId);
    }

    public void markFailed() {
        this.status = Status.FAILED;
        log.info("[SNAPSHOT] FAILED generationId={} snapshotId={}", generationId, snapshotId);
    }

    // ===== DATA POPULATION (called during preload) =====

    public void setAllAssignments(List<TeachingAssignment> all) {
        this.allAssignments = all;
        this.totalAssignmentsLoaded = all.size();
    }

    public void setScopedAssignments(List<TeachingAssignment> scoped) {
        this.scopedAssignments = scoped;
    }

    public void setScopedSingletons(List<TeachingAssignment> singletons) {
        this.scopedSingletons = singletons;
    }

    public void setAllGroupMembers(List<TeachingAssignmentGroupMember> members) {
        this.allGroupMembers = members;
        this.totalGroupMembersLoaded = members.size();
    }

    public void setScopedMembersByGroup(Map<UUID, List<TeachingAssignmentGroupMember>> membersByGroup) {
        this.scopedMembersByGroup = membersByGroup;
    }

    public void setScopedGroupedAssignmentIds(Set<UUID> ids) {
        this.scopedGroupedAssignmentIds = ids;
    }

    public void setRequirementsByCourse(Map<UUID, List<CourseMeetingRequirement>> reqs) {
        this.requirementsByCourse = reqs;
        this.totalRequirementsLoaded = reqs.values().stream().mapToInt(List::size).sum();
    }

    public void setTimeSlots(List<TimeSlot> slots) {
        this.timeSlots = slots;
    }

    public void setSections(List<Section> sections) {
        this.sections = sections;
    }

    public void setSemesters(List<Semester> semesters) {
        this.semesters = semesters;
        this.semesterById.clear();
        for (Semester s : semesters) {
            semesterById.put(s.getSemesterId(), s);
        }
    }

    public void setScope(Map<UUID, Set<UUID>> scope) {
        this.scope = scope;
    }

    public void incrementDbQueryCount(int n) {
        this.dbQueryCount += n;
    }

    // ===== GETTERS =====

    public UUID getSnapshotId() { return snapshotId; }
    public UUID getTermId() { return termId; }
    public UUID getExamTypeId() { return examTypeId; }
    public UUID getGenerationId() { return generationId; }
    public Status getStatus() { return status; }
    public Map<UUID, Set<UUID>> getScope() { return scope; }
    public List<TimeSlot> getTimeSlots() { return timeSlots; }
    public List<Section> getSections() { return sections; }
    public List<Semester> getSemesters() { return semesters; }
    public List<TeachingAssignment> getAllAssignments() { return allAssignments; }
    public List<TeachingAssignment> getScopedAssignments() { return scopedAssignments; }
    public List<TeachingAssignment> getScopedSingletons() { return scopedSingletons; }
    public List<TeachingAssignmentGroupMember> getAllGroupMembers() { return allGroupMembers; }
    public Map<UUID, List<TeachingAssignmentGroupMember>> getScopedMembersByGroup() { return scopedMembersByGroup; }
    public Set<UUID> getScopedGroupedAssignmentIds() { return scopedGroupedAssignmentIds; }
    public Map<UUID, List<CourseMeetingRequirement>> getRequirementsByCourse() { return requirementsByCourse; }
    public long getPreloadTimeMs() { return preloadEndTime - preloadStartTime; }
    public int getDbQueryCount() { return dbQueryCount; }
    public int getTotalAssignmentsLoaded() { return totalAssignmentsLoaded; }
    public int getTotalGroupMembersLoaded() { return totalGroupMembersLoaded; }
    public int getTotalRequirementsLoaded() { return totalRequirementsLoaded; }
    public LinkedHashMap<UUID, SemesterPartition> getSemesterPartitions() { return semesterPartitions; }
    public SemesterPartition getPartition(UUID semesterId) { return semesterPartitions.get(semesterId); }
    public Semester getSemester(UUID semesterId) { return semesterById.get(semesterId); }

    /**
     * Partition preloaded data into per-semester structures.
     * Called ONCE after preload completes. Each partition contains ONLY the
     * data needed for that semester's generation — no cross-semester contamination.
     */
    public void partitionBySemester() {
        semesterPartitions.clear();

        for (UUID semId : scope.keySet()) {
            Semester sem = semesterById.get(semId);
            if (sem == null) {
                log.warn("Semester {} in scope but not found in semesters table, skipping", semId);
                continue;
            }

            Set<UUID> scopeSections = scope.get(semId);
            SemesterPartition p = new SemesterPartition(semId, sem.getSemesterNo(), scopeSections);

            // Partition assignments to this semester
            for (TeachingAssignment a : scopedAssignments) {
                UUID aSemId = a.getCourse().getSemester() != null
                        ? a.getCourse().getSemester().getSemesterId() : null;
                if (!semId.equals(aSemId)) continue;
                if (scopeSections != null && !scopeSections.contains(a.getSection().getSectionId())) continue;
                p.addAssignment(a);
                if (!scopedGroupedAssignmentIds.contains(a.getAssignmentId())) {
                    p.addSingleton(a);
                }
            }

            // Partition group members to this semester
            for (Map.Entry<UUID, List<TeachingAssignmentGroupMember>> e : scopedMembersByGroup.entrySet()) {
                List<TeachingAssignmentGroupMember> semMembers = e.getValue().stream()
                        .filter(m -> {
                            UUID mSemId = m.getGroup().getCourse().getSemester() != null
                                    ? m.getGroup().getCourse().getSemester().getSemesterId() : null;
                            if (!semId.equals(mSemId)) return false;
                            if (scopeSections != null
                                    && !scopeSections.contains(m.getAssignment().getSection().getSectionId()))
                                return false;
                            return true;
                        })
                        .toList();
                if (!semMembers.isEmpty()) {
                    p.addGroupMembers(e.getKey(), semMembers);
                    for (TeachingAssignmentGroupMember m : semMembers) {
                        p.addGroupedAssignmentId(m.getAssignment().getAssignmentId());
                    }
                }
            }

            // Partition CMRs to this semester's courses
            Set<UUID> semCourseIds = new HashSet<>();
            for (TeachingAssignment a : p.getAssignments()) {
                semCourseIds.add(a.getCourse().getCourseId());
            }
            for (List<TeachingAssignmentGroupMember> members : p.getMembersByGroup().values()) {
                semCourseIds.add(members.get(0).getGroup().getCourse().getCourseId());
            }
            for (UUID courseId : semCourseIds) {
                List<CourseMeetingRequirement> reqs = requirementsByCourse.get(courseId);
                if (reqs != null) {
                    p.addRequirements(courseId, reqs);
                }
            }

            semesterPartitions.put(semId, p);
        }

        logPartitionSummary();
    }

    private void logPartitionSummary() {
        log.info("=== PARTITION SUMMARY (generation={}) ===", generationId);
        for (Map.Entry<UUID, SemesterPartition> e : semesterPartitions.entrySet()) {
            SemesterPartition p = e.getValue();
            log.info("  Sem-{}: assignments={}, singletons={}, combineGroups={}, " +
                     "groupedAssignments={}, courseIds={}, requiredSections={}",
                    p.getSemesterNo(),
                    p.getAssignments().size(),
                    p.getSingletons().size(),
                    p.getMembersByGroup().size(),
                    p.getGroupedAssignmentIds().size(),
                    p.getCourseIds().size(),
                    p.getScopeSections() == null ? "ALL" : p.getScopeSections().size());
        }
        log.info("=== END PARTITION SUMMARY ===");
    }

    // ===== SEMESTER PARTITION (inner class) =====

    /**
     * Contains ONLY the data needed for one semester's generation.
     * Built by partitioning the snapshot's preloaded data.
     * No cross-semester data leaks into a partition.
     */
    public static class SemesterPartition {
        private final UUID semesterId;
        private final int semesterNo;
        private final Set<UUID> scopeSections; // null = all sections

        private final List<TeachingAssignment> assignments = new ArrayList<>();
        private final List<TeachingAssignment> singletons = new ArrayList<>();
        private final Map<UUID, List<TeachingAssignmentGroupMember>> membersByGroup = new LinkedHashMap<>();
        private final Set<UUID> groupedAssignmentIds = new HashSet<>();
        private final Map<UUID, List<CourseMeetingRequirement>> requirementsByCourse = new HashMap<>();

        public SemesterPartition(UUID semesterId, int semesterNo, Set<UUID> scopeSections) {
            this.semesterId = semesterId;
            this.semesterNo = semesterNo;
            this.scopeSections = scopeSections;
        }

        public UUID getSemesterId() { return semesterId; }
        public int getSemesterNo() { return semesterNo; }
        public Set<UUID> getScopeSections() { return scopeSections; }
        public List<TeachingAssignment> getAssignments() { return assignments; }
        public List<TeachingAssignment> getSingletons() { return singletons; }
        public Map<UUID, List<TeachingAssignmentGroupMember>> getMembersByGroup() { return membersByGroup; }
        public Set<UUID> getGroupedAssignmentIds() { return groupedAssignmentIds; }
        public Map<UUID, List<CourseMeetingRequirement>> getRequirementsByCourse() { return requirementsByCourse; }

        public Set<UUID> getCourseIds() {
            Set<UUID> ids = new HashSet<>();
            for (TeachingAssignment a : assignments) ids.add(a.getCourse().getCourseId());
            for (List<TeachingAssignmentGroupMember> m : membersByGroup.values()) {
                ids.add(m.get(0).getGroup().getCourse().getCourseId());
            }
            return ids;
        }

        void addAssignment(TeachingAssignment a) { assignments.add(a); }
        void addSingleton(TeachingAssignment a) { singletons.add(a); }
        void addGroupMembers(UUID groupId, List<TeachingAssignmentGroupMember> members) {
            membersByGroup.put(groupId, members);
        }
        void addGroupedAssignmentId(UUID id) { groupedAssignmentIds.add(id); }
        void addRequirements(UUID courseId, List<CourseMeetingRequirement> reqs) {
            requirementsByCourse.put(courseId, reqs);
        }
    }
}
