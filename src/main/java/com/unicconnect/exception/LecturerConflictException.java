package com.unicconnect.exception;

import java.util.UUID;

/**
 * Raised when a manual timetable MOVE/SWAP would leave a lecturer teaching two
 * classes at the same day + period anywhere in the active generation. These are
 * hard conflicts: never overridable, never partially applied.
 */
public class LecturerConflictException extends RuntimeException {

    private final UUID lecturerId;
    private final String lecturerName;
    private final String day;
    private final String period;
    private final Integer conflictingSemester;
    private final String conflictingSection;
    private final String conflictingCourseCode;

    public LecturerConflictException(UUID lecturerId, String lecturerName, String day, String period,
                                     Integer conflictingSemester, String conflictingSection,
                                     String conflictingCourseCode) {
        super(buildMessage(lecturerName, day, period, conflictingSemester, conflictingSection,
                conflictingCourseCode));
        this.lecturerId = lecturerId;
        this.lecturerName = lecturerName;
        this.day = day;
        this.period = period;
        this.conflictingSemester = conflictingSemester;
        this.conflictingSection = conflictingSection;
        this.conflictingCourseCode = conflictingCourseCode;
    }

    private static String buildMessage(String lecturerName, String day, String period,
                                       Integer conflictingSemester, String conflictingSection,
                                       String conflictingCourseCode) {
        String who = lecturerName != null && !lecturerName.isBlank() ? lecturerName : "The lecturer";
        String where = "at " + day + " " + period;
        if (conflictingCourseCode != null && !conflictingCourseCode.isBlank()) {
            String what = conflictingCourseCode;
            if (conflictingSemester != null && conflictingSection != null
                    && !conflictingSection.isBlank()) {
                what += " in Semester " + conflictingSemester + " Section " + conflictingSection;
            }
            return "Cannot place this schedule: " + who + " already teaches " + what + " " + where + ".";
        }
        return "Cannot place this schedule: " + who + " is already engaged " + where + ".";
    }

    public UUID getLecturerId() {
        return lecturerId;
    }

    public String getLecturerName() {
        return lecturerName;
    }

    public String getDay() {
        return day;
    }

    public String getPeriod() {
        return period;
    }

    public Integer getConflictingSemester() {
        return conflictingSemester;
    }

    public String getConflictingSection() {
        return conflictingSection;
    }

    public String getConflictingCourseCode() {
        return conflictingCourseCode;
    }
}