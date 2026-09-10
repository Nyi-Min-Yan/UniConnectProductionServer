package com.unicconnect.controller;

import com.unicconnect.entity.LobbyStatus;
import com.unicconnect.entity.Staff;
import com.unicconnect.entity.TimetableLobby;
import com.unicconnect.entity.TimetableLobbyMember;
import com.unicconnect.exception.ResourceNotFoundException;
import com.unicconnect.repository.GenerationSessionRepository;
import com.unicconnect.repository.StaffRepository;
import com.unicconnect.repository.TimetableLobbyMemberRepository;
import com.unicconnect.repository.TimetableLobbyRepository;
import com.unicconnect.service.HodAccessService;
import com.unicconnect.service.TimetableLobbyAccessService;
import com.unicconnect.service.TimetableRealtimeEventService;
import com.unicconnect.util.SecurityUtil;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

/**
 * Lobby-scoped Server-Sent Events stream.
 *
 * <p>Registration is authorization-gated: only the authenticated staff member who
 * is an actual member of the lobby (leader, or joined member) may open the
 * stream. The {@code lobby_id} in the URL is never trusted as a scoping signal —
 * it must match the caller's membership.
 */
@RestController
@RequestMapping("/api/realtime")
public class TimetableRealtimeController {

    private final TimetableRealtimeEventService realtime;
    private final SecurityUtil securityUtil;
    private final StaffRepository staffRepository;
    private final TimetableLobbyRepository lobbyRepository;
    private final TimetableLobbyMemberRepository memberRepository;
    private final GenerationSessionRepository generationRepository;
    private final HodAccessService hodAccessService;
    private final TimetableLobbyAccessService lobbyAccessService;

    public TimetableRealtimeController(TimetableRealtimeEventService realtime,
                                       SecurityUtil securityUtil,
                                       StaffRepository staffRepository,
                                       TimetableLobbyRepository lobbyRepository,
                                       TimetableLobbyMemberRepository memberRepository,
                                       GenerationSessionRepository generationRepository,
                                       HodAccessService hodAccessService,
                                       TimetableLobbyAccessService lobbyAccessService) {
        this.realtime = realtime;
        this.securityUtil = securityUtil;
        this.staffRepository = staffRepository;
        this.lobbyRepository = lobbyRepository;
        this.memberRepository = memberRepository;
        this.generationRepository = generationRepository;
        this.hodAccessService = hodAccessService;
        this.lobbyAccessService = lobbyAccessService;
    }

    @GetMapping("/lobbies/{lobbyId}/stream")
    public ResponseEntity<SseEmitter> stream(@PathVariable UUID lobbyId) {
        UUID userId = securityUtil.currentUserId();
        Staff staff = staffRepository.findByUser_UserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Staff profile not found"));

        TimetableLobby lobby = lobbyRepository.findById(lobbyId)
                .orElseThrow(() -> new ResourceNotFoundException("Timetable generation lobby not found"));

        if (lobby.getStatus() == LobbyStatus.CANCELLED) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        TimetableLobbyMember membership = memberRepository
                .findByLobby_LobbyIdAndStaff_StaffId(lobbyId, staff.getStaffId())
                .orElse(null);
        boolean isLeader = lobby.getLeaderStaff().getStaffId().equals(staff.getStaffId());
        boolean joined = membership != null && (isLeader || membership.getJoinedAt() != null);
        if (!joined) {
            // Only lobby members may open the realtime stream for that lobby.
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        SseEmitter emitter = new SseEmitter(0L);
        realtime.register(lobbyId, staff.getStaffId(), emitter);
        emitter.onCompletion(() -> realtime.unregister(lobbyId, staff.getStaffId(), emitter));
        emitter.onTimeout(() -> realtime.unregister(lobbyId, staff.getStaffId(), emitter));
        emitter.onError((e) -> realtime.unregister(lobbyId, staff.getStaffId(), emitter));
        return ResponseEntity.ok(emitter);
    }

    /**
     * Generation-scoped realtime stream. Works for both lobby-linked and direct
     * (lobby-less) drafts: any HOD who may share the draft can subscribe, so
     * live drag/lock/schedule events reach every HOD browser viewing the
     * generation even when no active generation lobby exists.
     */
    @GetMapping("/generations/{generationId}/stream")
    public ResponseEntity<SseEmitter> generationStream(@PathVariable UUID generationId) {
        UUID userId = securityUtil.currentUserId();
        Staff staff = staffRepository.findByUser_UserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Staff profile not found"));

        if (!generationRepository.existsById(generationId)) {
            throw new ResourceNotFoundException("Timetable generation not found");
        }
        if (hodAccessService.currentHod().isEmpty()) {
            // The shared workspace is an HOD surface; non-HOD staff may not watch it.
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (!lobbyAccessService.canAccessSharedDraft(generationId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        SseEmitter emitter = new SseEmitter(0L);
        realtime.registerForGeneration(generationId, staff.getStaffId(), emitter);
        emitter.onCompletion(() -> realtime.unregisterForGeneration(generationId, staff.getStaffId(), emitter));
        emitter.onTimeout(() -> realtime.unregisterForGeneration(generationId, staff.getStaffId(), emitter));
        emitter.onError((e) -> realtime.unregisterForGeneration(generationId, staff.getStaffId(), emitter));
        return ResponseEntity.ok(emitter);
    }
}
