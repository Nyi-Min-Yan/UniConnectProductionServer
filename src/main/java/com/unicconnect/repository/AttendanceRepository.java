package com.unicconnect.repository;

import com.unicconnect.entity.Attendance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface AttendanceRepository extends JpaRepository<Attendance, UUID> {
    List<Attendance> findBySession_SessionId(UUID sessionId);
    void deleteBySession_SessionId(UUID sessionId);
    List<Attendance> findByStudent_StudentId(UUID studentId);
    boolean existsBySession_SessionIdAndStudent_StudentId(UUID sessionId, UUID studentId);

    /**
     * Bulk load every attendance decision for many sessions in ONE query
     * (history table must never issue per-student/per-session queries).
     * Slot and staff associations are fetch-joined so the range/lecturer
     * fields can be read after the transaction-scoped query without extra
     * lazy loads.
     */
    @Query("SELECT DISTINCT a FROM Attendance a" +
           " JOIN FETCH a.student stu" +
           " LEFT JOIN FETCH a.attendanceStartSlot ss" +
           " LEFT JOIN FETCH a.attendanceEndSlot se" +
           " LEFT JOIN FETCH a.markedByStaff mb" +
           " WHERE a.session.sessionId IN :sessionIds")
    List<Attendance> findBySession_SessionIdIn(@Param("sessionIds") List<UUID> sessionIds);

    @Query("SELECT COUNT(DISTINCT a.student.studentId) FROM Attendance a WHERE a.session.sessionId = :sessionId")
    long countStudentsBySession(@Param("sessionId") UUID sessionId);

    long countBySession_Schedule_Generation_GenerationId(UUID generationId);
}
