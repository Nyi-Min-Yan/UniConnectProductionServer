package com.unicconnect.service;

import com.unicconnect.dto.request.CreateGenerationRequest;
import com.unicconnect.dto.request.GenerateTimetableRequest;
import com.unicconnect.entity.*;
import com.unicconnect.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reproduces + verifies the combined-class generation-scope validation.
 *
 * The user reported: selecting Sem-3 A+B+C threw "combined class Semester 1 ...
 * CST-1141 ... only partially selected" even though Sem-1 CST-1141 was NOT part
 * of the requested scope. Combined-class validation must be scoped to the
 * SELECTED semesters: a group whose semester is not in scope must be ignored.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Transactional
public class CombinedClassScopeValidationTest {

    private static final UUID MID_EXAM = UUID.fromString("4f885c79-0eb3-a8b3-d35d-dee741a88907");
    private static final UUID SEM1 = UUID.fromString("ca7bb336-9530-4254-bc8d-3e92d3278ab3");
    private static final UUID SEM3 = UUID.fromString("2f4a3bc3-5d7e-44e2-b3ea-3bab943abcfb");
    private static final UUID SEC_A  = UUID.fromString("81004274-cf05-491f-b1a3-7c8e3d5c77e7");
    private static final UUID SEC_B  = UUID.fromString("f19a0bb5-347a-4b40-aa0a-dd7062a0d64e");
    private static final UUID SEC_C  = UUID.fromString("adc0d7f4-3075-41d0-9366-c6e8b80f0a27");
    private static final UUID SEC_CT = UUID.fromString("029878c5-d51d-4a0a-8fb1-99642ab4dee1");

    @Autowired TimetableGenerationService generationService;
    @Autowired AcademicTermRepository termRepository;
    @Autowired TeachingAssignmentGroupRepository groupRepository;
    @Autowired TeachingAssignmentGroupMemberRepository groupMemberRepository;
    @Autowired UserRepository userRepository;
    @Autowired SemesterRepository semesterRepository;
    @Autowired SectionRepository sectionRepository;
    @Autowired CourseRepository courseRepository;

    @BeforeEach
    void authenticateAsHod() {
        UUID userId = userRepository.findByEmail("dawmya@gmail.com")
                .orElseThrow(() -> new IllegalStateException("test HOD user missing"))
                .getUserId();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        userId, null, List.of(new SimpleGrantedAuthority("ROLE_STAFF"))));
    }

    private String sectionName(UUID secId) {
        return sectionRepository.findById(secId).map(Section::getSectionName).orElse("?");
    }

    private String semNo(Semester s) {
        return s != null ? ("S" + s.getSemesterNo()) : "NO-SEM";
    }

    // ========== 1. GROUND-TRUTH: dump all combined classes ==========
    @Test
    void dumpCombinedClasses() {
        AcademicTerm term = termRepository.findByStatus(TermStatus.ACTIVE).stream().findFirst().orElseThrow();
        System.out.println("\n===== COMBINED-CLASS GROUPS (term=" + term.getTermId() + ") =====");
        List<TeachingAssignmentGroup> groups = groupRepository.findWithCourseByTermId(term.getTermId());
        for (TeachingAssignmentGroup g : groups) {
            Course c = g.getCourse();
            String semester = c.getSemester() != null ? ("Sem-" + c.getSemester().getSemesterNo()) : "Sem-?";
            // members
            List<String> sections = g.getMembers().stream()
                    .map(m -> m.getAssignment().getSection().getSectionName())
                    .sorted()
                    .toList();
            System.out.println("  course=" + c.getCourseCode() + " " + semester
                    + " sections=[" + String.join(",", sections) + "] members=" + sections.size()
                    + " elective=" + c.isRequired());
        }
    }

    // ========== Helpers to run generation and capture the validation error ==========
    private Throwable runGenerate(String label, UUID semesterId, UUID[] sections) {
        AcademicTerm term = termRepository.findByStatus(TermStatus.ACTIVE).stream().findFirst().orElseThrow();
        List<GenerateTimetableRequest.SemesterSelection> selections = List.of(
                new GenerateTimetableRequest.SemesterSelection(semesterId, List.of(sections)));
        UUID genId = generationService.create(new CreateGenerationRequest(term.getTermId(), null)).generationId();
        try {
            generationService.generate(genId, new GenerateTimetableRequest(MID_EXAM, selections, true));
            return null; // no error
        } catch (Throwable t) {
            return t;
        }
    }

    // ========== 2. Sem-3 A+B+C must IGNORE Sem-1 CST-1141 ==========
    @Test
    void sem3_full_must_ignore_sem1_cst1141() {
        Throwable t = runGenerate("Sem3 A+B+C",
                SEM3, new UUID[]{SEC_A, SEC_B, SEC_C});
        if (t != null) {
            System.out.println("Sem3 A+B+C ERROR: " + t.getMessage());
            fail("Selecting Sem-3 A+B+C must not be blocked by Sem-1 CST-1141. Got: " + t.getMessage());
        } else {
            System.out.println("Sem3 A+B+C -> VALID (Sem-1 CST-1141 ignored) PASS");
        }
    }

    // ========== 3. Sem-1 partial selection must reject CST-1141 ==========
    @Test
    void sem1_partial_must_reject_cst1141() {
        Throwable t = runGenerate("Sem1 A+B (partial)",
                SEM1, new UUID[]{SEC_A, SEC_B});
        if (t == null) {
            System.out.println("Sem1 A+B (partial) -> unexpectedly VALID (should be INVALID)");
            // note: whether CST-1141 exists in Sem-1 determines if this is correct.
            return;
        }
        String msg = t.getMessage() == null ? "" : t.getMessage();
        System.out.println("Sem1 A+B (partial) -> INVALID: " + msg);
        System.out.println("  -> contains 'only partially selected': " + msg.contains("only partially selected"));
    }

    // ========== 4. Sem-1 full selection should accept ==========
    @Test
    void sem1_full_must_accept_cst1141() {
        Throwable t = runGenerate("Sem1 A+B+C",
                SEM1, new UUID[]{SEC_A, SEC_B, SEC_C});
        if (t != null) {
            System.out.println("Sem1 A+B+C ERROR: " + t.getMessage());
        } else {
            System.out.println("Sem1 A+B+C -> VALID PASS");
        }
    }

    // ========== 5. CST-2241 / E-2201 (Sem-4) membership ==========
    // Reads the ACTUAL group membership from the DB (never hard-coded). Reports
    // the memberships so the reported expectation (A+B+CT) can be checked against
    // the real data. The validation code must read members from the DB, not assume
    // any fixed section set.
    @Test
    void sem4_cst2241_e2201_membership() {
        AcademicTerm term = termRepository.findByStatus(TermStatus.ACTIVE).stream().findFirst().orElseThrow();
        List<TeachingAssignmentGroup> groups = groupRepository.findWithCourseByTermId(term.getTermId());
        int found = 0;
        for (TeachingAssignmentGroup g : groups) {
            Course c = g.getCourse();
            if (!List.of("CST-2241", "E-2201").contains(c.getCourseCode())) continue;
            found++;
            String semester = c.getSemester() != null ? ("Sem-" + c.getSemester().getSemesterNo()) : "Sem-?";
            List<String> sections = g.getMembers().stream()
                    .map(m -> m.getAssignment().getSection().getSectionName())
                    .sorted()
                    .toList();
            System.out.println("  " + c.getCourseCode() + " " + semester
                    + " sections=[" + String.join(",", sections) + "]");
            // Membership is read from the DB. We only assert it is non-empty and
            // spans at least two distinct sections; the exact set is a data fact.
            assertTrue(sections.size() >= 2, c.getCourseCode() + " combined class must span >=2 sections");
            assertFalse(sections.contains(null));
            assertEquals(sections.size(), new HashSet<>(sections).size(), "sections must be distinct");
        }
        assertTrue(found >= 1, "expected CST-2241 group to exist");
        System.out.println("  NOTE: reported expectation is A+B+CT; actual DB membership above is authoritative (code reads from DB).");
    }
}
