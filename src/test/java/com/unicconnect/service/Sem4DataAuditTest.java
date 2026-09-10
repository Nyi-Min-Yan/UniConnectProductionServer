package com.unicconnect.service;

import com.unicconnect.entity.*;
import com.unicconnect.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@SpringBootTest
@ActiveProfiles("dev")
public class Sem4DataAuditTest {

    @Autowired private TeachingAssignmentRepository taRepo;
    @Autowired private AcademicTermRepository termRepo;
    @Autowired private SemesterRepository semRepo;
    @Autowired private StaffRepository staffRepo;
    @Autowired private CourseMeetingRequirementRepository cmrRepo;
    @Autowired private TeachingAssignmentGroupMemberRepository groupMemberRepo;
    @Autowired private TeachingAssignmentGroupRepository groupRepo;
    @Autowired private StaffPositionAssignmentRepository spaRepo;
    @Autowired private OrganizationalUnitRepository unitRepo;

    @Test
    @Transactional
    public void auditSem4() {
        AcademicTerm activeTerm = termRepo.findAll().stream()
                .filter(t -> t.getStatus() == TermStatus.ACTIVE)
                .findFirst().orElseThrow(() -> new RuntimeException("No active term"));
        UUID termId = activeTerm.getTermId();

        Map<UUID, Integer> semNoMap = semRepo.findAll().stream()
                .collect(Collectors.toMap(Semester::getSemesterId, Semester::getSemesterNo));
        Semester sem4 = semRepo.findAll().stream()
                .filter(s -> s.getSemesterNo() == 4).findFirst().orElseThrow();
        UUID sem4Id = sem4.getSemesterId();

        List<TeachingAssignment> all = taRepo.findWithDetailsByTermId(termId).stream()
                .filter(a -> a.getAssignmentStatus() != AssignmentStatus.CANCELLED)
                .filter(a -> a.getCourse().getSemester() != null
                        && a.getCourse().getSemester().getSemesterId().equals(sem4Id))
                .toList();

        // CMR map
        Map<UUID, List<CourseMeetingRequirement>> cmrByCourse = new HashMap<>();
        List<CourseMeetingRequirement> cmrs = cmrRepo.findAllByCourse_CourseIdIn(
                all.stream().map(a -> a.getCourse().getCourseId()).collect(Collectors.toSet()));
        for (CourseMeetingRequirement r : cmrs)
            cmrByCourse.computeIfAbsent(r.getCourse().getCourseId(), k -> new ArrayList<>()).add(r);

        // group membership
        Set<UUID> groupedIds = new HashSet<>();
        Map<UUID, List<TeachingAssignmentGroupMember>> membersByGroup = new LinkedHashMap<>();
        for (TeachingAssignmentGroupMember m : groupMemberRepo.findWithDetailsByTermId(termId)) {
            if (m.getAssignment().getCourse().getSemester() != null
                    && m.getAssignment().getCourse().getSemester().getSemesterId().equals(sem4Id)) {
                groupedIds.add(m.getAssignment().getAssignmentId());
                membersByGroup.computeIfAbsent(m.getGroup().getGroupId(), k -> new ArrayList<>()).add(m);
            }
        }

        System.out.println("================ A. COMPLETE SEM4 TEACHING ASSIGNMENT TABLE ================");
        System.out.printf("%-9s %-5s %-9s %-24s %-30s %-8s %-4s %-4s %-5s %-10s%n",
                "Course","Sec","Staff","StaffName","Unit","Mtg","Swk","Pps","Wk","GroupID");
        List<TeachingAssignment> sorted = new ArrayList<>(all);
        sorted.sort(Comparator.comparing((TeachingAssignment a) -> a.getCourse().getCourseCode())
                .thenComparing(a -> a.getSection().getSectionName()));
        for (TeachingAssignment a : sorted) {
            String unit = a.getStaff().getUnit() != null ? a.getStaff().getUnit().getUnitName() : "-";
            var cmrList = cmrByCourse.getOrDefault(a.getCourse().getCourseId(), List.of());
            String mtg = cmrList.stream().map(r -> r.getMeetingType().name()).collect(Collectors.joining("+"));
            int swk = cmrList.stream().mapToInt(CourseMeetingRequirement::getSessionsPerWeek).sum();
            int pps = cmrList.stream().mapToInt(CourseMeetingRequirement::getPeriodsPerSession).sum();
            int wk = cmrList.stream().mapToInt(r -> r.getSessionsPerWeek()*r.getPeriodsPerSession()).sum();
            String grp = membersByGroup.values().stream()
                    .filter(l -> l.stream().anyMatch(m -> m.getAssignment().getAssignmentId().equals(a.getAssignmentId())))
                    .map(l -> shorten(l.get(0).getGroup().getGroupId().toString()))
                    .findFirst().orElse("");
            System.out.printf("%-9s %-5s %-9s %-24s %-30.30s %-8s %-4d %-4d %-5d %-10s%n",
                    a.getCourse().getCourseCode(), a.getSection().getSectionName(),
                    a.getStaff().getStaffNo(), shorten(a.getStaff().getStaffName()), unit,
                    mtg, swk, pps, wk, grp);
        }

        System.out.println("\n================ C. COMBINED-GROUP VERIFICATION (Sem4) ================");
        for (Map.Entry<UUID, List<TeachingAssignmentGroupMember>> e : membersByGroup.entrySet()) {
            TeachingAssignmentGroup g = e.getValue().get(0).getGroup();
            System.out.println("\nGroup " + g.getGroupId() + " | " + g.getGroupName()
                    + " | course=" + g.getCourse().getCourseCode());
            for (TeachingAssignmentGroupMember m : e.getValue()) {
                TeachingAssignment a = m.getAssignment();
                var cmrList = cmrByCourse.getOrDefault(a.getCourse().getCourseId(), List.of());
                System.out.println("   member: assignment=" + a.getAssignmentId()
                        + " section=" + a.getSection().getSectionName() + "(" + a.getSection().getSectionId() + ")"
                        + " staff=" + a.getStaff().getStaffNo() + " " + a.getStaff().getStaffName()
                        + " CMR=" + cmrList.stream().map(r->r.getMeetingType()+" "+r.getSessionsPerWeek()+"x"+r.getPeriodsPerSession()).collect(Collectors.joining(" | ")));
            }
        }

        // ALL combined groups in all semesters for reference
        System.out.println("\n===== ALL COMBINED GROUPS (all semesters, term) =====");
        Map<UUID,Integer> semByCourse = new HashMap<>();
        for (TeachingAssignment a : all) semByCourse.put(a.getCourse().getCourseId(),
                semNoMap.get(a.getCourse().getSemester().getSemesterId()));
        for (TeachingAssignmentGroup g : groupRepo.findWithCourseByTermId(termId)) {
            Integer sem = g.getCourse().getSemester()!=null ? semNoMap.get(g.getCourse().getSemester().getSemesterId()) : null;
            var members = groupMemberRepo.findWithDetailsByGroupId(g.getGroupId());
            System.out.println("  Sem" + sem + " " + g.getCourse().getCourseCode() + " [" + g.getGroupName() + "] "
                    + members.stream().map(m->m.getAssignment().getSection().getSectionName()
                        + "("+m.getAssignment().getSection().getSectionId()+")").collect(Collectors.joining("+")));
        }

        System.out.println("\n================ A2. SEM4 COURSE DETAIL (per course, per section) ================");
        System.out.printf("%-9s %-5s %-9s %-7s %-12s %-5s %-5s %-5s %-16s%n","Course","Sem","Course","WeekPrd","CrsSessions","Labels","Swk","Pps","SumWk","Sections(CMR detail)");
        Map<String, List<TeachingAssignment>> byCourseSec = new TreeMap<>();
        for (TeachingAssignment a : sorted) {
            byCourseSec.computeIfAbsent(a.getCourse().getCourseCode()+"|"+a.getSection().getSectionName(), k->new ArrayList<>()).add(a);
        }
        for (Map.Entry<String,List<TeachingAssignment>> e : byCourseSec.entrySet()) {
            TeachingAssignment a = e.getValue().get(0);
            var cmrList = cmrByCourse.getOrDefault(a.getCourse().getCourseId(), List.of());
            String detail = cmrList.stream()
                .map(r -> r.getMeetingType().name().toLowerCase()+" "+r.getSessionsPerWeek()+"sw x "+r.getPeriodsPerSession()+"pp")
                .collect(Collectors.joining(" + "));
            int wk = cmrList.stream().mapToInt(r -> r.getSessionsPerWeek()*r.getPeriodsPerSession()).sum();
            String secs = String.join(",", e.getValue().stream().map(x->x.getSection().getSectionName()).collect(Collectors.toSet()));
            System.out.printf("%-9s %-5s %-9s %-7s %-12s %-5s %-5s %-5s %-5d %-16s%n",
                a.getCourse().getCourseCode(), a.getCourse().getSemester()!=null?a.getCourse().getSemester().getSemesterNo():"?",
                a.getCourse().getCourseName().substring(0,Math.min(9,a.getCourse().getCourseName().length())),
                wk, cmrList.isEmpty()?0:cmrList.size(), secs, 0, 0, wk, detail);
        }

        System.out.println("\n================ B. SEM4 LECTURER LOAD TABLE ================");
        System.out.printf("%-9s %-24s %-18s %-12s %-12s %-12s%n","Staff","Name","Unit","DistinctCrs","Sections","WeeklyPeriods");
        Map<String, Map<String,Object>> byStaff = new TreeMap<>();
        for (TeachingAssignment a : all) {
            String sn = a.getStaff().getStaffNo();
            byStaff.computeIfAbsent(sn, k -> new HashMap<>());
            Map<String,Object> m = byStaff.get(sn);
            m.put("name", a.getStaff().getStaffName());
            m.put("unit", a.getStaff().getUnit()!=null?a.getStaff().getUnit().getUnitName():"-");
            ((Set<String>)m.computeIfAbsent("courses", k->new TreeSet<>())).add(a.getCourse().getCourseCode());
            ((Set<String>)m.computeIfAbsent("sections", k->new TreeSet<>())).add(a.getCourse().getCourseCode()+a.getSection().getSectionName());
            int wk = cmrByCourse.getOrDefault(a.getCourse().getCourseId(), List.of()).stream()
                    .mapToInt(r->r.getSessionsPerWeek()*r.getPeriodsPerSession()).sum();
            m.put("week", ((int)m.getOrDefault("week",0)) + wk);
        }
        for (String sn : byStaff.keySet()) {
            Map<String,Object> m = byStaff.get(sn);
            System.out.printf("%-9s %-24s %-18.18s %-12s %-12s %-12s%n", sn, shorten((String)m.get("name")),
                    m.get("unit"), ((Set)m.get("courses")).size(), ((Set)m.get("sections")).size(), m.get("week"));
        }

        System.out.println("\n================ D. SEM4 SECTION CAPACITY ================");
        System.out.println("6 periods/day x 5 weekdays = 30 cells/week (no lunch cell).");
        for (String secName : List.of("A","B","C","CT")) {
            int week = 0;
            List<String> courses = new ArrayList<>();
            for (TeachingAssignment a : all) {
                if (!a.getSection().getSectionName().equals(secName)) continue;
                int wk = cmrByCourse.getOrDefault(a.getCourse().getCourseId(), List.of()).stream()
                        .mapToInt(r->r.getSessionsPerWeek()*r.getPeriodsPerSession()).sum();
                week += wk;
                courses.add(a.getCourse().getCourseCode());
            }
            System.out.printf("  Section %-2s: coursework periods=%d  capacity=30  remaining=%d  courses=%s%n",
                    secName, week, 30-week, String.join(",", courses));
        }

        System.out.println("\n================ E. ROOM CAPACITY NOTE ================");
        System.out.println("NO Room entity exists in the model. The scheduler does NOT track room occupancy.");
        System.out.println("TimeSlots are period slots only (periodNo, start/end). No room-type or capacity constraint is modeled.");

        System.out.println("\n================ H. SEM4 LECTURERS - FULL SEMESTER-LOAD + POSITION ================");
        Set<String> sem4Staff = all.stream().map(a->a.getStaff().getStaffNo()).collect(Collectors.toSet());
        for (String sn : sem4Staff) {
            Staff st = staffRepo.findAll().stream().filter(s->s.getStaffNo().equals(sn)).findFirst().orElse(null);
            if (st==null) continue;
            var spas = spaRepo.findByStaff_StaffId(st.getStaffId());
            String pos = spas.isEmpty() ? "NO-POSITION" : spas.get(0).getPosition().getPositionName();
            // build per-semester load
            Map<Integer, List<String>> semLoad = new TreeMap<>();
            for (TeachingAssignment a : all.stream()
                    .filter(x->x.getStaff().getStaffId().equals(st.getStaffId())).toList()) {
                int sem = semNoMap.get(a.getCourse().getSemester().getSemesterId());
                semLoad.computeIfAbsent(sem, k->new ArrayList<>()).add(a.getCourse().getCourseCode()+":"+a.getSection().getSectionName());
            }
            System.out.println("  " + sn + " " + st.getStaffName()
                    + " | pos=" + pos + " | unit=" + (st.getUnit()!=null?st.getUnit().getUnitName():"-"));
            for (Map.Entry<Integer,List<String>> e : semLoad.entrySet())
                System.out.println("      Sem"+e.getKey()+": "+String.join(", ", e.getValue()));
        }

        System.out.println("\n================ STAFF INVENTORY (all lecturer-pos staff, candidate pool) ================");
        Set<String> lecturerPosStaff = new TreeSet<>();
        for (StaffPositionAssignment spa : spaRepo.findAllWithPositionAndStaff()) {
            String p = spa.getPosition().getPositionName().toLowerCase();
            if (p.contains("lectur") || p.contains("tutor") || p.contains("instruct")) lecturerPosStaff.add(spa.getStaff().getStaffNo());
        }
        Map<String, Staff> staffByNo = new HashMap<>();
        for (Staff s : staffRepo.findAll()) staffByNo.put(s.getStaffNo(), s);
        System.out.printf("%-9s %-24s %-34.34s %-12s%n","Staff","Name","Unit","LecPos");
        for (String sn : lecturerPosStaff) {
            Staff s = staffByNo.get(sn);
            if (s==null) continue;
            System.out.printf("%-9s %-24s %-34.34s %-12s%n", sn, shorten(s.getStaffName()),
                    s.getUnit()!=null?s.getUnit().getUnitName():"-", "YES");
        }
    }

    private static String shorten(String s){ return s == null ? "-" : s; }
}
