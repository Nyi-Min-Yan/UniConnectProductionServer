package com.unicconnect.repository;

import com.unicconnect.entity.ClassSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ClassSessionRepository extends JpaRepository<ClassSession, UUID> {
    List<ClassSession> findBySchedule_ScheduleId(UUID scheduleId);
    boolean existsBySchedule_ScheduleIdAndSessionDate(UUID scheduleId, java.time.LocalDate sessionDate);
    java.util.Optional<ClassSession> findBySchedule_ScheduleIdAndSessionDate(UUID scheduleId, java.time.LocalDate sessionDate);
    List<ClassSession> findBySchedule_ScheduleIdInAndSessionDateBetween(java.util.List<java.util.UUID> scheduleIds, java.time.LocalDate start, java.time.LocalDate end);
    List<ClassSession> findBySchedule_ScheduleIdInAndSessionDateBetweenOrderBySessionDateAsc(java.util.List<java.util.UUID> scheduleIds, java.time.LocalDate start, java.time.LocalDate end);
    List<ClassSession> findBySchedule_ScheduleIdAndSessionDateBetweenOrderBySessionDateAsc(java.util.UUID scheduleId, java.time.LocalDate start, java.time.LocalDate end);
}
