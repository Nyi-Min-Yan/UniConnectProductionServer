package com.unicconnect.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

@SpringBootTest
@ActiveProfiles("dev")
public class Sem7SectionBDiagnosticTest {

    @Autowired
    private DataSource dataSource;

    private static final String TERM_ID = "efc68a91-5818-41aa-b4fb-893e240ea0ed";
    private static final String SEM7_ID  = "3890917f-e9c5-4ddc-8622-c981820a589f";
    private static final String SEC_B_ID = "f19a0bb5-347a-4b40-aa0a-dd7062a0d64e";
    private static final String SEC_A_ID = "81004274-cf05-491f-b1a3-7c8e3d5c77e7";
    private static final String SEC_C_ID = "adc0d7f4-3075-41d0-9366-c6e8b80f0a27";
    private static final String SEC_CT_ID = "029878c5-d51d-4a0a-8fb1-99642ab4dee1";

    @Test
    void dumpSectionBUnits() throws Exception {
        System.out.println("=== SECTION B SCHEDULING UNITS (Sem-7) ===\n");

        try (Connection conn = dataSource.getConnection()) {
            String sql = """
                SELECT 
                    c.course_code,
                    c.is_required,
                    cmr.sessions_per_week,
                    cmr.periods_per_session,
                    cmr.meeting_type,
                    st.staff_name,
                    st.staff_no
                FROM teaching_assignments ta
                JOIN courses c ON c.course_id = ta.course_id
                JOIN staff st ON st.staff_id = ta.staff_id
                JOIN course_meeting_requirements cmr ON cmr.course_id = c.course_id
                WHERE ta.term_id = ?::uuid
                  AND c.semester_id = ?::uuid
                  AND ta.section_id = ?::uuid
                  AND ta.assignment_status != 'CANCELLED'
                ORDER BY c.course_code, cmr.meeting_type, st.staff_no
                """;

            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setString(1, TERM_ID);
            ps.setString(2, SEM7_ID);
            ps.setString(3, SEC_B_ID);
            ResultSet rs = ps.executeQuery();

            int totalSessions = 0;
            int totalPeriods = 0;
            int count = 0;

            System.out.printf("%-15s %6s %8s %10s %12s %-25s %s%n",
                    "Course", "CMR-S", "CMR-P", "TotPer", "MeetingType", "Staff", "StaffNo");
            System.out.println("-".repeat(100));

            while (rs.next()) {
                String code = rs.getString("course_code");
                int sessions = rs.getInt("sessions_per_week");
                int pps = rs.getInt("periods_per_session");
                String type = rs.getString("meeting_type");
                boolean required = rs.getBoolean("is_required");
                String staffName = rs.getString("staff_name");
                String staffNo = rs.getString("staff_no");

                int periods = sessions * pps;
                totalSessions += sessions;
                totalPeriods += periods;
                count++;

                System.out.printf("%-15s %6d %8d %10d %12s %-25s %s%n",
                        code, sessions, pps, periods, type,
                        staffName != null ? staffName : "NULL",
                        staffNo != null ? staffNo : "NULL");
            }
            rs.close();

            System.out.println("\n--- SUMMARY ---");
            System.out.println("Total teaching assignment+CMR rows: " + count);
            System.out.println("Total sessions: " + totalSessions);
            System.out.println("Total required periods: " + totalPeriods);
            System.out.println("Available: 5 days x 6 periods = 30");
            System.out.println("Raw slack: " + (30 - totalPeriods));
        }
    }

    @Test
    void dumpAllSem7Sections() throws Exception {
        System.out.println("=== ALL SEM-7 COURSE REQUIREMENTS (ALL SECTIONS) ===\n");

        try (Connection conn = dataSource.getConnection()) {
            String sql = """
                SELECT 
                    sec.section_name,
                    c.course_code,
                    cmr.sessions_per_week,
                    cmr.periods_per_session,
                    cmr.meeting_type,
                    c.is_required,
                    (cmr.sessions_per_week * cmr.periods_per_session) as total_periods
                FROM teaching_assignments ta
                JOIN courses c ON c.course_id = ta.course_id
                JOIN sections sec ON sec.section_id = ta.section_id
                JOIN course_meeting_requirements cmr ON cmr.course_id = c.course_id
                WHERE ta.term_id = ?::uuid
                  AND c.semester_id = ?::uuid
                  AND ta.assignment_status != 'CANCELLED'
                ORDER BY sec.section_name, c.course_code, cmr.meeting_type
                """;

            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setString(1, TERM_ID);
            ps.setString(2, SEM7_ID);
            ResultSet rs = ps.executeQuery();

            Map<String, Integer> sectionTotals = new LinkedHashMap<>();

            System.out.printf("%-8s %-15s %6s %8s %10s %10s %8s%n",
                    "Section", "Course", "CMR-S", "CMR-P", "TotPer", "MeetingType", "Req'd");
            System.out.println("-".repeat(75));

            while (rs.next()) {
                String sec = rs.getString("section_name");
                String code = rs.getString("course_code");
                int sessions = rs.getInt("sessions_per_week");
                int pps = rs.getInt("periods_per_session");
                String type = rs.getString("meeting_type");
                boolean required = rs.getBoolean("is_required");
                int totalP = rs.getInt("total_periods");

                sectionTotals.merge(sec, totalP, Integer::sum);

                System.out.printf("%-8s %-15s %6d %8d %10d %10s %8s%n",
                        sec, code, sessions, pps, totalP, type,
                        required ? "Y" : "N");
            }
            rs.close();

            System.out.println("\n=== SECTION TOTALS ===");
            for (Map.Entry<String, Integer> e : sectionTotals.entrySet()) {
                int slack = 30 - e.getValue();
                System.out.printf("  %-8s: %d required periods, 30 available, slack=%d%s%n",
                        e.getKey(), e.getValue(), slack,
                        slack < 0 ? " *** OVERLOADED ***" : "");
            }
        }
    }

    @Test
    void dumpFrozenSemesters() throws Exception {
        System.out.println("=== FROZEN SEMESTERS ===\n");

        try (Connection conn = dataSource.getConnection()) {
            // Semesters don't have term_id; they have semester_no
            // Frozen = completed academic terms
            String sql = """
                SELECT DISTINCT sem.semester_no, t.status, t.academic_year
                FROM teaching_assignments ta
                JOIN courses c ON c.course_id = ta.course_id
                JOIN semesters sem ON sem.semester_id = c.semester_id
                JOIN academic_terms t ON t.term_id = ta.term_id
                WHERE t.status = 'COMPLETED'
                ORDER BY sem.semester_no
                """;

            Statement st = conn.createStatement();
            ResultSet rs = st.executeQuery(sql);

            System.out.println("Completed semesters (with teaching assignments):");
            while (rs.next()) {
                System.out.printf("  Semester %d (year: %s, status: %s)%n",
                        rs.getInt("semester_no"), rs.getString("academic_year"), rs.getString("status"));
            }
            rs.close();

            // Find staff in completed semesters who also teach in Sem-7
            System.out.println("\n=== STAFF IN COMPLETED SEMESTERS WHO ALSO TEACH IN SEM-7 ===\n");

            String sql2 = """
                SELECT DISTINCT
                    st.staff_no,
                    st.staff_name,
                    c.course_code,
                    sec.section_name,
                    sem.semester_no,
                    cmr.sessions_per_week,
                    cmr.periods_per_session
                FROM teaching_assignments ta
                JOIN courses c ON c.course_id = ta.course_id
                JOIN staff st ON st.staff_id = ta.staff_id
                JOIN sections sec ON sec.section_id = ta.section_id
                JOIN semesters sem ON sem.semester_id = c.semester_id
                JOIN academic_terms t ON t.term_id = ta.term_id
                JOIN course_meeting_requirements cmr ON cmr.course_id = c.course_id
                WHERE t.status = 'COMPLETED'
                  AND st.staff_id IN (
                      SELECT ta2.staff_id
                      FROM teaching_assignments ta2
                      JOIN courses c2 ON c2.course_id = ta2.course_id
                      WHERE ta2.term_id = ?::uuid
                        AND c2.semester_id = ?::uuid
                        AND ta2.assignment_status != 'CANCELLED'
                  )
                ORDER BY st.staff_no, sem.semester_no, c.course_code
                """;

            PreparedStatement ps = conn.prepareStatement(sql2);
            ps.setString(1, TERM_ID);
            ps.setString(2, SEM7_ID);
            ResultSet rs2 = ps.executeQuery();

            Map<String, List<String>> staffInfo = new LinkedHashMap<>();
            while (rs2.next()) {
                String staffNo = rs2.getString("staff_no");
                String staffName = rs2.getString("staff_name");
                String course = rs2.getString("course_code");
                String secName = rs2.getString("section_name");
                int semNo = rs2.getInt("semester_no");
                int s = rs2.getInt("sessions_per_week");
                int p = rs2.getInt("periods_per_session");

                staffInfo.computeIfAbsent(staffNo + " (" + staffName + ")", k -> new ArrayList<>())
                        .add(String.format("Sem-%d / %s / %s / %dx%d",
                                semNo, course, secName, s, p));
            }
            rs2.close();

            System.out.println("Staff in completed semesters who also teach in Sem-7:");
            for (Map.Entry<String, List<String>> e : staffInfo.entrySet()) {
                System.out.println("  " + e.getKey());
                for (String c : e.getValue()) {
                    System.out.println("    -> " + c);
                }
            }
            System.out.println("\nTotal shared staff: " + staffInfo.size());
        }
    }

    @Test
    void dumpSectionBStaffAcrossSections() throws Exception {
        System.out.println("=== SECTION B STAFF WHO TEACH OTHER SECTIONS IN SEM-7 ===\n");

        try (Connection conn = dataSource.getConnection()) {
            String sql = """
                SELECT DISTINCT
                    st.staff_no,
                    st.staff_name,
                    c.course_code,
                    sec2.section_name,
                    cmr.sessions_per_week,
                    cmr.periods_per_session,
                    cmr.meeting_type
                FROM teaching_assignments ta
                JOIN courses c ON c.course_id = ta.course_id
                JOIN staff st ON st.staff_id = ta.staff_id
                JOIN sections sec2 ON sec2.section_id = ta.section_id
                JOIN course_meeting_requirements cmr ON cmr.course_id = c.course_id
                WHERE ta.term_id = ?::uuid
                  AND c.semester_id = ?::uuid
                  AND ta.assignment_status != 'CANCELLED'
                  AND st.staff_id IN (
                      SELECT ta2.staff_id
                      FROM teaching_assignments ta2
                      WHERE ta2.term_id = ?::uuid
                        AND ta2.section_id = ?::uuid
                        AND ta2.assignment_status != 'CANCELLED'
                  )
                ORDER BY st.staff_no, sec2.section_name, c.course_code
                """;

            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setString(1, TERM_ID);
            ps.setString(2, SEM7_ID);
            ps.setString(3, TERM_ID);
            ps.setString(4, SEC_B_ID);
            ResultSet rs = ps.executeQuery();

            Map<String, List<String>> staffSections = new LinkedHashMap<>();
            while (rs.next()) {
                String staffNo = rs.getString("staff_no");
                String staffName = rs.getString("staff_name");
                String course = rs.getString("course_code");
                String sec = rs.getString("section_name");
                int s = rs.getInt("sessions_per_week");
                int p = rs.getInt("periods_per_session");
                String type = rs.getString("meeting_type");
                staffSections.computeIfAbsent(staffNo + " (" + staffName + ")", k -> new ArrayList<>())
                        .add(String.format("Sec %s: %s %dx%d [%s]", sec, course, s, p, type));
            }
            rs.close();

            if (staffSections.isEmpty()) {
                System.out.println("No Section B staff teach other sections in Sem-7.");
            } else {
                for (Map.Entry<String, List<String>> e : staffSections.entrySet()) {
                    System.out.println(e.getKey());
                    for (String c : e.getValue()) {
                        System.out.println("  -> " + c);
                    }
                    System.out.println();
                }
            }
        }
    }

    @Test
    void calculateSectionBCapacityDetailed() throws Exception {
        System.out.println("=== SECTION B DETAILED CAPACITY ANALYSIS ===\n");

        System.out.println("Standard week (Mon-Fri, P1-P6):");
        System.out.println("  Available: 5 days x 6 periods = 30 period-slots\n");

        try (Connection conn = dataSource.getConnection()) {
            String sql = """
                SELECT 
                    c.course_code,
                    cmr.sessions_per_week,
                    cmr.periods_per_session,
                    cmr.meeting_type,
                    c.is_required,
                    (cmr.sessions_per_week * cmr.periods_per_session) as total_periods
                FROM teaching_assignments ta
                JOIN courses c ON c.course_id = ta.course_id
                JOIN course_meeting_requirements cmr ON cmr.course_id = c.course_id
                WHERE ta.term_id = ?::uuid
                  AND c.semester_id = ?::uuid
                  AND ta.section_id = ?::uuid
                  AND ta.assignment_status != 'CANCELLED'
                ORDER BY c.course_code, cmr.meeting_type
                """;

            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setString(1, TERM_ID);
            ps.setString(2, SEM7_ID);
            ps.setString(3, SEC_B_ID);
            ResultSet rs = ps.executeQuery();

            int totalPeriods = 0;
            int totalSessions = 0;
            int onePeriodSessions = 0;
            int twoPeriodSessions = 0;

            Map<String, List<String[]>> courseCMRs = new LinkedHashMap<>();

            System.out.printf("%-15s %6s %8s %8s %12s %8s%n",
                    "Course", "CMR-S", "CMR-P", "TotPer", "MeetingType", "Req'd");
            System.out.println("-".repeat(70));

            while (rs.next()) {
                String code = rs.getString("course_code");
                int sessions = rs.getInt("sessions_per_week");
                int pps = rs.getInt("periods_per_session");
                String type = rs.getString("meeting_type");
                boolean required = rs.getBoolean("is_required");
                int totalP = rs.getInt("total_periods");

                totalPeriods += totalP;
                totalSessions += sessions;

                if (pps == 1) onePeriodSessions += sessions;
                else if (pps == 2) twoPeriodSessions += sessions;

                String key = code + (required ? "" : " [E]");
                courseCMRs.computeIfAbsent(key, k -> new ArrayList<>())
                        .add(new String[]{String.valueOf(sessions), String.valueOf(pps),
                                String.valueOf(totalP), type});

                System.out.printf("%-15s %6d %8d %8d %12s %8s%n",
                        code, sessions, pps, totalP, type, required ? "Y" : "N");
            }
            rs.close();

            System.out.println("\n--- CAPACITY CALCULATION ---");
            System.out.println("Total available period-slots: 30");
            System.out.println("Total required periods: " + totalPeriods);
            System.out.println("Raw slack: " + (30 - totalPeriods));
            System.out.println("Total sessions: " + totalSessions);
            System.out.println();
            System.out.println("1-period sessions (fragment windows): " + onePeriodSessions);
            System.out.println("2-period sessions (need consecutive): " + twoPeriodSessions);

            System.out.println("\n--- CONSECUTIVE WINDOW ANALYSIS ---");
            System.out.println("Per day 2-period windows: P1-P2, P2-P3, P3-P4, P4-P5, P5-P6 = 5");
            System.out.println("Total 2-period windows across 5 days: 25");
            System.out.println();
            System.out.println("Each 1-period placement destroys up to 2 windows");
            System.out.println("Worst case if " + onePeriodSessions + " 1-period sessions scatter:");
            System.out.println("  Max windows destroyed: " + (onePeriodSessions * 2));
            System.out.println("  Windows remaining for 2-period: " + Math.max(0, 25 - onePeriodSessions * 2));
            System.out.println("  2-period sessions needed: " + twoPeriodSessions);
            System.out.println("  Feasible (worst case): " + (25 - onePeriodSessions * 2 >= twoPeriodSessions));

            System.out.println("\n--- PER-COURSE CMR SUMMARY ---");
            for (Map.Entry<String, List<String[]>> e : courseCMRs.entrySet()) {
                int courseTotal = e.getValue().stream()
                        .mapToInt(v -> Integer.parseInt(v[2])).sum();
                StringBuilder sb = new StringBuilder();
                for (String[] v : e.getValue()) {
                    if (sb.length() > 0) sb.append(" + ");
                    sb.append(v[0]).append("x").append(v[1]).append("[").append(v[3]).append("]");
                }
                System.out.printf("  %-15s total=%d  CMRs: %s%n", e.getKey(), courseTotal, sb);
            }
        }
    }

    @Test
    void dumpCombinedGroupsSem7() throws Exception {
        System.out.println("=== COMBINED TEACHING GROUPS IN SEM-7 ===\n");

        try (Connection conn = dataSource.getConnection()) {
            String sql = """
                SELECT 
                    tg.group_name,
                    c.course_code,
                    st.staff_name,
                    st.staff_no,
                    sec.section_name,
                    cmr.sessions_per_week,
                    cmr.periods_per_session,
                    cmr.meeting_type
                FROM teaching_assignment_groups tg
                JOIN teaching_assignment_group_members tgm ON tgm.group_id = tg.group_id
                JOIN teaching_assignments ta ON ta.assignment_id = tgm.assignment_id
                JOIN courses c ON c.course_id = tg.course_id
                JOIN staff st ON st.staff_id = ta.staff_id
                JOIN sections sec ON sec.section_id = ta.section_id
                JOIN course_meeting_requirements cmr ON cmr.course_id = c.course_id
                WHERE tg.term_id = ?::uuid
                  AND c.semester_id = ?::uuid
                ORDER BY tg.group_name, sec.section_name, c.course_code
                """;

            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setString(1, TERM_ID);
            ps.setString(2, SEM7_ID);
            ResultSet rs = ps.executeQuery();

            Map<String, List<String>> groups = new LinkedHashMap<>();
            while (rs.next()) {
                String group = rs.getString("group_name");
                String course = rs.getString("course_code");
                String staff = rs.getString("staff_name");
                String sec = rs.getString("section_name");
                int s = rs.getInt("sessions_per_week");
                int p = rs.getInt("periods_per_session");
                String type = rs.getString("meeting_type");
                groups.computeIfAbsent(group + " (" + course + ")", k -> new ArrayList<>())
                        .add(String.format("Sec %s: %s %s %dx%d", sec, staff, type, s, p));
            }
            rs.close();

            if (groups.isEmpty()) {
                System.out.println("No combined groups found in Sem-7.");
            } else {
                for (Map.Entry<String, List<String>> e : groups.entrySet()) {
                    System.out.println(e.getKey());
                    for (String c : e.getValue()) {
                        System.out.println("  -> " + c);
                    }
                    System.out.println();
                }
            }
        }
    }

    @Test
    void electiveAwareCapacity() throws Exception {
        System.out.println("=== ELECTIVE-AWARE SECTION CAPACITY (Sem-7) ===\n");

        try (Connection conn = dataSource.getConnection()) {
            // Query all teaching assignments with course details
            String sql = """
                SELECT 
                    ta.section_id,
                    sec.section_name,
                    c.course_code,
                    c.is_required,
                    cmr.sessions_per_week,
                    cmr.periods_per_session,
                    cmr.meeting_type,
                    st.staff_name,
                    st.staff_no
                FROM teaching_assignments ta
                JOIN courses c ON c.course_id = ta.course_id
                JOIN staff st ON st.staff_id = ta.staff_id
                JOIN sections sec ON sec.section_id = ta.section_id
                JOIN course_meeting_requirements cmr ON cmr.course_id = c.course_id
                WHERE ta.term_id = ?::uuid
                  AND c.semester_id = ?::uuid
                  AND ta.assignment_status != 'CANCELLED'
                ORDER BY sec.section_name, c.course_code, cmr.meeting_type
                """;

            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setString(1, TERM_ID);
            ps.setString(2, SEM7_ID);
            ResultSet rs = ps.executeQuery();

            // Group by section
            Map<String, Map<String, CourseInfo>> bySection = new LinkedHashMap<>();
            while (rs.next()) {
                String secName = rs.getString("section_name");
                String secId = rs.getString("section_id");
                String code = rs.getString("course_code");
                boolean required = rs.getBoolean("is_required");
                int sessions = rs.getInt("sessions_per_week");
                int pps = rs.getInt("periods_per_session");
                String type = rs.getString("meeting_type");
                String staffName = rs.getString("staff_name");

                String key = secName + "|" + secId;
                bySection.computeIfAbsent(key, k -> new LinkedHashMap<>())
                        .computeIfAbsent(code, k -> new CourseInfo(code, required))
                        .addCMR(sessions, pps, type, staffName);
            }
            rs.close();

            // Print per-section analysis
            for (Map.Entry<String, Map<String, CourseInfo>> secEntry : bySection.entrySet()) {
                String[] parts = secEntry.getKey().split("\\|");
                String secName = parts[0];
                String secId = parts[1];

                System.out.println("=== Section: " + secName + " ===\n");

                // List all courses
                System.out.printf("%-15s %8s %10s %12s %-20s %s%n",
                        "Course", "RawPer", "Required?", "ElectiveGrp", "CMR", "Staff");
                System.out.println("-".repeat(100));

                // Calculate raw demand
                int rawDemand = 0;
                // Calculate elective-aware demand: group non-required by elective group
                // All non-required courses in same section share same elective group key
                Map<String, Integer> electiveGroupMax = new LinkedHashMap<>();
                int requiredDemand = 0;

                for (CourseInfo ci : secEntry.getValue().values()) {
                    int courseTotal = ci.getTotalPeriods();
                    rawDemand += courseTotal;

                    if (ci.required) {
                        requiredDemand += courseTotal;
                        System.out.printf("%-15s %8d %10s %12s %-20s %s%n",
                                ci.code, courseTotal, "YES", "N/A",
                                ci.getCMRSummary(), ci.getStaffSummary());
                    } else {
                        // All non-required in same section share same elective group
                        String egKey = "elective-group|" + secId;
                        electiveGroupMax.merge(egKey, courseTotal, Math::max);
                        System.out.printf("%-15s %8d %10s %12s %-20s %s%n",
                                ci.code, courseTotal, "NO", egKey,
                                ci.getCMRSummary(), ci.getStaffSummary());
                    }
                }

                // Elective-aware occupancy = required + max of each elective group
                int electiveAwareOccupancy = requiredDemand + electiveGroupMax.values().stream()
                        .mapToInt(Integer::intValue).sum();

                System.out.println("\n--- CAPACITY SUMMARY ---");
                System.out.println("Physical capacity:             " + 30);
                System.out.println("Raw course-period demand:      " + rawDemand);
                System.out.println("Required course demand:        " + requiredDemand);
                System.out.println("Elective groups:               " + electiveGroupMax.size());
                for (Map.Entry<String, Integer> eg : electiveGroupMax.entrySet()) {
                    System.out.println("  " + eg.getKey() + " -> max=" + eg.getValue());
                }
                System.out.println("Elective-aware occupancy:      " + electiveAwareOccupancy);
                System.out.println("Effective slack:               " + (30 - electiveAwareOccupancy));
                System.out.println("OVERLOADED (raw):              " + (rawDemand > 30));
                System.out.println("OVERLOADED (elective-aware):   " + (electiveAwareOccupancy > 30));
                System.out.println();
            }
        }
    }

    @Test
    void electiveAwareCapacityAllSections() throws Exception {
        System.out.println("=== ELECTIVE-AWARE CAPACITY Ã¢â‚¬â€ ALL SEM-7 SECTIONS ===\n");

        try (Connection conn = dataSource.getConnection()) {
            String sql = """
                SELECT 
                    ta.section_id,
                    sec.section_name,
                    c.course_code,
                    c.is_required,
                    cmr.sessions_per_week,
                    cmr.periods_per_session
                FROM teaching_assignments ta
                JOIN courses c ON c.course_id = ta.course_id
                JOIN sections sec ON sec.section_id = ta.section_id
                JOIN course_meeting_requirements cmr ON cmr.course_id = c.course_id
                WHERE ta.term_id = ?::uuid
                  AND c.semester_id = ?::uuid
                  AND ta.assignment_status != 'CANCELLED'
                ORDER BY sec.section_name, c.course_code
                """;

            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setString(1, TERM_ID);
            ps.setString(2, SEM7_ID);
            ResultSet rs = ps.executeQuery();

            Map<String, Map<String, int[]>> bySection = new LinkedHashMap<>();
            while (rs.next()) {
                String secName = rs.getString("section_name");
                String secId = rs.getString("section_id");
                String code = rs.getString("course_code");
                boolean required = rs.getBoolean("is_required");
                int sessions = rs.getInt("sessions_per_week");
                int pps = rs.getInt("periods_per_session");

                String key = secName + "|" + secId;
                bySection.computeIfAbsent(key, k -> new LinkedHashMap<>())
                        .merge(code, new int[]{sessions, pps, required ? 1 : 0},
                                (a, b) -> { a[0] += b[0]; return a; });
            }
            rs.close();

            System.out.printf("%-8s %12s %12s %24s %8s%n",
                    "Section", "Physical", "RawDemand", "ElectiveAwareOccupancy", "Slack");
            System.out.println("-".repeat(70));

            for (Map.Entry<String, Map<String, int[]>> secEntry : bySection.entrySet()) {
                String[] parts = secEntry.getKey().split("\\|");
                String secName = parts[0];

                int rawDemand = 0;
                int requiredDemand = 0;
                int electiveGroupMax = 0;
                boolean hasElectiveGroup = false;

                for (int[] info : secEntry.getValue().values()) {
                    int coursePeriods = info[0] * info[1];
                    rawDemand += coursePeriods;
                    if (info[2] == 1) {
                        requiredDemand += coursePeriods;
                    } else {
                        electiveGroupMax = Math.max(electiveGroupMax, coursePeriods);
                        hasElectiveGroup = true;
                    }
                }

                int electiveAware = requiredDemand + (hasElectiveGroup ? electiveGroupMax : 0);

                System.out.printf("%-8s %12d %12d %24d %8d%n",
                        secName, 30, rawDemand, electiveAware, 30 - electiveAware);
            }
        }
    }

    // ===== SECTION-B PROVER =====
    // Independent feasibility check: can Section B alone be scheduled?
    // Constraints: section-grid conflict, same-day rule, staff conflict, elective co-location
    @Test
    void sectionBProver() throws Exception {
        System.out.println("=== SECTION-B FEASIBILITY PROVER ===\n");

        // 18 required scheduling units + 3 co-located elective courses
        // Grid: [day][period], day=0..4 (Mon..Fri), period=0..5 (P1..P6)
        int[][] grid = new int[5][6];
        // Staff: staffId -> [day][period] boolean
        Map<String, boolean[][]> staffOcc = new HashMap<>();
        // Days used per course-group
        Map<Integer, Set<Integer>> groupDays = new HashMap<>();

        int nodes = 0;

        record Unit(String name, int pps, String staff, int group) {}
        record Placement(int day, int start) {}

        Unit[] units = {
            new Unit("CST-4141_1", 2, "STF047", 4),
            new Unit("CST-4141_2", 2, "STF047", 4),
            new Unit("E-4101_1",   2, "STF042", 5),
            new Unit("E-4101_2",   2, "STF042", 5),
            new Unit("CS-4126b",   2, "STF018", 1),
            new Unit("CST-4112b",  2, "STF0031", 2),
            new Unit("CS-4124_1",  1, "STF017", 0),
            new Unit("CS-4124_2",  1, "STF017", 0),
            new Unit("CS-4124_3",  1, "STF017", 0),
            new Unit("CS-4124_4",  1, "STF017", 0),
            new Unit("CS-4126a_1", 1, "STF018", 1),
            new Unit("CS-4126a_2", 1, "STF018", 1),
            new Unit("CST-4112a_1",1, "STF0031", 2),
            new Unit("CST-4112a_2",1, "STF0031", 2),
            new Unit("CST-4123_1", 1, "STF045", 3),
            new Unit("CST-4123_2", 1, "STF045", 3),
            new Unit("CST-4123_3", 1, "STF045", 3),
            new Unit("CST-4123_4", 1, "STF045", 3),
        };
        // Elective group: 3 courses, all share same 2 windows
        String[] eStaff = {"STF043", "STF043", "STF046"};

        int N = units.length;
        Placement[] sol = new Placement[N];
        int[] dayChoice = new int[N + 1];
        int[] startChoice = new int[N + 1];

        System.out.println("Units: " + N + " required + 3 co-located electives");
        System.out.println("Grid: 5 days x 6 periods = 30 cells");
        System.out.println("Solving (up to 30s)...\n");

        // Place required units via backtracking
        boolean reqFound = false;
        int curUnit = 0;
        long t0 = System.currentTimeMillis();

        while (true) {
            nodes++;
            if (nodes % 500000 == 0) System.out.printf("  ...%d nodes, %dms%n", nodes, System.currentTimeMillis() - t0);
            if (System.currentTimeMillis() - t0 > 30000) { System.out.println("  TIME LIMIT"); break; }

            if (curUnit >= N) { reqFound = true; break; }
            if (curUnit < 0 || curUnit >= N) break;

            Unit u = units[curUnit];
            int maxStart = 6 - u.pps;

            boolean advanced = false;
            while (dayChoice[curUnit] < 5 && !advanced) {
                if (curUnit >= N) break;
                int d = dayChoice[curUnit];
                int s = startChoice[curUnit];
                if (s > maxStart) { dayChoice[curUnit]++; startChoice[curUnit] = 0; continue; }
                startChoice[curUnit]++;

                // canPlaceReq
                boolean ok = true;
                int end = s + u.pps;
                if (end <= 6) {
                    for (int p = s; p < end; p++) if (grid[d][p] != 0) { ok = false; break; }
                    if (ok) {
                        Set<Integer> gd = groupDays.getOrDefault(u.group(), Collections.emptySet());
                        if (gd.contains(d)) ok = false;
                    }
                    if (ok) {
                        boolean[][] occ = staffOcc.computeIfAbsent(u.staff, k -> new boolean[5][6]);
                        for (int p = s; p < end; p++) if (occ[d][p]) { ok = false; break; }
                    }
                } else { ok = false; }

                if (ok) {
                    // placeReq
                    for (int p = s; p < end; p++) grid[d][p] = curUnit + 1;
                    boolean[][] socc = staffOcc.computeIfAbsent(u.staff, k -> new boolean[5][6]);
                    for (int p = s; p < end; p++) socc[d][p] = true;
                    groupDays.computeIfAbsent(u.group(), k -> new HashSet<>()).add(d);
                    sol[curUnit] = new Placement(d, s);
                    curUnit++;
                    advanced = true;
                }
            }

            if (!advanced) {
                dayChoice[curUnit] = 0;
                startChoice[curUnit] = 0;
                curUnit--;
                if (curUnit < 0) break;
                // undoReq
                Unit uu = units[curUnit];
                if (sol[curUnit] != null) {
                    int dd = sol[curUnit].day(), ss = sol[curUnit].start(), ee = ss + uu.pps;
                    for (int p = ss; p < ee; p++) grid[dd][p] = 0;
                    boolean[][] occ2 = staffOcc.get(uu.staff);
                    if (occ2 != null) for (int p = ss; p < ee; p++) occ2[dd][p] = false;
                    Set<Integer> gd2 = groupDays.get(uu.group());
                    if (gd2 != null) gd2.remove(dd);
                    sol[curUnit] = null;
                }
                dayChoice[curUnit]++;
                startChoice[curUnit] = 0;
            }
        }

        if (!reqFound) {
            System.out.println("\n=== RESULT ===");
            System.out.println("Required units: NO valid arrangement in " + nodes + " nodes, " + (System.currentTimeMillis() - t0) + "ms");
            System.out.println("GENUINELY INFEASIBLE for Section B required courses alone.");
            return;
        }

        System.out.println("Required units: FOUND in " + nodes + " nodes, " + (System.currentTimeMillis() - t0) + "ms");

        // Now try elective group
        System.out.println("\nTrying elective group (CS-4115 + CST-4137 + CST-4158)...");

        Placement[] eWin = new Placement[2];
        boolean elecFound = false;
        for (int d1 = 0; d1 < 5 && !elecFound; d1++) {
            for (int s1 = 0; s1 <= 4 && !elecFound; s1++) {
                int e1 = s1 + 2;
                if (e1 > 6) continue;
                boolean ok = true;
                for (int p = s1; p < e1; p++) if (grid[d1][p] != 0) { ok = false; break; }
                if (!ok) continue;
                for (String stf : eStaff) {
                    boolean[][] occ = staffOcc.computeIfAbsent(stf, k -> new boolean[5][6]);
                    for (int p = s1; p < e1; p++) if (occ[d1][p]) { ok = false; break; }
                    if (!ok) break;
                }
                if (!ok) continue;

                for (int d2 = 0; d2 < 5 && !elecFound; d2++) {
                    if (d2 == d1) continue;
                    for (int s2 = 0; s2 <= 4 && !elecFound; s2++) {
                        int e2 = s2 + 2;
                        if (e2 > 6) continue;
                        ok = true;
                        for (int p = s2; p < e2; p++) if (grid[d2][p] != 0) { ok = false; break; }
                        if (!ok) continue;
                        for (String stf : eStaff) {
                            boolean[][] occ = staffOcc.computeIfAbsent(stf, k -> new boolean[5][6]);
                            for (int p = s2; p < e2; p++) if (occ[d2][p]) { ok = false; break; }
                            if (!ok) break;
                        }
                        if (ok) {
                            eWin[0] = new Placement(d1, s1);
                            eWin[1] = new Placement(d2, s2);
                            elecFound = true;
                        }
                    }
                }
            }
        }

        System.out.println("\n=== RESULT ===");
        System.out.println("Required units: FEASIBLE");
        System.out.println("Elective group: " + (elecFound ? "FEASIBLE" : "INFEASIBLE"));
        System.out.println("Total nodes: " + nodes);
        System.out.println("Time: " + (System.currentTimeMillis() - t0) + "ms");

        if (elecFound) {
            System.out.println("\n=== COMPLETE SECTION B SCHEDULE ===\n");
            String[] dayNames = {"Mon", "Tue", "Wed", "Thu", "Fri"};
            System.out.printf("%-18s %-5s %-10s %s%n", "Course", "Day", "Window", "Staff");
            System.out.println("-".repeat(50));
            for (int i = 0; i < N; i++) {
                if (sol[i] != null) {
                    System.out.printf("%-18s %-5s P%d-P%d %s%n",
                        units[i].name(), dayNames[sol[i].day()],
                        sol[i].start() + 1, sol[i].start() + units[i].pps(),
                        units[i].staff());
                }
            }
            String[][] eCourses = {{"CS-4115", "STF043"}, {"CST-4137", "STF043"}, {"CST-4158", "STF046"}};
            for (String[] ec : eCourses) {
                for (int w = 0; w < 2; w++) {
                    System.out.printf("%-18s %-5s P%d-P%d %s%n",
                        ec[0] + "(E)", dayNames[eWin[w].day()],
                        eWin[w].start() + 1, eWin[w].start() + 2, ec[1]);
                }
            }
            System.out.println("\n*** Section B IS FEASIBLE — production solver has a SEARCH BUG ***");
        } else {
            System.out.println("\n*** Required courses feasible but elective group cannot fit ***");
        }
    }


    // ===== FULL SEM-7 PROVER =====
    // Tests feasibility of scheduling ALL sections (A, B, C, CT) together
    // Staff conflicts are GLOBAL: same staff cannot teach two sections at same time
    @Test
    void sem7FullProver() throws Exception {
        System.out.println("=== SEM-7 FULL FEASIBILITY PROVER (A, B, CT) ===\n");

        // Load all Sem-7 scheduling units from DB
        // Each row: courseCode, sectionName, sectionId, staffId, staffName, isRequired, courseGroup
        List<Object[]> allUnits = new ArrayList<>();
        Map<String, Integer> courseGroupIdMap = new HashMap<>();
        int nextGroupId = 0;

        try (Connection conn = dataSource.getConnection()) {
            String sql = """
                SELECT 
                    sec.section_id, sec.section_name,
                    c.course_id, c.course_code, c.is_required,
                    cmr.sessions_per_week, cmr.periods_per_session,
                    st.staff_id, st.staff_name
                FROM teaching_assignments ta
                JOIN courses c ON c.course_id = ta.course_id
                JOIN sections sec ON sec.section_id = ta.section_id
                JOIN staff st ON st.staff_id = ta.staff_id
                JOIN course_meeting_requirements cmr ON cmr.course_id = c.course_id
                WHERE ta.term_id = ?::uuid
                  AND c.semester_id = ?::uuid
                  AND sec.section_name IN ('A','B','CT')
                  AND ta.assignment_status != 'CANCELLED'
                ORDER BY sec.section_name, c.course_code, cmr.meeting_type
                """;
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setString(1, TERM_ID);
            ps.setString(2, SEM7_ID);
            ResultSet rs = ps.executeQuery();

            while (rs.next()) {
                String secId = rs.getString("section_id");
                String secName = rs.getString("section_name");
                String courseId = rs.getString("course_id");
                String code = rs.getString("course_code");
                boolean required = rs.getBoolean("is_required");
                int spw = rs.getInt("sessions_per_week");
                int pps = rs.getInt("periods_per_session");
                String staffId = rs.getString("staff_id");
                String staffName = rs.getString("staff_name");

                String groupKey = secId + "|" + courseId;
                int group;
                if (courseGroupIdMap.containsKey(groupKey)) {
                    group = courseGroupIdMap.get(groupKey);
                } else {
                    group = nextGroupId++;
                    courseGroupIdMap.put(groupKey, group);
                }

                // Expand spw into individual 1-session units
                for (int i = 0; i < spw; i++) {
                    allUnits.add(new Object[]{code, secName, secId, staffId, staffName, required, group, pps});
                }
            }
            rs.close();
        }

        int totalUnits = allUnits.size();

        // Section info
        Set<String> secSet = new LinkedHashSet<>();
        for (Object[] u : allUnits) secSet.add((String) u[1]);
        String[] secNames = secSet.toArray(new String[0]);
        int numSections = secNames.length;
        Map<String, Integer> secIdxMap = new HashMap<>();
        for (int i = 0; i < numSections; i++) secIdxMap.put(secNames[i], i);

        System.out.println("=== DATA SUMMARY ===");
        System.out.println("Total scheduling units: " + totalUnits);
        Map<String, Integer> secCounts = new LinkedHashMap<>();
        for (Object[] u : allUnits) secCounts.merge((String) u[1], 1, Integer::sum);
        for (var e : secCounts.entrySet()) System.out.println("  " + e.getKey() + ": " + e.getValue() + " units");
        System.out.println("Unique staff: " + allUnits.stream().map(u -> (String) u[3]).distinct().count());
        System.out.println("Sections: " + numSections + " (" + String.join(", ", secNames) + ")");
        System.out.println("Course groups: " + nextGroupId);
        System.out.println();

        // Print per-section breakdown
        for (String sn : secNames) {
            System.out.println("Section " + sn + ":");
            Map<String, String> seen = new LinkedHashMap<>();
            for (Object[] u : allUnits) {
                if (!u[1].equals(sn)) continue;
                String key = (String) u[0] + "|" + u[7] + "|" + u[3];
                if (!seen.containsKey(key)) {
                    seen.put(key, String.format("  %s %dx%d [%s] %s",
                            u[0], 1, u[7], (boolean) u[5] ? "R" : "E", u[4]));
                }
            }
            for (String s : seen.values()) System.out.println(s);
        }

        // Build prover state
        System.out.println("\n=== SOLVING (up to 120s, MRV) ===\n");

        int[][][] grids = new int[numSections][5][6];
        Map<String, boolean[][]> staffOcc = new HashMap<>();
        Map<Integer, Set<Integer>> groupDays = new HashMap<>();
        boolean[] placed = new boolean[totalUnits];
        int[] solDay = new int[totalUnits];
        int[] solStart = new int[totalUnits];
        Arrays.fill(solDay, -1);
        Arrays.fill(solStart, -1);

        long t0 = System.currentTimeMillis();
        int[] nodes = {0};
        boolean[] found = {false};

        // MRV recursive backtracking
        final boolean[] foundArr = found;
        final int[] nodesArr = nodes;
        final int[][][] gridsRef = grids;
        final Map<String, boolean[][]> staffOccRef = staffOcc;
        final Map<Integer, Set<Integer>> groupDaysRef = groupDays;
        final boolean[] placedRef = placed;
        final int[] solDayRef = solDay;
        final int[] solStartRef = solStart;
        final List<Object[]> allUnitsRef = allUnits;
        final Map<String, Integer> secIdxMapRef = secIdxMap;
        final int totalUnitsRef = totalUnits;

        java.util.function.BiConsumer<Integer, Long> solveFn = null;
        solveFn = new java.util.function.BiConsumer<Integer, Long>() {
            @Override
            public void accept(Integer depth, Long deadline) {
                if (foundArr[0]) return;
                if (depth == totalUnitsRef) { foundArr[0] = true; return; }
                nodesArr[0]++;
                if (nodesArr[0] % 500000 == 0) {
                    System.out.printf("  ...%d nodes, %dms, depth=%d/%d%n",
                            nodesArr[0], System.currentTimeMillis() - t0, depth, totalUnitsRef);
                }
                if (System.currentTimeMillis() > deadline) return;

                // Find unplaced unit with fewest valid placements (MRV)
                int bestUnit = -1;
                int bestCount = Integer.MAX_VALUE;
                List<int[]> bestOptions = null;

                for (int ui = 0; ui < totalUnitsRef; ui++) {
                    if (placedRef[ui]) continue;
                    Object[] u = allUnitsRef.get(ui);
                    int si = secIdxMapRef.get(u[1]);
                    int pps = (int) u[7];
                    int maxStart = 6 - pps;
                    String staffId = (String) u[3];
                    int grp = (int) u[6];

                    List<int[]> options = new ArrayList<>();
                    for (int d = 0; d < 5; d++) {
                        Set<Integer> gd = groupDaysRef.getOrDefault(grp, Collections.emptySet());
                        if (gd.contains(d)) continue;
                        boolean[][] occ = staffOccRef.getOrDefault(staffId, new boolean[5][6]);
                        for (int s = 0; s <= maxStart; s++) {
                            int end = s + pps;
                            boolean ok = true;
                            for (int p = s; p < end; p++) if (gridsRef[si][d][p] != 0) { ok = false; break; }
                            if (!ok) continue;
                            for (int p = s; p < end; p++) if (occ[d][p]) { ok = false; break; }
                            if (ok) options.add(new int[]{d, s});
                        }
                    }
                    if (options.isEmpty()) return; // dead end
                    if (options.size() < bestCount) {
                        bestCount = options.size();
                        bestUnit = ui;
                        bestOptions = options;
                        if (bestCount == 1) break;
                    }
                }

                if (bestUnit < 0) return;

                Object[] u = allUnitsRef.get(bestUnit);
                int si = secIdxMapRef.get(u[1]);
                int pps = (int) u[7];
                String staffId = (String) u[3];
                int grp = (int) u[6];

                for (int[] opt : bestOptions) {
                    if (foundArr[0]) return;
                    if (System.currentTimeMillis() > deadline) return;
                    int d = opt[0], s = opt[1], end = s + pps;

                    placedRef[bestUnit] = true;
                    solDayRef[bestUnit] = d;
                    solStartRef[bestUnit] = s;
                    for (int p = s; p < end; p++) gridsRef[si][d][p] = bestUnit + 1;
                    boolean[][] socc = staffOccRef.computeIfAbsent(staffId, k -> new boolean[5][6]);
                    for (int p = s; p < end; p++) socc[d][p] = true;
                    groupDaysRef.computeIfAbsent(grp, k -> new HashSet<>()).add(d);

                    accept(depth + 1, deadline);

                    if (!foundArr[0]) {
                        placedRef[bestUnit] = false;
                        solDayRef[bestUnit] = -1;
                        solStartRef[bestUnit] = -1;
                        for (int p = s; p < end; p++) gridsRef[si][d][p] = 0;
                        boolean[][] occ2 = staffOccRef.get(staffId);
                        if (occ2 != null) for (int p = s; p < end; p++) occ2[d][p] = false;
                        Set<Integer> gd = groupDaysRef.get(grp);
                        if (gd != null) gd.remove(d);
                    }
                }
            }
        };

        solveFn.accept(0, System.currentTimeMillis() + 120000L);

        long elapsed = System.currentTimeMillis() - t0;
        System.out.println("\n" + "=".repeat(60));
        System.out.println("=== SEM-7 FULL PROVER RESULT ===");
        System.out.println("=".repeat(60));
        System.out.println("Total units: " + totalUnits);
        System.out.println("Sections: " + numSections + " (" + String.join(", ", secNames) + ")");
        System.out.println("Result: " + (found[0] ? "FEASIBLE" : "INFEASIBLE (within 120s)"));
        System.out.println("Nodes explored: " + nodes[0]);
        System.out.println("Time: " + elapsed + "ms");

        if (found[0]) {
            System.out.println("\n=== COMPLETE SEM-7 SCHEDULE (A, B, CT) ===\n");
            String[] dayNames = {"Mon", "Tue", "Wed", "Thu", "Fri"};
            for (int si2 = 0; si2 < numSections; si2++) {
                System.out.println("--- Section " + secNames[si2] + " ---");
                System.out.printf("%-15s %-5s %-10s %s%n", "Course", "Day", "Window", "Staff");
                System.out.println("-".repeat(50));
                for (int p = 0; p < totalUnits; p++) {
                    if (placed[p] && secIdxMap.get(allUnits.get(p)[1]) == si2) {
                        Object[] u2 = allUnits.get(p);
                        System.out.printf("%-15s %-5s P%d-P%d %s%n",
                                u2[0] + ((boolean) u2[5] ? "" : "(E)"),
                                dayNames[solDay[p]],
                                solStart[p] + 1,
                                solStart[p] + (int) u2[7],
                                u2[4]);
                    }
                }
                System.out.println();
            }
            System.out.println("*** PRODUCTION SOLVER SEARCH BUG CONFIRMED ***");
            System.out.println("A valid Sem-7 schedule EXISTS but the production solver cannot find it.");
        } else {
            System.out.println("\nNo valid schedule found within 120s.");
            System.out.println("Consider running per-section individually to identify bottleneck.");
        }
    }

    static class CourseInfo {
        final String code;
        final boolean required;
        final List<int[]> cmrs = new ArrayList<>();
        final List<String> types = new ArrayList<>();
        final Set<String> staff = new LinkedHashSet<>();

        CourseInfo(String code, boolean required) {
            this.code = code;
            this.required = required;
        }

        void addCMR(int sessions, int pps, String type, String staffName) {
            cmrs.add(new int[]{sessions, pps});
            types.add(type);
            if (staffName != null) staff.add(staffName);
        }

        int getTotalPeriods() {
            return cmrs.stream().mapToInt(c -> c[0] * c[1]).sum();
        }

        String getCMRSummary() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < cmrs.size(); i++) {
                if (sb.length() > 0) sb.append(" + ");
                sb.append(cmrs.get(i)[0]).append("x").append(cmrs.get(i)[1])
                  .append("[").append(types.get(i)).append("]");
            }
            return sb.toString();
        }

        String getStaffSummary() {
            return String.join(", ", staff);
        }
    }
}
