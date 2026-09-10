package com.unicconnect.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "attendance")
@Getter
@Setter
@NoArgsConstructor
public class Attendance {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "attendance_id")
    private UUID attendanceId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private ClassSession session;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    private Student student;

    @Enumerated(EnumType.STRING)
    @Column(name = "attendance_status", nullable = false, length = 20)
    private AttendanceStatus attendanceStatus;

    @Column(name = "remark")
    private String remark;

    @CreationTimestamp
    @Column(name = "marked_at", nullable = false, updatable = false)
    private Instant markedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "marked_by_staff_id")
    private Staff markedByStaff;

    /**
     * Actual attended range for THIS student. PRESENT requires both; ABSENT
     * requires both to be NULL. The scheduled range lives on the schedule.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "attendance_start_slot_id")
    private TimeSlot attendanceStartSlot;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "attendance_end_slot_id")
    private TimeSlot attendanceEndSlot;
}