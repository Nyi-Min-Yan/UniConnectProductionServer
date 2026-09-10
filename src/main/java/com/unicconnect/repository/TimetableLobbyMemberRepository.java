package com.unicconnect.repository;

import com.unicconnect.entity.TimetableLobbyMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TimetableLobbyMemberRepository extends JpaRepository<TimetableLobbyMember, UUID> {
    List<TimetableLobbyMember> findByLobby_LobbyIdOrderByInvitedAtAsc(UUID lobbyId);
    Optional<TimetableLobbyMember> findByLobby_LobbyIdAndStaff_StaffId(UUID lobbyId, UUID staffId);
    boolean existsByLobby_LobbyIdAndJoinedAtIsNull(UUID lobbyId);
}
