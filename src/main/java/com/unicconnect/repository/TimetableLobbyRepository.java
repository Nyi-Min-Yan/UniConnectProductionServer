package com.unicconnect.repository;

import com.unicconnect.entity.LobbyStatus;
import com.unicconnect.entity.TimetableLobby;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TimetableLobbyRepository extends JpaRepository<TimetableLobby, UUID> {
    List<TimetableLobby> findAllByOrderByCreatedAtDesc();
    Optional<TimetableLobby> findFirstByStatusInOrderByCreatedAtDesc(List<LobbyStatus> statuses);
    boolean existsByStatusIn(List<LobbyStatus> statuses);
    Optional<TimetableLobby> findByGeneration_GenerationId(UUID generationId);
    Optional<TimetableLobby> findFirstByStatusInAndTerm_TermId(List<LobbyStatus> statuses, UUID termId);
    List<TimetableLobby> findByStatusIn(List<LobbyStatus> statuses);
}
