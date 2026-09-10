package com.unicconnect.config;

import com.unicconnect.entity.*;
import com.unicconnect.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

@Component
@Profile("dev")
public class DevDataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataInitializer.class);

    private static final String STAFF_PASSWORD = "ucstgo@2026";
    private static final String STUDENT_PASSWORD = "ucstgo@2026";
    private static final String ADMIN_EMAIL = "nyiminyan0099@gmail.com";
    private static final String ADMIN_PASSWORD = "@dmin123";

    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final OrganizationalUnitRepository unitRepository;
    private final PositionRepository positionRepository;
    private final StaffRepository staffRepository;
    private final StaffPositionAssignmentRepository spaRepository;
    private final MajorRepository majorRepository;
    private final SemesterRepository semesterRepository;
    private final SectionRepository sectionRepository;
    private final AcademicTermRepository termRepository;
    private final StudentRepository studentRepository;
    private final PasswordEncoder passwordEncoder;

    public DevDataInitializer(RoleRepository roleRepository,
                              UserRepository userRepository,
                              OrganizationalUnitRepository unitRepository,
                              PositionRepository positionRepository,
                              StaffRepository staffRepository,
                              StaffPositionAssignmentRepository spaRepository,
                              MajorRepository majorRepository,
                              SemesterRepository semesterRepository,
                              SectionRepository sectionRepository,
                              AcademicTermRepository termRepository,
                              StudentRepository studentRepository,
                              PasswordEncoder passwordEncoder) {
        this.roleRepository = roleRepository;
        this.userRepository = userRepository;
        this.unitRepository = unitRepository;
        this.positionRepository = positionRepository;
        this.staffRepository = staffRepository;
        this.spaRepository = spaRepository;
        this.majorRepository = majorRepository;
        this.semesterRepository = semesterRepository;
        this.sectionRepository = sectionRepository;
        this.termRepository = termRepository;
        this.studentRepository = studentRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        log.info("=== Dev profile active: seeding development data ===");

        // The serverless database may still be waking up when this runner fires:
        // the Neon pooler can reset the first connection mid-query during a cold
        // start, which would otherwise abort application startup. Seeding is
        // idempotent, so retry through the wake-up window for transient
        // connection failures only; anything else is a real problem and still
        // aborts startup.
        Exception lastFailure = null;
        for (int attempt = 1; attempt <= 15; attempt++) {
            try {
                seedOrganizationalUnits();
                seedSemesters();
                seedSections();
                seedMajors();
                seedAcademicTerm();
                seedPositions();
                seedSystemAdmin();
                seedStaff();
                seedStudents();
                log.info("=== Dev data seeding complete (attempt {}) ===", attempt);
                return;
            } catch (org.springframework.dao.DataAccessResourceFailureException
                     | org.springframework.dao.QueryTimeoutException e) {
                lastFailure = e;
                log.warn("Dev data seeding failed on attempt {} (database warming up?): {}",
                        attempt, e.getMessage());
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting to retry dev seeding", ie);
                }
            }
        }
        log.error("Dev data seeding failed after 15 attempts; continuing without seed data. "
                + "The application stays usable while the database is already populated.", lastFailure);
    }

    // ── Organizational Units ───────────────────────────────────

    private void seedOrganizationalUnits() {
        String[][] units = {
            {"FCS", "Faculty of Computer Science", "ACADEMIC"},
            {"FCST", "Faculty of Computer Systems and Technologies", "ACADEMIC"},
            {"FIS", "Faculty of Information Science", "ACADEMIC"},
            {"ITSM", "Department of Information Technologies Support and Maintenance", "ACADEMIC"},
            {"FC", "Faculty of Computing", "ACADEMIC"},
            {"NL", "Department of Natural Language", "ACADEMIC"},
            {"NS", "Department of Natural Science", "ACADEMIC"},
            {"ADM", "Department of Administration", "ADMINISTRATIVE"},
            {"FIN", "Department of Finance", "ADMINISTRATIVE"},
            {"SA", "Department of Student Affairs", "ADMINISTRATIVE"}
        };
        for (String[] u : units) {
            if (!unitRepository.existsByUnitCode(u[0])) {
                OrganizationalUnit unit = new OrganizationalUnit();
                unit.setUnitCode(u[0]);
                unit.setUnitName(u[1]);
                unit.setUnitType(u[2]);
                unitRepository.save(unit);
            }
        }
        log.info("Organizational units seeded");
    }

    // ── Semesters ──────────────────────────────────────────────

    private void seedSemesters() {
        Set<Integer> existing = semesterRepository.findAll().stream()
                .map(Semester::getSemesterNo).collect(java.util.stream.Collectors.toSet());
        for (int i = 1; i <= 8; i++) {
            final int semNo = i;
            if (!existing.contains(semNo)) {
                Semester sem = new Semester();
                sem.setSemesterNo(semNo);
                semesterRepository.save(sem);
            }
        }
        log.info("Semesters seeded (1-8)");
    }

    // ── Sections ───────────────────────────────────────────────

    private void seedSections() {
        for (String name : List.of("A", "B", "C", "CT")) {
            if (sectionRepository.findAll().stream().noneMatch(s -> s.getSectionName().equals(name))) {
                Section section = new Section();
                section.setSectionName(name);
                sectionRepository.save(section);
            }
        }
        log.info("Sections seeded (A, B, C, CT)");
    }

    // ── Majors ─────────────────────────────────────────────────

    private void seedMajors() {
        String csUnitCode = "FCS";
        String[][] majors = {
            {"CS", "Computer Science"},
            {"CT", "Computer Technology"},
            {"CST", "Computer Systems and Technology"}
        };
        OrganizationalUnit csUnit = unitRepository.findByUnitCode(csUnitCode)
                .orElseThrow(() -> new RuntimeException("FCS unit not found"));

        for (String[] m : majors) {
            List<Major> existing = majorRepository.findAll();
            if (existing.stream().noneMatch(maj -> maj.getMajorCode().equals(m[0]))) {
                Major major = new Major();
                major.setUnit(csUnit);
                major.setMajorCode(m[0]);
                major.setMajorName(m[1]);
                majorRepository.save(major);
            }
        }
        log.info("Majors seeded (CS, CT, CST)");
    }

    // ── Academic Term ──────────────────────────────────────────

    private void seedAcademicTerm() {
        LocalDate today = LocalDate.now();
        LocalDate desiredEnd = today.withDayOfMonth(today.lengthOfMonth());
        if (termRepository.findByStatus(TermStatus.ACTIVE).isEmpty()) {
            AcademicTerm term = new AcademicTerm();
            term.setAcademicYear("2025-2026");
            term.setStartDate(LocalDate.of(2025, 9, 1));
            term.setEndDate(today.withDayOfMonth(today.lengthOfMonth()));
            term.setStatus(TermStatus.ACTIVE);
            termRepository.save(term);
            log.info("Academic term 2025-2026 created");
        } else {
            AcademicTerm term = termRepository.findByStatus(TermStatus.ACTIVE).stream().findFirst().orElseThrow(
                    () -> new IllegalStateException("No active academic term found"));
            if (term.getStartDate() == null || term.getStartDate().isAfter(today)) {
                term.setStartDate(today.minusMonths(6));
            }
            if (term.getEndDate() == null || term.getEndDate().isBefore(desiredEnd)) {
                term.setEndDate(desiredEnd);
            }
            termRepository.save(term);
            log.info("Active academic term extended to start={} end={}", term.getStartDate(), term.getEndDate());
        }
    }

    // ── Positions ──────────────────────────────────────────────

    private void seedPositions() {
        String[][] positions = {
            {"LECTURER", "Lecturer"},
            {"HOD", "Head of Department"},
            {"STUDENT_AFFAIRS_OFFICER", "Student Affairs Officer"},
            {"FINANCE_OFFICER", "Finance Officer"},
            {"ADMINISTRATIVE_OFFICER", "Administrative Officer"},
            {"SENIOR_CLERK", "Senior Clerk"},
            {"JUNIOR_CLERK", "Junior Clerk"},
            {"RECTOR", "Rector"},
            {"PRO_RECTOR", "Pro-Rector"}
        };
        for (String[] p : positions) {
            if (!positionRepository.existsByPositionName(p[0])) {
                Position pos = new Position();
                pos.setPositionName(p[0]);
                pos.setDescription(p[1]);
                positionRepository.save(pos);
            }
        }
        log.info("Positions seeded");
    }

    // ── System Admin ───────────────────────────────────────────

    private void seedSystemAdmin() {
        if (userRepository.existsByEmail(ADMIN_EMAIL)) {
            log.info("System admin already exists: {}", ADMIN_EMAIL);
            return;
        }
        Role adminRole = roleRepository.findByRoleName("SYSTEM_ADMIN")
                .orElseThrow(() -> new RuntimeException("SYSTEM_ADMIN role not found"));

        User user = new User();
        user.setEmail(ADMIN_EMAIL);
        user.setPasswordHash(passwordEncoder.encode(ADMIN_PASSWORD));
        user.setRole(adminRole);
        user.setActive(true);
        user.setRegistrationStatus(RegistrationStatus.APPROVED);
        userRepository.save(user);

        log.info("System admin created: {} (password hashed with BCrypt)", ADMIN_EMAIL);
    }

    // ── Staff ──────────────────────────────────────────────────

    private void seedStaff() {
        Role staffRole = roleRepository.findByRoleName("STAFF")
                .orElseThrow(() -> new RuntimeException("STAFF role not found"));

        OrganizationalUnit csUnit = unitRepository.findByUnitCode("FCS").orElse(null);
        OrganizationalUnit saUnit = unitRepository.findByUnitCode("SA").orElse(null);
        OrganizationalUnit finUnit = unitRepository.findByUnitCode("FIN").orElse(null);
        OrganizationalUnit admUnit = unitRepository.findByUnitCode("ADM").orElse(null);
        OrganizationalUnit cstUnit = unitRepository.findByUnitCode("FCST").orElse(null);
        OrganizationalUnit isUnit = unitRepository.findByUnitCode("FIS").orElse(null);
        OrganizationalUnit itsmUnit = unitRepository.findByUnitCode("ITSM").orElse(null);
        OrganizationalUnit compUnit = unitRepository.findByUnitCode("FC").orElse(null);
        OrganizationalUnit nlUnit = unitRepository.findByUnitCode("NL").orElse(null);
        OrganizationalUnit nsUnit = unitRepository.findByUnitCode("NS").orElse(null);

        Position lecturerPos = positionRepository.findByPositionName("LECTURER").orElse(null);
        Position hodPos = positionRepository.findByPositionName("HOD").orElse(null);
        Position saoPos = positionRepository.findByPositionName("STUDENT_AFFAIRS_OFFICER").orElse(null);
        Position foPos = positionRepository.findByPositionName("FINANCE_OFFICER").orElse(null);
        Position aoPos = positionRepository.findByPositionName("ADMINISTRATIVE_OFFICER").orElse(null);
        Position scPos = positionRepository.findByPositionName("SENIOR_CLERK").orElse(null);
        Position jcPos = positionRepository.findByPositionName("JUNIOR_CLERK").orElse(null);

        Staff adminStaff = null;

        // ── Lecturers (CS faculty) ─────────────────────────────
        staff("STF001", "dawmya@gmail.com", "Daw Mya", "09-1111111", csUnit,
                List.of(new PosAssign(lecturerPos, LocalDate.of(2024, 1, 1)),
                        new PosAssign(hodPos, LocalDate.of(2024, 1, 1))),
                staffRole);

        staff("STF002", "dawaye@gmail.com", "Daw Aye", "09-2222222", csUnit,
                List.of(new PosAssign(lecturerPos, LocalDate.of(2024, 1, 1))),
                staffRole);

        staff("STF003", "dawsandar@gmail.com", "Daw Sandar", "09-3333333", csUnit,
                List.of(new PosAssign(lecturerPos, LocalDate.of(2024, 1, 1))),
                staffRole);

        // ── Lecturers (CST faculty) ─────────────────────────────
        staff("STF013", "dawmoe@gmail.com", "Daw Moe", "09-4040404", cstUnit,
                List.of(new PosAssign(lecturerPos, LocalDate.of(2024, 1, 1)),
                        new PosAssign(hodPos, LocalDate.of(2024, 1, 1))),
                staffRole);

        staff("STF014", "aungkyaw@gmail.com", "U Aung Kyaw", "09-5050505", cstUnit,
                List.of(new PosAssign(lecturerPos, LocalDate.of(2024, 1, 1))),
                staffRole);

        staff("STF015", "dawthida@gmail.com", "Daw Thida", "09-6060606", cstUnit,
                List.of(new PosAssign(lecturerPos, LocalDate.of(2024, 1, 1))),
                staffRole);

        // ── Lecturers (FIS) ─────────────────────────────────────
        staff("STF016", "htetnaing@gmail.com", "U Htet Naing", "09-7070707", isUnit,
                List.of(new PosAssign(lecturerPos, LocalDate.of(2024, 1, 1)),
                        new PosAssign(hodPos, LocalDate.of(2024, 1, 1))),
                staffRole);

        staff("STF017", "dawsusu@gmail.com", "Daw Su Su", "09-8080808", isUnit,
                List.of(new PosAssign(lecturerPos, LocalDate.of(2024, 1, 1))),
                staffRole);

        staff("STF018", "kyawmin@gmail.com", "U Kyaw Min", "09-9090909", isUnit,
                List.of(new PosAssign(lecturerPos, LocalDate.of(2024, 1, 1))),
                staffRole);

        // ── Lecturers (ITSM) ────────────────────────────────────
        staff("STF019", "minzaw@gmail.com", "U Min Zaw", "09-1112222", itsmUnit,
                List.of(new PosAssign(lecturerPos, LocalDate.of(2024, 1, 1)),
                        new PosAssign(hodPos, LocalDate.of(2024, 1, 1))),
                staffRole);

        staff("STF020", "yeemon@gmail.com", "Daw Yee Mon", "09-2223333", itsmUnit,
                List.of(new PosAssign(lecturerPos, LocalDate.of(2024, 1, 1))),
                staffRole);

        staff("STF021", "soewin@gmail.com", "U Soe Win", "09-3334444", itsmUnit,
                List.of(new PosAssign(lecturerPos, LocalDate.of(2024, 1, 1))),
                staffRole);

        // ── Lecturers (Computing) ───────────────────────────────
        staff("STF022", "myintthein@gmail.com", "U Myint Thein", "09-4445555", compUnit,
                List.of(new PosAssign(lecturerPos, LocalDate.of(2024, 1, 1)),
                        new PosAssign(hodPos, LocalDate.of(2024, 1, 1))),
                staffRole);

        staff("STF023", "chawchaw@gmail.com", "Daw Chaw Chaw", "09-5556666", compUnit,
                List.of(new PosAssign(lecturerPos, LocalDate.of(2024, 1, 1))),
                staffRole);

        staff("STF024", "thura@gmail.com", "U Thura", "09-6667777", compUnit,
                List.of(new PosAssign(lecturerPos, LocalDate.of(2024, 1, 1))),
                staffRole);

        // ── Lecturers (Natural Language) ────────────────────────
        staff("STF025", "khinkhin@gmail.com", "Daw Khin Khin", "09-7778888", nlUnit,
                List.of(new PosAssign(lecturerPos, LocalDate.of(2024, 1, 1)),
                        new PosAssign(hodPos, LocalDate.of(2024, 1, 1))),
                staffRole);

        staff("STF026", "aungmin@gmail.com", "U Aung Min", "09-8889999", nlUnit,
                List.of(new PosAssign(lecturerPos, LocalDate.of(2024, 1, 1))),
                staffRole);

        staff("STF027", "hninwai@gmail.com", "Daw Hnin Wai", "09-9990000", nlUnit,
                List.of(new PosAssign(lecturerPos, LocalDate.of(2024, 1, 1))),
                staffRole);

        // ── Lecturers (Natural Science) ─────────────────────────
        staff("STF028", "phyothura@gmail.com", "U Phyo Thura", "09-1212121", nsUnit,
                List.of(new PosAssign(lecturerPos, LocalDate.of(2024, 1, 1)),
                        new PosAssign(hodPos, LocalDate.of(2024, 1, 1))),
                staffRole);

        staff("STF029", "nilar@gmail.com", "Daw Nilar", "09-3232323", nsUnit,
                List.of(new PosAssign(lecturerPos, LocalDate.of(2024, 1, 1))),
                staffRole);

        // ── Student Affairs ─────────────────────────────────────
        staff("STF004", "kohtet@gmail.com", "Ko Htet", "09-4444444", saUnit,
                List.of(new PosAssign(saoPos, LocalDate.of(2024, 1, 1)),
                        new PosAssign(scPos, LocalDate.of(2024, 6, 1))),
                staffRole);

        staff("STF005", "mama@gmail.com", "Ma Ma", "09-5555555", saUnit,
                List.of(new PosAssign(saoPos, LocalDate.of(2024, 1, 1)),
                        new PosAssign(jcPos, LocalDate.of(2024, 6, 1))),
                staffRole);

        staff("STF006", "dawsu@gmail.com", "Daw Su", "09-6666666", saUnit,
                List.of(new PosAssign(saoPos, LocalDate.of(2024, 1, 1)),
                        new PosAssign(hodPos, LocalDate.of(2024, 1, 1))),
                staffRole);

        // ── Finance ─────────────────────────────────────────────
        staff("STF007", "komyo@gmail.com", "Ko Myo", "09-7777777", finUnit,
                List.of(new PosAssign(foPos, LocalDate.of(2024, 1, 1))),
                staffRole);

        staff("STF008", "dawyin@gmail.com", "Daw Yin", "09-8888888", finUnit,
                List.of(new PosAssign(foPos, LocalDate.of(2024, 1, 1)),
                        new PosAssign(scPos, LocalDate.of(2024, 1, 1))),
                staffRole);

        staff("STF009", "makhin@gmail.com", "Ma Khin", "09-9999999", finUnit,
                List.of(new PosAssign(foPos, LocalDate.of(2024, 1, 1)),
                        new PosAssign(hodPos, LocalDate.of(2024, 1, 1)),
                        new PosAssign(scPos, LocalDate.of(2024, 6, 1))),
                staffRole);

        // ── Administration ──────────────────────────────────────
        staff("STF010", "dawnwe@gmail.com", "Daw Nwe", "09-1010101", admUnit,
                List.of(new PosAssign(aoPos, LocalDate.of(2024, 1, 1)),
                        new PosAssign(hodPos, LocalDate.of(2024, 1, 1))),
                staffRole);

        staff("STF011", "dawphyu@gmail.com", "Daw Phyu", "09-2020202", admUnit,
                List.of(new PosAssign(aoPos, LocalDate.of(2024, 1, 1)),
                        new PosAssign(jcPos, LocalDate.of(2024, 6, 1))),
                staffRole);

        staff("STF012", "komyo2@gmail.com", "Ko Zaw", "09-3030303", admUnit,
                List.of(new PosAssign(aoPos, LocalDate.of(2024, 1, 1)),
                        new PosAssign(scPos, LocalDate.of(2024, 1, 1))),
                staffRole);

        // ── Admin's staff record ────────────────────────────────
        adminStaff = staff("STF000", ADMIN_EMAIL, "System Admin", "09-0000000", csUnit,
                List.of(new PosAssign(lecturerPos, LocalDate.of(2024, 1, 1))),
                staffRole);

        log.info("Staff seeded (30 total: 20 lecturers, 3 student affairs, 3 finance, 3 administration, 1 admin)");
    }

    private Staff staff(String staffNo, String email, String name, String phone,
                        OrganizationalUnit unit, List<PosAssign> positions, Role role) {
        if (staffRepository.existsByStaffNo(staffNo)) {
            return staffRepository.findByStaffNo(staffNo).orElse(null);
        }

        User user;
        if (userRepository.existsByEmail(email)) {
            user = userRepository.findByEmail(email).orElse(null);
            if (user == null) return null;
        } else {
            user = new User();
            user.setEmail(email);
            user.setPasswordHash(passwordEncoder.encode(STAFF_PASSWORD));
            user.setRole(role);
            user.setActive(true);
            user.setRegistrationStatus(RegistrationStatus.APPROVED);
            user = userRepository.save(user);
        }

        Staff s = new Staff();
        s.setUser(user);
        s.setStaffNo(staffNo);
        s.setStaffName(name);
        s.setPhoneNo(phone);
        s.setUnit(unit);
        s.setJoinedAt(LocalDate.of(2024, 1, 1));
        s = staffRepository.save(s);

        for (PosAssign pa : positions) {
            if (pa.position != null && !spaRepository.existsByStaff_StaffIdAndPosition_PositionIdAndStartDate(
                    s.getStaffId(), pa.position.getPositionId(), pa.startDate)) {
                StaffPositionAssignment spa = new StaffPositionAssignment();
                spa.setStaff(s);
                spa.setPosition(pa.position);
                spa.setStartDate(pa.startDate);
                spaRepository.save(spa);
            }
        }
        return s;
    }

    private record PosAssign(Position position, LocalDate startDate) {}

    // ── Students ───────────────────────────────────────────────

    private void seedStudents() {
        if (studentRepository.count() > 0) {
            log.info("Students already present ({}), skipping student seed", studentRepository.count());
            return;
        }

        Role studentRole = roleRepository.findByRoleName("STUDENT")
                .orElseThrow(() -> new RuntimeException("STUDENT role not found"));

        AcademicTerm term = termRepository.findByStatus(TermStatus.ACTIVE).stream().findFirst()
                .orElseThrow(() -> new RuntimeException("No active academic term"));

        Map<Integer, Semester> semesters = new HashMap<>();
        semesterRepository.findAll().forEach(s -> semesters.put(s.getSemesterNo(), s));

        Map<String, Section> sections = new HashMap<>();
        sectionRepository.findAll().forEach(s -> sections.put(s.getSectionName(), s));

        Map<String, Major> majors = new HashMap<>();
        majorRepository.findAll().forEach(m -> majors.put(m.getMajorCode(), m));

        Set<String> existingEmails = new HashSet<>(userRepository.findAll()
                .stream().map(User::getEmail).toList());
        Set<String> usedBases = new HashSet<>();
        existingEmails.forEach(e -> usedBases.add(e.toLowerCase().replaceAll("[^a-z]", "")));

        String[] namePool = {
            "Kyaw Kyaw","Aung Aung","Min Min","Ko Ko","Htet Htet","Mya Mya","Su Su","Thiri Win",
            "Aung Min","Kyaw Zin","Zaw Zaw","Nay Nay","Bo Bo","Thet Thet","Ei Ei","Hnin Hnin",
            "Khin Khin","May May","Yamin","Zin Zin","Phyu Phyu","Sandi","Wai Wai","Linn Linn",
            "Thant Zin","Hla Hla","Cho Cho","Nilar","Moe Moe","Aye Aye","Htet Aung","Kyaw Thu",
            "Zin Mar","Hnin Wai","Su Thiri","Aye Mya","Thandar","Khin Sandar","Nyein Nyein",
            "Zin Myo","Min Thu","Aung Ko","Kyaw Swar","Htet Lin","Wai Yan","Phyo Phyo","Thiha",
            "Kaung Kaung","Sithu","Myint Myint","Htet Naing","Aung Myo","Kyaw Min","Zar Ni",
            "Pyae Phyo","Naing Naing","Htun Htun","Thein Thein","San San","Aye Chan","Min Khant",
            "Ko Zaw","Aung Kyaw","Ye Ye","Kaung Myat","Thura","Zaw Min","Min Zaw","Htoo Htoo","Sai Sai"
        };
        Map<String, Integer> baseCounter = new HashMap<>();
        for (String base : usedBases) baseCounter.put(base, 100);

        int[] semestersToSeed = {2, 4, 6, 8};
        String[] sectionNames = {"A", "B", "C"};
        String[] majorCodes = {"CS", "CT", "CST"};

        int rollNumber = 1001;
        Set<String> usedRolls = new HashSet<>(studentRepository.findAll()
                .stream().map(Student::getRollNo).toList());
        int totalStudents = 0;

        for (int sem : semestersToSeed) {
            for (int i = 0; i < 60; i++) {
                String secName = sectionNames[i % 3];
                String majorCode = majorCodes[(i / 3) % 3];
                String name = namePool[totalStudents % namePool.length];
                String base = name.toLowerCase().replaceAll("[^a-z]", "");
                int counter = baseCounter.merge(base, 100, Math::max);
                String email;
                do {
                    email = String.format("%s%d@gmail.com", base, counter++);
                } while (existingEmails.contains(email));
                baseCounter.put(base, counter);
                existingEmails.add(email);

                String rollNo;
                do {
                    rollNo = String.format("UCSTGO-%04d", rollNumber++);
                } while (usedRolls.contains(rollNo));
                usedRolls.add(rollNo);

                User user = new User();
                user.setEmail(email);
                user.setPasswordHash(passwordEncoder.encode(STUDENT_PASSWORD));
                user.setRole(studentRole);
                user.setActive(true);
                user.setRegistrationStatus(RegistrationStatus.APPROVED);
                user = userRepository.save(user);

                Student student = new Student();
                student.setUser(user);
                student.setMajor(majors.get(majorCode));
                student.setSemester(semesters.get(sem));
                student.setSection(sections.get(secName));
                student.setTerm(term);
                student.setRollNo(rollNo);
                student.setStudentName(name);
                student.setPhoneNo(String.format("09-%07d", totalStudents + 1));
                student.setBatchYear(2000 + (8 - sem));
                studentRepository.save(student);

                totalStudents++;
            }
        }

        log.info("Students seeded: {} total across semesters 2,4,6,8 x sections A,B,C x majors CS,CT,CST (balanced)", totalStudents);
    }
}
