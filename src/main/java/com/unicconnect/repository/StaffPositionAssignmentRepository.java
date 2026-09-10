package com.unicconnect.repository;

import com.unicconnect.entity.Staff;
import com.unicconnect.entity.StaffPositionAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StaffPositionAssignmentRepository extends JpaRepository<StaffPositionAssignment, UUID> {
    List<StaffPositionAssignment> findByStaff_StaffId(UUID staffId);
    Optional<StaffPositionAssignment> findByStaff_StaffIdAndPositionAssignmentId(UUID staffId, UUID assignmentId);
    boolean existsByStaff_StaffIdAndPosition_PositionIdAndStartDate(UUID staffId, UUID positionId, LocalDate startDate);
    boolean existsByStaff_StaffId(UUID staffId);

    @Query("select spa from StaffPositionAssignment spa join fetch spa.position join fetch spa.staff")
    List<StaffPositionAssignment> findAllWithPositionAndStaff();
}