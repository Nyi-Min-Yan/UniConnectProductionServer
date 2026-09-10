package com.unicconnect.repository;

import com.unicconnect.entity.ClassSchedule;
import com.unicconnect.entity.ScheduleStatus;
import com.unicconnect.entity.ScheduleType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ClassScheduleRepository extends JpaRepository<ClassSchedule, UUID> {

    List<ClassSchedule> findByGeneration_GenerationId(UUID generationId);

    @Query("SELECT DISTINCT s FROM ClassSchedule s" + FETCH_JOINS
            + " WHERE s.generation.generationId = :generationId")
    List<ClassSchedule> findByGeneration_GenerationIdWithDetails(@Param("generationId") UUID generationId);

    String FETCH_JOINS = " JOIN FETCH s.generation g"
            + " LEFT JOIN FETCH s.teachingAssignment a"
            + " LEFT JOIN FETCH a.course c"
            + " LEFT JOIN FETCH a.staff st"
            + " LEFT JOIN FETCH a.section sec"
            + " JOIN FETCH s.startSlot"
            + " JOIN FETCH s.endSlot";

    // Matches shared (combined-section) schedules: a schedule row belongs to the
    // given section when its teaching assignment targets it OR when one of its
    // teaching group members targets it. plain (non-fetch) joins keep the result
    // rows deduplicated via DISTINCT. The member/assignment joins are EXPLICIT
    // LEFT JOINs: on Hibernate 6 implicit joins are INNER and would silently
    // drop every schedule that has no teaching group.
    String GROUP_JOINS = " LEFT JOIN s.teachingGroup tg"
            + " LEFT JOIN tg.members tgm"
            + " LEFT JOIN tgm.assignment ta"
            + " LEFT JOIN ta.section tsec"
            + " LEFT JOIN ta.staff tst";

    @Query("SELECT DISTINCT s FROM ClassSchedule s" + FETCH_JOINS + " WHERE g.term.termId = :termId")
    List<ClassSchedule> findByTermIdWithDetails(@Param("termId") UUID termId);

    @Query("SELECT DISTINCT s FROM ClassSchedule s" + FETCH_JOINS + GROUP_JOINS
            + " WHERE a.section.sectionId = :sectionId"
            + " OR tsec.sectionId = :sectionId")
    List<ClassSchedule> findBySectionIdWithDetails(@Param("sectionId") UUID sectionId);

    @Query("SELECT DISTINCT s FROM ClassSchedule s" + FETCH_JOINS + GROUP_JOINS
            + " WHERE a.staff.staffId = :staffId"
            + " OR tst.staffId = :staffId")
    List<ClassSchedule> findByStaffIdWithDetails(@Param("staffId") UUID staffId);

    @Query("SELECT DISTINCT s FROM ClassSchedule s" + FETCH_JOINS + " WHERE s.dayOfWeek = :dayOfWeek")
    List<ClassSchedule> findByDayOfWeekWithDetails(@Param("dayOfWeek") Integer dayOfWeek);

    @Query("SELECT DISTINCT s FROM ClassSchedule s" + FETCH_JOINS)
    List<ClassSchedule> findAllWithDetails();

    @Query("SELECT DISTINCT s FROM ClassSchedule s" + FETCH_JOINS + GROUP_JOINS
            + " WHERE g.term.termId = :termId"
            + " AND (sec.sectionId = :sectionId OR tsec.sectionId = :sectionId)")
    List<ClassSchedule> findByTermAndSectionWithDetails(@Param("termId") UUID termId,
                                                        @Param("sectionId") UUID sectionId);

    boolean existsByGeneration_GenerationIdAndTeachingAssignment_Staff_StaffId(
            UUID generationId, UUID staffId);

    boolean existsByTeachingAssignment_Staff_StaffIdAndDayOfWeekAndScheduleStatusNot(
            UUID staffId, Integer dayOfWeek, ScheduleStatus excluded);

    boolean existsByTeachingAssignment_Section_SectionIdAndDayOfWeekAndScheduleStatusNot(
            UUID sectionId, Integer dayOfWeek, ScheduleStatus excluded);

    @Query("SELECT COUNT(s) > 0 FROM ClassSchedule s WHERE s.generation.generationId = :generationId " +
            "AND s.scheduleStatus <> com.unicconnect.entity.ScheduleStatus.CANCELLED " +
            "AND s.dayOfWeek = :dayOfWeek AND (" +
            "   (s.startSlot.displayOrder <= :endOrder AND s.endSlot.displayOrder >= :startOrder) " +
            "   OR (s.startSlot.slotId = :slotId))")
    boolean hasSlotOverlap(@Param("generationId") UUID generationId,
                           @Param("dayOfWeek") Integer dayOfWeek,
                           @Param("startOrder") Integer startOrder,
                           @Param("endOrder") Integer endOrder,
                           @Param("slotId") UUID slotId);

    boolean existsByTeachingAssignment_AssignmentIdAndScheduleStatusNot(
            UUID assignmentId, ScheduleStatus excluded);

    boolean existsByTeachingGroup_GroupId(UUID groupId);
}