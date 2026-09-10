package com.unicconnect.repository;

import com.unicconnect.entity.CourseMeetingRequirement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CourseMeetingRequirementRepository extends JpaRepository<CourseMeetingRequirement, UUID> {
    List<CourseMeetingRequirement> findByCourse_CourseId(UUID courseId);
    Optional<CourseMeetingRequirement> findByCourse_CourseIdAndMeetingType(UUID courseId, com.unicconnect.entity.MeetingType meetingType);
    boolean existsByCourse_CourseIdAndMeetingType(UUID courseId, com.unicconnect.entity.MeetingType meetingType);

    @Query("select r from CourseMeetingRequirement r join fetch r.course c where c.courseId in :courseIds")
    List<CourseMeetingRequirement> findAllByCourse_CourseIdIn(@Param("courseIds") Collection<UUID> courseIds);
}