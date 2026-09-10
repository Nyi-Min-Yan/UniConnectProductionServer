package com.unicconnect.service;

import com.unicconnect.entity.OrganizationalUnit;
import com.unicconnect.exception.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for OrganizationalUnitResolver against the exact organizational
 * units currently present in the database (the database is the source of
 * truth; these names are duplicated here only to exercise the resolver).
 */
class OrganizationalUnitResolverTest {

    private OrganizationalUnitResolver resolver;
    private List<OrganizationalUnit> units;

    @BeforeEach
    void setUp() {
        resolver = new OrganizationalUnitResolver(null);
        units = List.of(
                unit("ITSM", "Department of Information Technologies Support and Maintenance", "ACADEMIC"),
                unit("NL", "Department of Natural Language", "ACADEMIC"),
                unit("NS", "Department of Natural Science", "ACADEMIC"),
                unit("ADM", "Department of Administration", "ADMINISTRATIVE"),
                unit("FIN", "Department of Finance", "ADMINISTRATIVE"),
                unit("SA", "Department of Student Affairs", "ADMINISTRATIVE"),
                unit("FCS", "Faculty of Computer Science", "ACADEMIC"),
                unit("FCST", "Faculty of Computer Systems and Technologies", "ACADEMIC"),
                unit("FIS", "Faculty of Information Science", "ACADEMIC"),
                unit("FC", "Faculty of Computing", "ACADEMIC")
        );
    }

    private static OrganizationalUnit unit(String code, String name, String type) {
        OrganizationalUnit u = new OrganizationalUnit();
        u.setUnitCode(code);
        u.setUnitName(name);
        u.setUnitType(type);
        return u;
    }

    private OrganizationalUnit resolve(String value) {
        return resolver.resolve(units, value);
    }

    @Test
    void resolvesExactNames() {
        assertEquals("FIN", resolve("Department of Finance").getUnitCode());
        assertEquals("SA", resolve("Department of Student Affairs").getUnitCode());
        assertEquals("ADM", resolve("Department of Administration").getUnitCode());
        assertEquals("NS", resolve("Department of Natural Science").getUnitCode());
    }

    @Test
    void resolvesReorderedDepartmentNames() {
        assertEquals("FIN", resolve("Finance Department").getUnitCode());
        assertEquals("SA", resolve("Student Affairs Department").getUnitCode());
        assertEquals("ADM", resolve("Administrative Department").getUnitCode());
    }

    @Test
    void resolvesNormalizedWhitespaceAndCase() {
        assertEquals("FIN", resolve("   FINANCE  DEPARTMENT   ").getUnitCode());
        assertEquals("FIN", resolve("finance department").getUnitCode());
        assertEquals("SA", resolve("STUDENT AFFAIRS DEPARTMENT").getUnitCode());
    }

    @Test
    void resolvesByUnitCode() {
        assertEquals("FIN", resolve("FIN").getUnitCode());
        assertEquals("NS", resolve("ns").getUnitCode());
    }

    @Test
    void resolvesAmpersandVariant() {
        assertEquals("ITSM", resolve("Department of Information Technologies Support & Maintenance").getUnitCode());
    }

    @Test
    void computerDepartmentIsAmbiguous() {
        // "Computer Department" is a structural value that matches several
        // existing computer-related units (FCS, FCST, FC) - never guessed.
        ValidationException ex = assertThrows(ValidationException.class, () -> resolve("Computer Department"));
        assertTrue(ex.getMessage().contains("could not be uniquely resolved"), ex.getMessage());
    }

    @Test
    void bareNameResolvesToExistingUnit() {
        // Human-readable Excel values map to the existing database records.
        assertEquals("FCS", resolve("Computer Science").getUnitCode());
        assertEquals("FCST", resolve("Computer Systems & Technologies").getUnitCode());
        assertEquals("FIS", resolve("Information Science").getUnitCode());
        assertEquals("ITSM", resolve("Information Technologies Support and Maintenance").getUnitCode());
        assertEquals("FC", resolve("Computing").getUnitCode());
        assertEquals("NL", resolve("Language").getUnitCode());
        assertEquals("NS", resolve("Natural Science").getUnitCode());
        assertEquals("ADM", resolve("Administration").getUnitCode());
        assertEquals("FIN", resolve("Finance").getUnitCode());
        assertEquals("SA", resolve("Student Affairs").getUnitCode());
    }

    @Test
    void canonicalFacultyNamesResolveUniquely() {
        // After duplicate consolidation each official name maps to one record.
        assertEquals("FCS", resolve("Faculty of Computer Science").getUnitCode());
        assertEquals("FCST", resolve("Faculty of Computer Systems and Technologies").getUnitCode());
        assertEquals("FIS", resolve("Faculty of Information Science").getUnitCode());
        assertEquals("FC", resolve("Faculty of Computing").getUnitCode());
    }

    @Test
    void unknownUnitIsNotFound() {
        ValidationException ex = assertThrows(ValidationException.class, () -> resolve("Department of Human Resources"));
        assertTrue(ex.getMessage().contains("was not found"), ex.getMessage());
        ValidationException ex2 = assertThrows(ValidationException.class, () -> resolve("Mechanical Engineering"));
        assertTrue(ex2.getMessage().contains("was not found"), ex2.getMessage());
    }

    @Test
    void normalizationsBehaveGenerically() {
        assertEquals("department of finance", OrganizationalUnitResolver.reorderDepartment("finance department"));
        assertEquals(null, OrganizationalUnitResolver.reorderDepartment("department of finance"));
        assertEquals("department of administr",
                OrganizationalUnitResolver.stemName(OrganizationalUnitResolver.normalize("administrative department")));
        assertEquals("department of administr",
                OrganizationalUnitResolver.stemName(OrganizationalUnitResolver.normalize("department of administration")));
        assertNotNull(resolve("finance department"));
    }
}
