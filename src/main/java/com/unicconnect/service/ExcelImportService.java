package com.unicconnect.service;

import com.unicconnect.dto.request.CreateStaffUserRequest;
import com.unicconnect.dto.request.CreateStudentUserRequest;
import com.unicconnect.dto.response.ImportError;
import com.unicconnect.dto.response.ImportResultResponse;
import com.unicconnect.entity.AcademicTerm;
import com.unicconnect.entity.Major;
import com.unicconnect.entity.OrganizationalUnit;
import com.unicconnect.entity.Section;
import com.unicconnect.entity.Semester;
import com.unicconnect.entity.TermStatus;
import com.unicconnect.exception.ValidationException;
import com.unicconnect.repository.AcademicTermRepository;
import com.unicconnect.repository.MajorRepository;
import com.unicconnect.repository.SectionRepository;
import com.unicconnect.repository.SemesterRepository;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class ExcelImportService {

    private static final Set<String> STAFF_TYPES = Set.of(
            "LECTURER", "STUDENT_AFFAIRS", "FINANCE", "ADMINISTRATIVE");

    private static final Map<String, String> DEFAULT_POSITION = Map.of(
            "LECTURER", "LECTURER",
            "STUDENT_AFFAIRS", "STUDENT_AFFAIRS_OFFICER",
            "FINANCE", "FINANCE_OFFICER",
            "ADMINISTRATIVE", "ADMINISTRATIVE_OFFICER");

    private static final java.util.regex.Pattern SEMESTER_PATTERN =
            java.util.regex.Pattern.compile("(?i)^(?:semester\\s*)?(\\d+)$");

    private final StudentService studentService;
    private final StaffService staffService;
    private final MajorRepository majorRepository;
    private final SectionRepository sectionRepository;
    private final SemesterRepository semesterRepository;
    private final AcademicTermRepository termRepository;
    private final OrganizationalUnitResolver unitResolver;

    public ExcelImportService(StudentService studentService,
                              StaffService staffService,
                              MajorRepository majorRepository,
                              SectionRepository sectionRepository,
                              SemesterRepository semesterRepository,
                              AcademicTermRepository termRepository,
                              OrganizationalUnitResolver unitResolver) {
        this.studentService = studentService;
        this.staffService = staffService;
        this.majorRepository = majorRepository;
        this.sectionRepository = sectionRepository;
        this.semesterRepository = semesterRepository;
        this.termRepository = termRepository;
        this.unitResolver = unitResolver;
    }

    // ------------------------------------------------------------------
    // Students
    // ------------------------------------------------------------------

    public ImportResultResponse importStudents(MultipartFile file) {
        List<ImportError> errors = new ArrayList<>();
        int created = 0;
        try (InputStream in = file.getInputStream();
             Workbook workbook = WorkbookFactory.create(in)) {
            Sheet sheet = workbook.getSheetAt(0);
            Map<String, Integer> header = headerIndex(sheet.getRow(sheet.getFirstRowNum()));
            for (Row row : sheet) {
                if (row.getRowNum() == sheet.getFirstRowNum() || isEmptyRow(row)) {
                    continue;
                }
                try {
                    createStudentFromRow(header, row);
                    created++;
                } catch (Exception ex) {
                    errors.add(new ImportError(row.getRowNum() + 1, message(ex)));
                }
            }
        } catch (IOException ex) {
            throw new ValidationException("Could not read the uploaded file: " + ex.getMessage());
        }
        return new ImportResultResponse(created, errors);
    }

    private void createStudentFromRow(Map<String, Integer> header, Row row) {
        String email = str(row, header, "email");
        String password = str(row, header, "password");
        String name = firstNonBlank(str(row, header, "name"), str(row, header, "studentname"));
        String rollNo = firstNonBlank(str(row, header, "rollno"), str(row, header, "roll"));
        String majorValue = str(row, header, "major");

        require(email, "Email is required");
        require(password, "Password is required");
        if (password.length() < 8) {
            throw new ValidationException("Password must be at least 8 characters");
        }
        require(name, "Student name is required");
        require(rollNo, "Roll number is required");
        require(majorValue, "Major is required");

        Major major = resolveMajor(majorValue);
        Section section = optionalResolve(str(row, header, "section"), this::resolveSection);
        Integer semesterNo = parseSemesterNo(str(row, header, "semester"));
        Semester semester = semesterNo != null ? resolveSemester(semesterNo) : null;
        AcademicTerm term = resolveTerm(str(row, header, "academicterm"));
        Integer batchYear = parseInt(str(row, header, "batchyear"), "Batch year");

        studentService.createWithUser(new CreateStudentUserRequest(
                email, password, name, rollNo,
                major.getMajorId(),
                semester != null ? semester.getSemesterId() : null,
                section != null ? section.getSectionId() : null,
                term != null ? term.getTermId() : null,
                str(row, header, "phonenumber"),
                batchYear,
                str(row, header, "address"),
                true));
    }

    // ------------------------------------------------------------------
    // Staff
    // ------------------------------------------------------------------

    public ImportResultResponse importStaff(MultipartFile file, String type) {
        if (type == null || !STAFF_TYPES.contains(type.trim().toUpperCase(Locale.ROOT))) {
            throw new ValidationException("Unsupported staff type: " + type);
        }
        String normalizedType = type.trim().toUpperCase(Locale.ROOT);
        List<ImportError> errors = new ArrayList<>();
        int created = 0;
        try (InputStream in = file.getInputStream();
             Workbook workbook = WorkbookFactory.create(in)) {
            Sheet sheet = workbook.getSheetAt(0);
            Map<String, Integer> header = headerIndex(sheet.getRow(sheet.getFirstRowNum()));
            List<OrganizationalUnit> units = unitResolver.allUnits();
            for (Row row : sheet) {
                if (row.getRowNum() == sheet.getFirstRowNum() || isEmptyRow(row)) {
                    continue;
                }
                try {
                    createStaffFromRow(normalizedType, header, row, units);
                    created++;
                } catch (Exception ex) {
                    errors.add(new ImportError(row.getRowNum() + 1, message(ex)));
                }
            }
        } catch (IOException ex) {
            throw new ValidationException("Could not read the uploaded file: " + ex.getMessage());
        }
        return new ImportResultResponse(created, errors);
    }

    private void createStaffFromRow(String type, Map<String, Integer> header, Row row, List<OrganizationalUnit> units) {
        String email = str(row, header, "email");
        String password = str(row, header, "password");
        String name = firstNonBlank(str(row, header, "name"),
                str(row, header, "staffname"), str(row, header, "lecturername"));
        String unitValue = str(row, header, "unit");

        require(email, "Email is required");
        require(password, "Password is required");
        if (password.length() < 8) {
            throw new ValidationException("Password must be at least 8 characters");
        }
        require(name, "Staff name is required");
        require(unitValue, "Unit is required");

        OrganizationalUnit unit = unitResolver.resolve(units, unitValue);
        Integer batchYear = parseInt(str(row, header, "batchyear"), "Batch year");
        LocalDate joinedAt = parseDate(str(row, header, "joinedat"));

        staffService.createWithUser(new CreateStaffUserRequest(
                email, password, name,
                str(row, header, "staffno"),
                unit.getUnitId(),
                str(row, header, "phonenumber"),
                batchYear,
                str(row, header, "address"),
                joinedAt,
                buildPositions(type, str(row, header, "position")),
                true));
    }

    private List<String> buildPositions(String type, String positionValue) {
        String defaultPosition = DEFAULT_POSITION.get(type);
        List<String> positions = new ArrayList<>();
        positions.add(defaultPosition);
        if (positionValue != null && !positionValue.isBlank()) {
            String normalized = positionValue.trim().toUpperCase(Locale.ROOT)
                    .replaceAll("[ -]", "_");
            if (!normalized.equals(defaultPosition) && !positions.contains(normalized)) {
                positions.add(normalized);
            }
        }
        return positions;
    }

    // ------------------------------------------------------------------
    // Resolution helpers
    // ------------------------------------------------------------------

    private Major resolveMajor(String value) {
        String v = value.trim().toLowerCase(Locale.ROOT);
        return majorRepository.findAll().stream()
                .filter(m -> m.getMajorCode().toLowerCase(Locale.ROOT).equals(v)
                        || m.getMajorName().toLowerCase(Locale.ROOT).equals(v))
                .findFirst()
                .orElseThrow(() -> new ValidationException("Major '" + value + "' was not found."));
    }

    private Section resolveSection(String value) {
        String v = value.trim().toLowerCase(Locale.ROOT);
        return sectionRepository.findAll().stream()
                .filter(s -> s.getSectionName().toLowerCase(Locale.ROOT).equals(v))
                .findFirst()
                .orElseThrow(() -> new ValidationException("Section '" + value + "' was not found."));
    }

    private Semester resolveSemester(int semesterNo) {
        return semesterRepository.findAll().stream()
                .filter(s -> s.getSemesterNo() != null && s.getSemesterNo() == semesterNo)
                .findFirst()
                .orElseThrow(() -> new ValidationException("Semester '" + semesterNo + "' was not found."));
    }

    private AcademicTerm resolveTerm(String value) {
        if (value == null || value.isBlank()) {
            return termRepository.findByStatus(TermStatus.ACTIVE).stream().findFirst()
                    .orElseThrow(() -> new ValidationException("No active academic term was found."));
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        return termRepository.findAll().stream()
                .filter(t -> t.getAcademicYear() != null
                        && t.getAcademicYear().toLowerCase(Locale.ROOT).equals(v))
                .findFirst()
                .orElseThrow(() -> new ValidationException("Academic term '" + value + "' was not found."));
    }

    // ------------------------------------------------------------------
    // Cell / row helpers
    // ------------------------------------------------------------------

    private Map<String, Integer> headerIndex(Row header) {
        Map<String, Integer> index = new HashMap<>();
        if (header == null) {
            return index;
        }
        for (Cell cell : header) {
            String name = normalizeHeader(cellText(cell));
            if (!name.isEmpty()) {
                index.putIfAbsent(name, cell.getColumnIndex());
            }
        }
        return index;
    }

    private String normalizeHeader(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private String str(Row row, Map<String, Integer> header, String key) {
        Integer index = header.get(key);
        if (index == null) {
            return null;
        }
        Cell cell = row.getCell(index);
        if (cell == null) {
            return null;
        }
        String value = cellText(cell);
        return value.isEmpty() ? null : value;
    }

    private String cellText(Cell cell) {
        String value = new DataFormatter().formatCellValue(cell).trim();
        return value.replace("\u00A0", " ").trim();
    }

    private boolean isEmptyRow(Row row) {
        if (row == null) {
            return true;
        }
        for (Cell cell : row) {
            if (cell != null && !cellText(cell).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private void require(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new ValidationException(message);
        }
    }

    private Integer parseInt(String value, String label) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            String v = value.trim();
            if (v.contains(".")) {
                return (int) Math.round(Double.parseDouble(v));
            }
            return Integer.parseInt(v);
        } catch (NumberFormatException ex) {
            throw new ValidationException(label + " must be a number (got '" + value + "')");
        }
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException ex) {
            throw new ValidationException("Date must be in YYYY-MM-DD format (got '" + value + "')");
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private <T> T optionalResolve(String value, java.util.function.Function<String, T> resolver) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return resolver.apply(value);
    }

    private Integer parseSemesterNo(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        java.util.regex.Matcher matcher = SEMESTER_PATTERN.matcher(value.trim());
        if (!matcher.matches()) {
            throw new ValidationException("Semester must be a number (got '" + value + "')");
        }
        return Integer.parseInt(matcher.group(1));
    }

    private String message(Exception ex) {
        return ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
    }
}
