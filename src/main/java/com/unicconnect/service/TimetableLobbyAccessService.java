package com.unicconnect.service;

import com.unicconnect.entity.LobbyStatus;
import com.unicconnect.entity.Staff;
import com.unicconnect.entity.TimetableLobby;
import com.unicconnect.exception.BusinessRuleException;
import com.unicconnect.repository.TimetableLobbyMemberRepository;
import com.unicconnect.repository.TimetableLobbyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Shared-draft authorization for timetable generation lobbies (req 3/33).
 *
 * <p>A non-published generation owned by a live lobby may only be accessed by
 * the lobby leader or a joined lobby member — invited-but-not-joined staff and
 * unrelated HODs are rejected, no matter what generationId is supplied. The
 * authoritative chain is: authenticated staff → lobby membership →
 * {@code timetable_lobbies.generation_id} → the actual {@code GenerationSession}.
 *
 * <p>Generations not linked to any lobby follow the normal HOD draft rules.
 */
@Service
@Transactional
public class TimetableLobbyAccessService {

    private final TimetableLobbyRepository lobbyRepository;
    private final TimetableLobbyMemberRepository lobbyMemberRepository;
    private final HodAccessService hodAccessService;

    public TimetableLobbyAccessService(TimetableLobbyRepository lobbyRepository,
                                       TimetableLobbyMemberRepository lobbyMemberRepository,
                                       HodAccessService hodAccessService) {
        this.lobbyRepository = lobbyRepository;
        this.lobbyMemberRepository = lobbyMemberRepository;
        this.hodAccessService = hodAccessService;
    }

    /**
     * Non-throwing check: {@code true} when the generation is not owned by a
     * live lobby, or the current authenticated staff member is the lobby leader
     * or a joined lobby member.
     */
    public boolean canAccessSharedDraft(UUID generationId) {
        Optional<TimetableLobby> lobby = lobbyRepository.findByGeneration_GenerationId(generationId);
        if (lobby.isEmpty()) {
            return true;
        }
        TimetableLobby l = lobby.get();
        if (l.getStatus() == LobbyStatus.CANCELLED) {
            return false;
        }
        Optional<Staff> hod = hodAccessService.currentHod();
        if (hod.isEmpty()) {
            return false;
        }
        Staff staff = hod.get();
        if (l.getLeaderStaff().getStaffId().equals(staff.getStaffId())) {
            return true;
        }
        return lobbyMemberRepository
                .findByLobby_LobbyIdAndStaff_StaffId(l.getLobbyId(), staff.getStaffId())
                .map(m -> m.getJoinedAt() != null)
                .orElse(false);
    }

    /**
     * Throwing variant used by mutating endpoints: returns the current HOD or
     * rejects the operation for non-members of the owning lobby.
     */
    public Staff requireSharedDraftAccess(UUID generationId) {
        Staff hod = hodAccessService.requireHod();
        if (!canAccessSharedDraft(generationId)) {
            throw new BusinessRuleException(
                    "Only the lobby leader or joined lobby members can access this shared draft");
        }
        return hod;
    }
}
