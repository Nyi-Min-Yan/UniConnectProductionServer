package com.unicconnect.repository;

import com.unicconnect.entity.Semester;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SemesterRepository extends JpaRepository<Semester, UUID> {
    Optional<Semester> findBySemesterNo(Integer semesterNo);
}