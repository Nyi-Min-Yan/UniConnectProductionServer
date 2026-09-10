package com.unicconnect.repository;

import com.unicconnect.entity.Major;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MajorRepository extends JpaRepository<Major, UUID> {
    List<Major> findByUnit_UnitId(UUID unitId);
    Optional<Major> findByMajorCode(String majorCode);
    Optional<Major> findByMajorName(String majorName);
}