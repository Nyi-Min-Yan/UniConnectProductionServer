package com.unicconnect.repository;

import com.unicconnect.entity.Course;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CourseRepository extends JpaRepository<Course, UUID> {
    Optional<Course> findByCourseCode(String courseCode);
    boolean existsByCourseCode(String courseCode);
    List<Course> findByMajor_MajorId(UUID majorId);
    List<Course> findBySemester_SemesterId(UUID semesterId);
    List<Course> findByUnit_UnitId(UUID unitId);
}