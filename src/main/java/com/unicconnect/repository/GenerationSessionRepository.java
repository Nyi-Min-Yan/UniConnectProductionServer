package com.unicconnect.repository;

import com.unicconnect.entity.GenerationSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GenerationSessionRepository extends JpaRepository<GenerationSession, UUID> {
    List<GenerationSession> findByTerm_TermIdOrderByCreatedAtDesc(UUID termId);
    Optional<GenerationSession> findFirstByTerm_TermIdAndStatusOrderByCreatedAtDesc(
            UUID termId, com.unicconnect.entity.GenerationStatus status);
    boolean existsByTerm_TermIdAndStatus(UUID termId, com.unicconnect.entity.GenerationStatus status);
    Optional<GenerationSession> findFirstByStatusAndPublishedAtIsNotNullOrderByPublishedAtDesc(
            com.unicconnect.entity.GenerationStatus status);
}
