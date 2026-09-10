package com.unicconnect.service;

import com.unicconnect.entity.Course;
import com.unicconnect.entity.CourseMeetingRequirement;
import com.unicconnect.entity.Semester;
import com.unicconnect.entity.TeachingAssignment;
import com.unicconnect.entity.TeachingAssignmentGroup;
import com.unicconnect.entity.TeachingAssignmentGroupMember;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * SEPARATE experimental scheduler that measure whether adding FORWARD-CHECKING
 * to the CURRENT production scheduling strategy reduces search cost.
 *
 * It is test-only and NEVER persisted. It does NOT modify
 * {@link TimetableGenerationService}. It consumes the SAME input snapshot
 * (a {@link GenerationSnapshot.SemesterPartition}) and mirrors the production
 * selection and constraint model faithfully:
 *
 *   - structural priority (4x1 > mixed 2x1+1x2 > 2x2 > other)
 *   - combined-class anchor phase (Section A authoritative via one linked unit)
 *   - MRV (fewest candidate placements) ordering
 *   - deterministic tie-breaking (fixed seed)
 *   - section conflicts, lecturer conflicts (per-semester & per-section 6-bit masks)
 *   - same-course / same-day hard constraint
 *   - elective co-location (established group windows -> forced windows)
 *   - five valid 2-period windows (P1-P2 .. P5-P6) plus P3-P4 lunch-bridge fallback
 *
 * The ONLY algorithmic difference between the two modes is forward-checking:
 * after a candidate passes the immediate hard constraints, it is temporarily
 * applied and every affected unscheduled unit's domain is re-checked; if any
 * has ZERO legal candidates, the candidate is rejected immediately (and the
 * temporary placement is undone). This is expected behaviour, not a failure:
 * FC deliberately prunes locally-legal candidates that would make a future
 * unit impossible.
 */
public class TimetableSchedulingExperiment {

    public static final int WORKING_DAY_START = 1;
    public static final int WORKING_DAY_END = 5;
    public static final int PERIODS_PER_DAY = 6;

    // ================= immutable unit model =================

    public static final class ExpUnit {
        public final String label;
        public final Object ownerKey;          // TeachingAssignmentGroup (combined) or TeachingAssignment
        public final Set<UUID> staffIds;
        public final Set<UUID> sectionIds;
        public final int sessionsPerWeek;
        public final int periodsPerSession;
        public final int structuralPriority;
        public final UUID semesterId;
        public final String electiveGroup;     // null for non-elective / combined
        public final UUID anchorSectionId;
        public final boolean combined;
        public final boolean elective;
        public final Object keySection;        // ownerKey + anchor/first section -> usedDays key
        public final String courseCode;
        public final int cmrSessions;          // sessions recorded (== periodsPerSession? no) kept for diagnostics

        ExpUnit(String label, Object ownerKey, Set<UUID> staffIds, Set<UUID> sectionIds,
                int spw, int pps, int structuralPriority, UUID semesterId,
                String electiveGroup, UUID anchorSectionId, boolean combined,
                String courseCode) {
            this.label = label;
            this.ownerKey = ownerKey;
            this.staffIds = staffIds;
            this.sectionIds = sectionIds;
            this.sessionsPerWeek = spw;
            this.periodsPerSession = pps;
            this.structuralPriority = structuralPriority;
            this.semesterId = semesterId;
            this.electiveGroup = electiveGroup;
            this.anchorSectionId = anchorSectionId;
            this.combined = combined;
            this.elective = electiveGroup != null;
            this.keySection = ownerKey + "|" + (combined ? anchorSectionId : sectionIds.iterator().next());
            this.courseCode = courseCode;
            this.cmrSessions = spw;
        }

        /** Human-readable "Section X / COURSE / n×m" for diagnostics. */
        public String describe() {
            return label + " [" + sessionsPerWeek + "x" + periodsPerSession + "]";
        }
    }

    // ================= grid (faithful ConflictGrid port) =================

    private static final class SectionOcc { int mask, start, end; String groupKey; }

    public static final class Grid {
        private final Map<UUID, Map<Integer, Integer>> staff = new HashMap<>();
        private final Map<UUID, Map<UUID, Map<Integer, Map<Object, SectionOcc>>>> sections = new HashMap<>();
        private final Map<UUID, Map<UUID, Map<String, Map<Integer, int[]>>>> groupWindows = new HashMap<>();
        private final Map<UUID, Map<UUID, Map<Integer, Map<Integer, String>>>> staffGroupKeys = new HashMap<>();

        private static int periodMask(int s, int e) { int m = 0; for (int p = s; p <= e; p++) m |= (1 << (p - 1)); return m; }

        boolean canPlace(Set<UUID> staffIds, Set<UUID> sectionIds, UUID semId,
                         int day, int start, int end, String electiveGroup) {
            int mask = periodMask(start, end);
            for (UUID id : staffIds) {
                int cm = staff.getOrDefault(id, Map.of()).getOrDefault(day, 0) & mask;
                if (cm != 0) {
                    if (electiveGroup == null) return false;
                    boolean allowed = false;
                    Map<UUID, Map<Integer, Map<Integer, String>>> bySec = staffGroupKeys.get(id);
                    if (bySec != null) {
                        for (UUID secId : sectionIds) {
                            Map<Integer, Map<Integer, String>> byDay = bySec.get(secId);
                            if (byDay == null) continue;
                            Map<Integer, String> gk = byDay.get(day);
                            if (gk == null) continue;
                            boolean allSame = true;
                            for (int p = start; p <= end; p++)
                                if ((cm & (1 << (p - 1))) != 0 && !electiveGroup.equals(gk.get(p))) { allSame = false; break; }
                            if (allSame) { allowed = true; break; }
                        }
                    }
                    if (!allowed) return false;
                }
            }
            Map<UUID, Map<Integer, Map<Object, SectionOcc>>> bySem = sections.get(semId);
            if (bySem != null) {
                for (UUID id : sectionIds) {
                    Map<Integer, Map<Object, SectionOcc>> byDay = bySem.get(id);
                    if (byDay == null) continue;
                    Map<Object, SectionOcc> occ = byDay.get(day);
                    if (occ == null) continue;
                    for (SectionOcc o : occ.values()) {
                        if ((o.mask & mask) == 0) continue;
                        if (electiveGroup == null || !electiveGroup.equals(o.groupKey)
                                || o.start != start || o.end != end) return false;
                    }
                }
            }
            return true;
        }

        int[] forcedWindow(String electiveGroup, UUID sectionId, UUID semId, int occurrence) {
            Map<UUID, Map<String, Map<Integer, int[]>>> bySec = groupWindows.get(semId);
            if (bySec == null) return null;
            Map<String, Map<Integer, int[]>> byGroup = bySec.get(sectionId);
            if (byGroup == null) return null;
            Map<Integer, int[]> occ = byGroup.get(electiveGroup);
            return occ != null ? occ.get(occurrence) : null;
        }

        void place(Set<UUID> staffIds, Set<UUID> sectionIds, UUID semId,
                   int day, int start, int end, String electiveGroup, Object unitKey, int occurrence) {
            int mask = periodMask(start, end);
            for (UUID id : staffIds) {
                staff.computeIfAbsent(id, k -> new HashMap<>()).merge(day, mask, (a, b) -> a | b);
                if (electiveGroup != null) {
                    for (UUID secId : sectionIds) {
                        Map<Integer, String> gk = staffGroupKeys
                                .computeIfAbsent(id, k -> new HashMap<>())
                                .computeIfAbsent(secId, k -> new HashMap<>())
                                .computeIfAbsent(day, k -> new HashMap<>());
                        for (int p = start; p <= end; p++) gk.put(p, electiveGroup);
                    }
                }
            }
            for (UUID secId : sectionIds) {
                SectionOcc so = new SectionOcc();
                so.mask = mask; so.start = start; so.end = end; so.groupKey = electiveGroup;
                sections.computeIfAbsent(semId, k -> new HashMap<>())
                        .computeIfAbsent(secId, k -> new HashMap<>())
                        .computeIfAbsent(day, k -> new LinkedHashMap<>())
                        .put(unitKey, so);
                if (electiveGroup != null) {
                    groupWindows.computeIfAbsent(semId, k -> new HashMap<>())
                            .computeIfAbsent(secId, k -> new HashMap<>())
                            .computeIfAbsent(electiveGroup, k -> new HashMap<>())
                            .put(occurrence, new int[]{day, start, end});
                }
            }
        }

        void unplace(Set<UUID> staffIds, Set<UUID> sectionIds, UUID semId,
                     int day, int start, int end, String electiveGroup, Object unitKey) {
            int mask = periodMask(start, end);
            for (UUID id : staffIds) {
                Map<Integer, Integer> d = staff.get(id);
                if (d != null) {
                    int next = d.getOrDefault(day, 0) & ~mask;
                    if (next == 0) { d.remove(day); if (d.isEmpty()) staff.remove(id); } else d.put(day, next);
                }
                Map<UUID, Map<Integer, Map<Integer, String>>> bySec = staffGroupKeys.get(id);
                if (bySec != null) {
                    for (UUID secId : sectionIds) {
                        Map<Integer, Map<Integer, String>> byDay = bySec.get(secId);
                        if (byDay == null) continue;
                        Map<Integer, String> gk = byDay.get(day);
                        if (gk == null) continue;
                        for (int p = start; p <= end; p++) gk.remove(p);
                    }
                }
            }
            for (UUID secId : sectionIds) {
                Map<Integer, Map<Object, SectionOcc>> byDay = sections.getOrDefault(semId, Map.of()).get(secId);
                if (byDay != null) {
                    Map<Object, SectionOcc> occ = byDay.get(day);
                    if (occ != null) { occ.remove(unitKey); if (occ.isEmpty()) byDay.remove(day); }
                }
            }
        }
    }

    // ================= result / metrics =================

    public static final class Result {
        public boolean success;
        public long runtimeMs;
        public long nodes;
        public long canPlaceCalls;
        public long backtracks;
        public long placements;
        public long candidateCount;        // total candidate placements generated
public long fcCalls;               // forward-check invocations
    public long fcRejections;          // candidates rejected by forward-check
    public long fcAccepted;            // candidates accepted by forward-check (no zero domain)
    public long zeroDomainDetections;  // distinct unscheduled units found with zero domain
        public int maxDepth;
        public long cacheHits;
        public long cacheMisses;
        public long electiveCombos;
        public boolean iterationLimitReached;
        public boolean timeout;
        public String failureCategory;

        // Sem-1 diagnostic details (populated only for the forward-check run)
        public long firstBacktrackNode;
        public String mostBacktrackedUnit;
        public String smallestDomainUnit;
        public String fcZeroUnitLabel;     // unit whose domain dropped to zero
        public String fcZeroCourse;
        public String fcZeroSection;
        public boolean fcZeroTwoPeriod;
        public boolean fcZeroCombined;
        public boolean fcZeroLecturer;     // not directly attributable; diagnostic best-effort
        public boolean fcZeroElective;
        public long fcZeroUnitCount;

        public String metricsLine() {
            return String.format("result=%s runtime=%dms nodes=%d canPlace=%d backtracks=%d placements=%d "
                    + "candidates=%d fcCalls=%d fcRejects=%d zeroDomains=%d maxDepth=%d cacheHit=%d cacheMiss=%d "
                    + "electives=%d iterLimit=%s timeout=%s failure=%s",
                    success ? "SOLVED" : "FAIL", runtimeMs, nodes, canPlaceCalls, backtracks, placements,
                    candidateCount, fcCalls, fcRejections, zeroDomainDetections, maxDepth, cacheHits, cacheMisses,
                    electiveCombos, iterationLimitReached, timeout, failureCategory);
        }
    }

    // ================= unit construction from a partition =================

    public static List<ExpUnit> buildUnits(Map<UUID, List<CourseMeetingRequirement>> requirementsByCourse,
                                           Map<UUID, List<TeachingAssignmentGroupMember>> membersByGroup,
                                           List<TeachingAssignment> singletons, UUID semesterId) {
        List<ExpUnit> units = new ArrayList<>();
        Map<UUID, Integer> priorityByCourse = new HashMap<>();

        for (List<TeachingAssignmentGroupMember> members : membersByGroup.values()) {
            TeachingAssignmentGroup group = members.get(0).getGroup();
            Course course = group.getCourse();
            Semester sem = course.getSemester();
            if (sem == null || !sem.getSemesterId().equals(semesterId)) continue;
            Set<UUID> staffIds = new HashSet<>();
            Set<UUID> sectionIds = new HashSet<>();
            for (TeachingAssignmentGroupMember m : members) {
                staffIds.add(m.getAssignment().getStaff().getStaffId());
                sectionIds.add(m.getAssignment().getSection().getSectionId());
            }
            if (sectionIds.isEmpty()) continue;
            String anchorName = sectionIds.stream().map(id -> nameOf(members, id)).min(String::compareTo).orElseThrow();
            UUID anchorId = sectionIds.stream().filter(id -> nameOf(members, id).equals(anchorName)).findFirst().orElseThrow();
            int sp = priorityByCourse.computeIfAbsent(course.getCourseId(),
                    k -> classifyShape(requirementsByCourse.getOrDefault(course.getCourseId(), List.of())));
            String label = "Combined " + sectionLabel(members) + " / " + course.getCourseCode();
            for (CourseMeetingRequirement req : requirementsByCourse.getOrDefault(course.getCourseId(), List.of()))
                units.add(new ExpUnit(label + " / " + req.getMeetingType(),
                        group, staffIds, sectionIds, req.getSessionsPerWeek(), req.getPeriodsPerSession(),
                        sp, semesterId, null, anchorId, true, course.getCourseCode()));
        }

        for (TeachingAssignment a : singletons) {
            Course course = a.getCourse();
            UUID semId = course.getSemester() != null ? course.getSemester().getSemesterId() : null;
            if (!semesterId.equals(semId)) continue;
            Set<UUID> staffIds = Set.of(a.getStaff().getStaffId());
            Set<UUID> sectionIds = Set.of(a.getSection().getSectionId());
            String eg = (!course.isRequired() && semId != null)
                    ? semId.toString() + "|" + a.getSection().getSectionId() : null;
            int sp = priorityByCourse.computeIfAbsent(course.getCourseId(),
                    k -> classifyShape(requirementsByCourse.getOrDefault(course.getCourseId(), List.of())));
            String label = course.getCourseCode() + "/" + a.getSection().getSectionName();
            for (CourseMeetingRequirement req : requirementsByCourse.getOrDefault(course.getCourseId(), List.of()))
                units.add(new ExpUnit(label + " / " + req.getMeetingType(),
                        a, staffIds, sectionIds, req.getSessionsPerWeek(), req.getPeriodsPerSession(),
                        eg != null ? 4 : sp, semesterId, eg, a.getSection().getSectionId(), false,
                        course.getCourseCode()));
        }

        units.sort(Comparator.comparingInt((ExpUnit u) -> u.combined ? 0 : 1)
                .thenComparingInt(u -> -u.periodsPerSession)
                .thenComparingInt(u -> -u.sessionsPerWeek));
        return units;
    }

    private static String nameOf(List<TeachingAssignmentGroupMember> members, UUID id) {
        for (TeachingAssignmentGroupMember m : members)
            if (m.getAssignment().getSection().getSectionId().equals(id))
                return m.getAssignment().getSection().getSectionName();
        return "?";
    }
    private static String sectionLabel(List<TeachingAssignmentGroupMember> members) {
        StringBuilder sb = new StringBuilder();
        for (TeachingAssignmentGroupMember m : members)
            sb.append(m.getAssignment().getSection().getSectionName()).append("+");
        if (!members.isEmpty()) sb.setLength(sb.length() - 1);
        return sb.toString();
    }

    public static int classifyShape(List<CourseMeetingRequirement> reqs) {
        int oneS = 0, twoS = 0; boolean has2x1 = false, has1x2 = false, all1 = true;
        for (CourseMeetingRequirement r : reqs) {
            int spw = r.getSessionsPerWeek(), pps = r.getPeriodsPerSession();
            if (pps == 1) { oneS += spw; if (spw == 2) has2x1 = true; }
            else { all1 = false; if (pps == 2) { twoS += spw; if (spw == 1) has1x2 = true; } }
        }
        if (all1 && oneS >= 4) return 1;
        if (has2x1 && has1x2) return 2;
        if (twoS >= 2) return 3;
        return 4;
    }

    private static boolean consecutive(int[] startMinute, boolean[] lunchBridge, int startIdx, int perSession) {
        for (int i = startIdx; i < startIdx + perSession - 1; i++) {
            if (startMinute[i + 1] - startMinute[i] != 60) {
                if (lunchBridge[i]) return true;      // P3-P4 bridge fallback
                return false;
            }
        }
        return true;
    }

    public static final class Placement { public int day; public int startOrder; public int endOrder; }

    private static List<Placement> placements(ExpUnit u, Set<Integer> usedDays,
                                              int[] startMinute, boolean[] lunchBridge, Grid g) {
        List<Placement> out = new ArrayList<>();
        int occurrence = usedDays.size() + 1;
        int[] forced = (u.elective && !u.sectionIds.isEmpty())
                ? g.forcedWindow(u.electiveGroup, u.sectionIds.iterator().next(), u.semesterId, occurrence) : null;
        if (forced != null && (forced[2] - forced[1] + 1) != u.periodsPerSession) forced = null;
        for (int day = WORKING_DAY_START; day <= WORKING_DAY_END; day++) {
            if (usedDays.contains(day)) continue;
            for (int si = 0; si + u.periodsPerSession <= PERIODS_PER_DAY; si++) {
                if (!consecutive(startMinute, lunchBridge, si, u.periodsPerSession)) continue;
                int s = si + 1, e = si + u.periodsPerSession;
                if (forced != null && (day != forced[0] || s != forced[1] || e != forced[2])) continue;
                if (!g.canPlace(u.staffIds, u.sectionIds, u.semesterId, day, s, e, u.electiveGroup)) continue;
                Placement p = new Placement(); p.day = day; p.startOrder = s; p.endOrder = e; out.add(p);
            }
        }
        return out;
    }

    /** Diagnostic counter for canPlace calls. */
    public static final class PlacementCounter { public long calls; }

    /** Diagnostic: count legal placements for a unit (root domain size), counting canPlace calls. */
    public static int domainSize(ExpUnit u, Set<Integer> usedDays,
                                 int[] startMinute, boolean[] lunchBridge, Grid g, PlacementCounter c) {
        return placementsFor(u, usedDays, startMinute, lunchBridge, g, c).size();
    }

    /** Diagnostic: list legal placements, counting canPlace calls. */
    public static List<Placement> placementsFor(ExpUnit u, Set<Integer> usedDays,
                                                int[] startMinute, boolean[] lunchBridge,
                                                Grid g, PlacementCounter c) {
        int occurrence = usedDays.size() + 1;
        int[] forced = (u.elective && !u.sectionIds.isEmpty())
                ? g.forcedWindow(u.electiveGroup, u.sectionIds.iterator().next(), u.semesterId, occurrence) : null;
        if (forced != null && (forced[2] - forced[1] + 1) != u.periodsPerSession) forced = null;
        List<Placement> out = new ArrayList<>();
        for (int day = WORKING_DAY_START; day <= WORKING_DAY_END; day++) {
            if (usedDays.contains(day)) continue;
            for (int si = 0; si + u.periodsPerSession <= PERIODS_PER_DAY; si++) {
                if (!consecutive(startMinute, lunchBridge, si, u.periodsPerSession)) continue;
                int s = si + 1, e = si + u.periodsPerSession;
                if (forced != null && (day != forced[0] || s != forced[1] || e != forced[2])) continue;
                c.calls++;
                if (!g.canPlace(u.staffIds, u.sectionIds, u.semesterId, day, s, e, u.electiveGroup)) continue;
                Placement p = new Placement(); p.day = day; p.startOrder = s; p.endOrder = e; out.add(p);
            }
        }
        return out;
    }

    // ================= runner =================

    public static Result run(List<ExpUnit> units, int[] startMinute, boolean[] lunchBridge,
                             boolean forwardCheck, long seed, long nodeLimitMs, long timeBudgetMs,
                             boolean collectDiagnostics, String semLabel) {
        Result r = new Result();
        Grid g = new Grid();
        Random rnd = new Random(seed);
        Map<Object, Set<Integer>> usedDaysByCourse = new HashMap<>();
        Map<ExpUnit, Integer> placedCounts = new HashMap<>();
        Map<String,Integer> dayLoads = new HashMap<>();
        long wall0 = System.currentTimeMillis();
        long deadline = wall0 + timeBudgetMs;

        // capacity precondition (mirror production checkCapacityPreconditions)
        Map<UUID, Integer> periodsBySection = new HashMap<>();
        Set<String> counted = new HashSet<>();
        for (ExpUnit u : units)
            for (UUID sId : u.sectionIds)
                if (counted.add(u.ownerKey + "|" + sId))
                    periodsBySection.merge(sId, u.sessionsPerWeek * u.periodsPerSession, Integer::sum);
        for (Map.Entry<UUID, Integer> e : periodsBySection.entrySet())
            if (e.getValue() > PERIODS_PER_DAY * WORKING_DAY_END) {
                r.success = false; r.failureCategory = "CAPACITY_OVERLOAD";
                return r;
            }

        M m = new M();
        DC cache = new DC(units);
        boolean ok = solveRec(units, cache, g, startMinute, lunchBridge, usedDaysByCourse, placedCounts,
                dayLoads, forwardCheck, rnd, m, nodeLimitMs, deadline, 0);

        r.success = ok;
        r.runtimeMs = System.currentTimeMillis() - wall0;
        r.nodes = m.nodes; r.canPlaceCalls = m.canPlace; r.backtracks = m.backtracks;
        r.placements = m.placements; r.candidateCount = m.candidates;
        r.fcCalls = m.fcCalls; r.fcRejections = m.fcRejections;
        r.zeroDomainDetections = m.zeroDomains; r.maxDepth = m.maxDepth;
        r.cacheHits = m.cacheHits; r.cacheMisses = m.cacheMisses;
        r.firstBacktrackNode = m.firstBacktrackNode;
        r.mostBacktrackedUnit = m.mostBacktrackedUnit;
        r.smallestDomainUnit = m.smallestDomainUnit;
        r.iterationLimitReached = !ok && !m.timedOut && m.zeroDomainAtRoot == 0;
        r.timeout = m.timedOut;
        if (!ok) {
            r.failureCategory = m.timedOut ? "TIME_LIMIT"
                    : (m.zeroDomainAtRoot > 0 ? "ZERO_DOMAIN" : "NODE_LIMIT");
        }
        if (collectDiagnostics) {
            r.fcZeroUnitLabel = m.fcZeroUnitLabel;
            r.fcZeroCourse = m.fcZeroCourse;
            r.fcZeroSection = m.fcZeroSection;
            r.fcZeroTwoPeriod = m.fcZeroTwoPeriod;
            r.fcZeroCombined = m.fcZeroCombined;
            r.fcZeroLecturer = m.fcZeroLecturer;
            r.fcZeroElective = m.fcZeroElective;
            r.fcZeroUnitCount = m.fcZeroUnitCount;
        }
        return r;
    }

    private static final class M {
long nodes, canPlace, backtracks, placements, candidates;
    long fcCalls, fcRejections, fcAccepted, zeroDomains, cacheHits, cacheMisses;
    int maxDepth; long firstBacktrackNode = -1;
    String mostBacktrackedUnit; long mostBacktracks = -1;
    String smallestDomainUnit; int smallestDomain = Integer.MAX_VALUE;
    int zeroDomainAtRoot;
    boolean timedOut;
    String fcZeroUnitLabel, fcZeroCourse, fcZeroSection;
    boolean fcZeroTwoPeriod, fcZeroCombined, fcZeroLecturer, fcZeroElective;
    long fcZeroUnitCount;
    // FC rejection trace (first 50 only)
    List<String> fcRejectionTraces = new ArrayList<String>(50);
    }

    /**
     * Domain cache with targeted invalidation, mirroring production's
     * incremental conflict checking. A unit's cached placement list is only
     * recomputed when a recent placement/unplacement touched one of its
     * sections, one of its staff, or its same-course (usedDays) key.
     */
    private static final class DC {
        private static final class Entry { List<Placement> p; boolean dirty = true; }
        private final Map<ExpUnit, Entry> map = new HashMap<>();
        private final List<ExpUnit> units;
        private final Set<UUID> touchedSections = new HashSet<>();
        private final Set<UUID> touchedStaff = new HashSet<>();
        private final Set<Object> touchedKeys = new HashSet<>();
        private boolean changed;

        DC(List<ExpUnit> units) {
            this.units = units;
            for (ExpUnit u : units) map.put(u, new Entry());
        }

        void record(Set<UUID> secs, Set<UUID> staff, Object key) {
            touchedSections.addAll(secs);
            touchedStaff.addAll(staff);
            if (key != null) touchedKeys.add(key);
            changed = true;
        }

        /** Mark dirty ONLY units whose section/staff/key overlaps the recent change. */
        void flush() {
            if (!changed) return;
            for (ExpUnit u : units) {
                if (intersects(u.sectionIds, touchedSections)
                        || intersects(u.staffIds, touchedStaff)
                        || touchedKeys.contains(u.keySection)) {
                    map.get(u).dirty = true;
                }
            }
            touchedSections.clear(); touchedStaff.clear(); touchedKeys.clear();
            changed = false;
        }

        private static boolean intersects(Set<UUID> a, Set<UUID> b) {
            if (a.size() < b.size()) { for (UUID x : a) if (b.contains(x)) return true; }
            else { for (UUID x : b) if (a.contains(x)) return true; }
            return false;
        }
    }

    private static boolean solveRec(List<ExpUnit> units, DC cache, Grid g, int[] startMinute, boolean[] lunchBridge,
                                    Map<Object, Set<Integer>> usedDaysByCourse,
                                    Map<ExpUnit, Integer> placedCounts, Map<String,Integer> dayLoads,
                                    boolean forwardCheck, Random rnd, M m, long nodeLimit, long deadline,
                                    int depth) {
        if (depth > m.maxDepth) m.maxDepth = depth;
        if (m.nodes >= nodeLimit) return false;
        if (System.currentTimeMillis() >= deadline) { m.timedOut = true; return false; }
        cache.flush();

        // ---- production-faithful selection: combined-anchor phase, then
        // structural priority, then MRV (fewest options). Zero-domain returns.
        boolean anyUnplacedCombined = false;
        ExpUnit anchorCandidate = null; int minCombinedOpts = Integer.MAX_VALUE;
        ExpUnit best = null; int bestPriority = Integer.MAX_VALUE;
        boolean bestCombined = false; int minOptions = Integer.MAX_VALUE;
        List<Placement> bestOpts = null;

        for (ExpUnit u : units) {
            if (placedCounts.getOrDefault(u, 0) >= u.sessionsPerWeek) continue;
            int priority = u.structuralPriority;
            boolean combined = u.combined;
            Set<Integer> usedDays = usedDaysByCourse.getOrDefault(u.keySection, new HashSet<>());
            List<Placement> opts = cachedDomain(cache, u, usedDays, startMinute, lunchBridge, g, m);
            m.candidates += opts.size();
            if (opts.isEmpty()) {
                if (depth == 0) m.zeroDomainAtRoot++;
                return false;                       // zero-domain unit -> backtrack
            }
            if (combined) {
                anyUnplacedCombined = true;
                if (opts.size() < minCombinedOpts) { anchorCandidate = u; minCombinedOpts = opts.size(); }
            }
            if (opts.size() < m.smallestDomain) { m.smallestDomain = opts.size(); m.smallestDomainUnit = u.describe(); }
            if (best == null || priority < bestPriority
                    || (priority == bestPriority && combined && !bestCombined)
                    || (priority == bestPriority && combined == bestCombined && opts.size() < minOptions)) {
                best = u; bestPriority = priority; bestOpts = opts; minOptions = opts.size(); bestCombined = combined;
            }
        }
        if (best == null) return true;              // all placed

        ExpUnit selected = (anyUnplacedCombined && anchorCandidate != null) ? anchorCandidate : best;
        List<Placement> opts = (selected == best) ? bestOpts
                : cachedDomain(cache, selected, usedDaysByCourse.getOrDefault(selected.keySection, new HashSet<>()),
                        startMinute, lunchBridge, g, m);

        // soft order: day-balance then seeded randomized ties (comparator-safe keys)
        Map<Placement, Integer> keys = new HashMap<>();
        for (Placement p : opts) keys.put(p, rnd.nextInt());
        List<Placement> ordered = new ArrayList<>(opts);
        ordered.sort(Comparator.comparingInt((Placement p) -> sectionLoad(selected, p.day, dayLoads))
                .thenComparingInt(p -> keys.get(p)));

        for (Placement p : ordered) {
            Set<Integer> usedDays = usedDaysByCourse.computeIfAbsent(selected.keySection, k -> new HashSet<>());
            if (usedDays.contains(p.day)) continue;
            int occ = usedDays.size() + 1;

            // ---------- FORWARD-CHECK ORACLE (the only difference) ----------
            if (forwardCheck) {
                m.fcCalls++;
                apply(g, selected, p, occ, usedDays, placedCounts, dayLoads, cache);
                ExpUnit zero = null;
                for (ExpUnit o : units) {
                    if (o == selected || placedCounts.getOrDefault(o, 0) >= o.sessionsPerWeek) continue;
                    Set<Integer> ou = usedDaysByCourse.getOrDefault(o.keySection, new HashSet<>());
                    if (placements(o, ou, startMinute, lunchBridge, g).isEmpty()) {
                        zero = o; m.zeroDomains++;
                        if (m.fcZeroUnitLabel == null) {
                            m.fcZeroUnitLabel = o.describe();
                            m.fcZeroCourse = o.courseCode;
                            m.fcZeroSection = o.sectionIds.size() > 1 ? "A+B+C" : sectionOf(o);
                            m.fcZeroTwoPeriod = o.periodsPerSession == 2;
                            m.fcZeroCombined = o.combined;
                            m.fcZeroElective = o.elective;
                            m.fcZeroLecturer = false; // not directly isolated here
                        }
                        m.fcZeroUnitCount++;
                        break;
                    }
                }
                unapply(g, selected, p, usedDays, placedCounts, dayLoads, cache);
                if (zero != null) { m.fcRejections++;
                    // Capture trace (first 50 only)
                    if (m.fcRejectionTraces.size() < 50) {
                        // Compute "before" domain size: evaluate placements before temporary placement
                        Set<Integer> beforeUsedDays = usedDaysByCourse.getOrDefault(zero.keySection, new HashSet<>());
                        int beforeDomainSize = placements(zero, beforeUsedDays, startMinute, lunchBridge, g).size();
                        String trace = String.format("node=%d placed=%s candDay=%d candS=%d candE=%d affected=%s domBefore=%d domAfter=0 rejected=true reason=zero-domain",
                                m.nodes, selected.describe(), p.day, p.startOrder, p.endOrder, zero.describe(), beforeDomainSize);
                        m.fcRejectionTraces.add(trace);
                    }
                    continue;
                }
                else { m.fcAccepted++; }
            }
            // -----------------------------------------------------------------

            apply(g, selected, p, occ, usedDays, placedCounts, dayLoads, cache);
            m.nodes++; m.placements++;

            if (solveRec(units, cache, g, startMinute, lunchBridge, usedDaysByCourse, placedCounts,
                    dayLoads, forwardCheck, rnd, m, nodeLimit, deadline, depth + 1)) return true;

            unapply(g, selected, p, usedDays, placedCounts, dayLoads, cache);
            m.backtracks++;
            if (m.firstBacktrackNode == -1) m.firstBacktrackNode = m.nodes;
            if (m.mostBacktrackedUnit == null
                    || selected.courseCode.equals(m.mostBacktrackedUnit)) { /* placeholder */ }
        }
        return false;
    }

    private static void apply(Grid g, ExpUnit u, Placement p, int occ, Set<Integer> usedDays,
                              Map<ExpUnit, Integer> placedCounts, Map<String,Integer> dayLoads, DC cache) {
        g.place(u.staffIds, u.sectionIds, u.semesterId, p.day, p.startOrder, p.endOrder,
                u.electiveGroup, u.ownerKey, occ);
        usedDays.add(p.day);
        placedCounts.merge(u, 1, Integer::sum);
        adjustLoads(u, p.day, u.periodsPerSession, dayLoads, true);
        cache.record(u.sectionIds, u.staffIds, u.keySection);
        cache.flush();
    }

    private static void unapply(Grid g, ExpUnit u, Placement p, Set<Integer> usedDays,
                                Map<ExpUnit, Integer> placedCounts, Map<String,Integer> dayLoads, DC cache) {
        g.unplace(u.staffIds, u.sectionIds, u.semesterId, p.day, p.startOrder, p.endOrder,
                u.electiveGroup, u.ownerKey);
        usedDays.remove(p.day);
        int pc = placedCounts.merge(u, -1, Integer::sum);
        if (pc <= 0) placedCounts.remove(u);
        adjustLoads(u, p.day, -u.periodsPerSession, dayLoads, false);
        cache.record(u.sectionIds, u.staffIds, u.keySection);
        cache.flush();
    }

    private static void adjustLoads(ExpUnit u, int day, int delta, Map<String,Integer> dayLoads, boolean add) {
        for (UUID sec : u.sectionIds) {
            int nl = dayLoads.getOrDefault(day + "|" + sec, 0) + delta;
            if (nl <= 0) dayLoads.remove(day + "|" + sec); else dayLoads.put(day + "|" + sec, nl);
        }
    }

    private static List<Placement> cachedDomain(DC cache, ExpUnit u, Set<Integer> usedDays,
                                                int[] startMinute, boolean[] lunchBridge, Grid g, M m) {
        DC.Entry e = cache.map.get(u);
        if (!e.dirty) { m.cacheHits++; return e.p; }
        e.p = placements(u, usedDays, startMinute, lunchBridge, g);
        e.dirty = false;
        m.cacheMisses++;
        return e.p;
    }

    private static String sectionOf(ExpUnit u) {
        String label = u.label;
        int slash = label.indexOf('/');
        if (slash > 0) { String p = label.substring(0, slash).trim(); return p; }
        return "?";
    }

    private static int sectionLoad(ExpUnit u, int day, Map<String,Integer> dayLoads) {
        int s = 0; for (UUID sec : u.sectionIds) s += dayLoads.getOrDefault(day + "|" + sec, 0); return s;
    }
}
