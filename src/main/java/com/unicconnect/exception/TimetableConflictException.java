package com.unicconnect.exception;

import java.util.List;

/**
 * Raised when a timetable cannot be published because unresolved scheduling
 * conflicts remain. Carries the full list of structured conflict details so
 * clients can render them all at once instead of failing on the first one.
 */
public class TimetableConflictException extends RuntimeException {

    private final List<String> conflicts;

    public TimetableConflictException(List<String> conflicts) {
        super("Cannot publish timetable: " + conflicts.size()
                + " unresolved scheduling conflict" + (conflicts.size() == 1 ? "" : "s") + " found");
        this.conflicts = List.copyOf(conflicts);
    }

    public List<String> conflicts() {
        return conflicts;
    }
}