package com.unicconnect.service;

import com.unicconnect.entity.OrganizationalUnit;
import com.unicconnect.exception.ValidationException;
import com.unicconnect.repository.OrganizationalUnitRepository;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Resolves a human-friendly organizational-unit value (for example an Excel
 * "unit" cell such as "Finance Department") to the actual ORGANIZATIONAL_UNITS
 * database record by querying the table directly. No unit ids or canonical
 * names are hard-coded here: the database is the only source of truth.
 */
@Service
public class OrganizationalUnitResolver {

    private static final Set<String> STOP_TOKENS = Set.of("of", "the", "and", "department", "faculty");

    private final OrganizationalUnitRepository unitRepository;

    public OrganizationalUnitResolver(OrganizationalUnitRepository unitRepository) {
        this.unitRepository = unitRepository;
    }

    /** Loads every organizational-unit record currently in the database. */
    public List<OrganizationalUnit> allUnits() {
        return unitRepository.findAll();
    }

    /** Resolves a value against the current database contents. */
    public OrganizationalUnit resolve(String value) {
        return resolve(unitRepository.findAll(), value);
    }

    /** Resolves a value against an already-loaded list of unit records. */
    public OrganizationalUnit resolve(Collection<OrganizationalUnit> units, String value) {
        String raw = value == null ? "" : value.trim();
        if (raw.isEmpty()) {
            throw new ValidationException("Organizational unit is required.");
        }

        String normalized = normalize(raw);

        // 1. Exact normalized match against unit name or unit code.
        List<OrganizationalUnit> exact = units.stream()
                .filter(u -> normalize(u.getUnitName()).equals(normalized)
                        || normalize(u.getUnitCode()).equals(normalized))
                .toList();
        if (exact.size() == 1) {
            return exact.get(0);
        }
        if (exact.size() > 1) {
            throw ambiguity(raw);
        }

        // 2. Generic "X Department" <-> "Department of X" reorder plus a light
        // morphological comparison ("Administrative Department" ~ "Department
        // of Administration"). The comparison stays exact on the normalized
        // stems, so it only ever matches a real database record.
        String stemInput = stemName(normalized);
        String stemReordered = reorderDepartment(normalized) != null
                ? stemName(reorderDepartment(normalized)) : null;
        List<OrganizationalUnit> reorderedMatches = units.stream()
                .filter(u -> {
                    String stemName = stemName(normalize(u.getUnitName()));
                    String stemCode = stemName(normalize(u.getUnitCode()));
                    return stemName.equals(stemInput) || stemCode.equals(stemInput)
                            || (stemReordered != null
                            && (stemName.equals(stemReordered) || stemCode.equals(stemReordered)));
                })
                .toList();
        if (reorderedMatches.size() == 1) {
            return reorderedMatches.get(0);
        }
        if (reorderedMatches.size() > 1) {
            throw ambiguity(raw);
        }

        // 3. Bare-name token match: a value that does not carry a structural
        // "Faculty of ..." / "... Department" prefix or suffix can resolve to
        // the existing record whose plain name matches it, e.g.
        // "Computer Science" -> Faculty of Computer Science,
        // "Computing" -> Faculty of Computing, "Finance" -> Department of
        // Finance. This is a lookup only: it never creates a record.
        if (!isStructural(normalized)) {
            Set<String> inputRawTokens = rawCoreTokens(normalized);
            if (!inputRawTokens.isEmpty()) {
                List<OrganizationalUnit> tokenExact = units.stream()
                        .filter(u -> rawCoreTokens(normalize(u.getUnitName())).equals(inputRawTokens))
                        .toList();
                if (tokenExact.size() == 1) {
                    return tokenExact.get(0);
                }
                if (tokenExact.size() > 1) {
                    throw ambiguity(raw);
                }

                Set<String> inputStemmed = stemTokens(inputRawTokens);
                List<OrganizationalUnit> tokenSubset = units.stream()
                        .filter(u -> stemTokens(rawCoreTokens(normalize(u.getUnitName())))
                                .containsAll(inputStemmed))
                        .toList();
                if (tokenSubset.size() == 1) {
                    return tokenSubset.get(0);
                }
                if (tokenSubset.size() > 1) {
                    throw ambiguity(raw);
                }
            }
        }

        // 4. Ambiguity guard: if the value's core tokens are shared by several
        // units (e.g. "Computer Department" overlaps several computer-related
        // faculties), fail instead of guessing. This step never resolves.
        Set<String> inputTokens = coreTokens(normalized);
        if (!inputTokens.isEmpty()) {
            List<OrganizationalUnit> overlapping = units.stream()
                    .filter(u -> {
                        Set<String> unitTokens = coreTokens(normalize(u.getUnitName()));
                        unitTokens.retainAll(inputTokens);
                        return !unitTokens.isEmpty();
                    })
                    .toList();
            if (overlapping.size() > 1) {
                throw ambiguity(raw);
            }
        }

        throw new ValidationException("Organizational unit '" + raw + "' was not found.");
    }

    private static ValidationException ambiguity(String raw) {
        return new ValidationException(
                "Organizational unit '" + raw + "' could not be uniquely resolved. "
                        + "Multiple organizational units match this value.");
    }

    // ------------------------------------------------------------------
    // Normalization
    // ------------------------------------------------------------------

    /** Trims, collapses repeated whitespace, folds case and softens punctuation. */
    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String collapsed = value.trim()
                .replace("&", " and ")
                .replaceAll("[^a-zA-Z0-9 ]", " ")
                .toLowerCase(Locale.ROOT);
        return collapsed.replaceAll("\\s+", " ").trim();
    }

    /**
     * "X Department" -> "Department of X" (only when the value ends with the
     * literal word "Department"), otherwise returns null.
     */
    static String reorderDepartment(String normalized) {
        if (normalized == null || !normalized.endsWith("department")) {
            return null;
        }
        int index = normalized.lastIndexOf("department");
        String prefix = normalized.substring(0, index).trim();
        if (prefix.isEmpty()) {
            return null;
        }
        return "department of " + prefix;
    }

    /** Light, conservative morphological stem applied to each token. */
    static String stemToken(String token) {
        String t = token;
        if (t.endsWith("ative")) {
            t = t.substring(0, t.length() - 5);
        } else if (t.endsWith("ation")) {
            t = t.substring(0, t.length() - 5);
        } else if (t.endsWith("ing")) {
            t = t.substring(0, t.length() - 3);
        } else if (t.endsWith("ive")) {
            t = t.substring(0, t.length() - 3);
        } else if (t.endsWith("ion")) {
            t = t.substring(0, t.length() - 3);
        } else if (t.endsWith("ies")) {
            t = t.substring(0, t.length() - 3) + "y";
        } else if (t.endsWith("er")) {
            t = t.substring(0, t.length() - 2);
        } else if (t.endsWith("s") && !t.endsWith("ss") && t.length() > 3) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }

    static String stemName(String normalized) {
        return Arrays.stream(normalized.split(" "))
                .filter(token -> !token.isEmpty())
                .map(OrganizationalUnitResolver::stemToken)
                .collect(Collectors.joining(" "));
    }

    /** Meaningful tokens of a normalized name (stop words removed, stemmed). */
    private static Set<String> coreTokens(String normalized) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : normalized.split(" ")) {
            if (token.isEmpty() || STOP_TOKENS.contains(token)) {
                continue;
            }
            String stemmed = stemToken(token);
            if (!stemmed.isEmpty()) {
                tokens.add(stemmed);
            }
        }
        return tokens;
    }

    /** Meaningful raw (unstemmed) tokens of a normalized name. */
    private static Set<String> rawCoreTokens(String normalized) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : normalized.split(" ")) {
            if (!token.isEmpty() && !STOP_TOKENS.contains(token)) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private static Set<String> stemTokens(Set<String> tokens) {
        Set<String> out = new LinkedHashSet<>();
        for (String token : tokens) {
            String stemmed = stemToken(token);
            if (!stemmed.isEmpty()) {
                out.add(stemmed);
            }
        }
        return out;
    }

    /** True when the value carries a structural "Faculty of ..."/"... Department" marker. */
    private static boolean isStructural(String normalized) {
        return normalized.endsWith("department") || normalized.endsWith("faculty")
                || normalized.startsWith("department of") || normalized.startsWith("faculty of");
    }
}
