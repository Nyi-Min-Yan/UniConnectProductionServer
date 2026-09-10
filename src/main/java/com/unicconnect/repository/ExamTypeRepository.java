package com.unicconnect.repository;

import com.unicconnect.entity.ExamType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ExamTypeRepository extends JpaRepository<ExamType, UUID> {
    Optional<ExamType> findByExamTypeName(String examTypeName);
    boolean existsByExamTypeName(String examTypeName);
}