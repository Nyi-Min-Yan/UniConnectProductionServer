package com.unicconnect.repository;

import com.unicconnect.entity.Section;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SectionRepository extends JpaRepository<Section, UUID> {
    Optional<Section> findBySectionName(String sectionName);
}