package com.unicconnect.repository;

import com.unicconnect.entity.AcademicTerm;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AcademicTermRepository extends JpaRepository<AcademicTerm, UUID> {
    List<AcademicTerm> findByStatus(com.unicconnect.entity.TermStatus status);
    Optional<AcademicTerm> findByAcademicYear(String academicYear);
}