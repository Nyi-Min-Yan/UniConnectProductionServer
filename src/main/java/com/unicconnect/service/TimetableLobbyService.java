package com.unicconnect.service;

import com.unicconnect.dto.request.CreateLobbyRequest;
import com.unicconnect.dto.request.InviteLobbyMemberRequest;
import com.unicconnect.dto.response.TimetableLobbyMemberResponse;
import com.unicconnect.dto.response.TimetableLobbyResponse;
import com.unicconnect.entity.*;
import com.unicconnect.exception.BusinessRuleException;
import com.unicconnect.exception.ResourceNotFoundException;
import com.unicconnect.repository.*;
import com.unicconnect.util.SecurityUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
public class TimetableLobbyService {

    private static final List<LobbyStatus> ACTIVE_STATUSES =
            List.of(LobbyStatus.OPEN, LobbyStatus.GENERATING);

    private final TimetableLobbyRepository lobbyRepository;
    private final TimetableLobbyMemberRepository memberRepository;
    private final StaffRepository staffRepository;
    private final StaffPositionAssignmentRepository assignmentRepository;
    private final AcademicTermRepository termRepository;
    private final GenerationSessionRepository generationRepository;
    private final TimetableRealtimeEventService realtimeEventService;
    private final SecurityUtil securityUtil;

    public TimetableLobbyService(TimetableLobbyRepository lobbyRepository,
                                 TimetableLobbyMemberRepository memberRepository,
                                 StaffRepository staffRepository,
                                 StaffPositionAssignmentRepository assignmentRepository,
                                 AcademicTermRepository termRepository,
                                 GenerationSessionRepository generationRepository,
                                 TimetableRealtimeEventService realtimeEventService,
                                 SecurityUtil securityUtil) {
        this.lobbyRepository = lobbyRepository;
        this.memberRepository = memberRepository;
        this.staffRepository = staffRepository;
        this.assignmentRepository = assignmentRepository;
        this.termRepository = termRepository;
        this.generationRepository = generationRepository;
        this.realtimeEventService = realtimeEventService;
        this.securityUtil = securityUtil;
    }

    public List<TimetableLobbyResponse> list() {
        return lobbyRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toResponse)
                .toList();
    }

    public TimetableLobbyResponse getById(UUID lobbyId) {
        return toResponse(findLobby(lobbyId));
    }

    public TimetableLobbyResponse create(CreateLobbyRequest request) {
        Staff leader = currentHod();
        if (lobbyRepository.existsByStatusIn(ACTIVE_STATUSES)) {
            throw new BusinessRuleException(
                    "Only one timetable generation lobby can be active at a time");
        }
        AcademicTerm term = request != null && request.termId() != null
                ? termRepository.findById(request.termId())
                        .orElseThrow(() -> new ResourceNotFoundException("Academic term not found"))
                : activeTerm();

        TimetableLobby lobby = new TimetableLobby();
        lobby.setTerm(term);
        lobby.setLeaderStaff(leader);
        lobby.setStatus(LobbyStatus.OPEN);
        lobby = lobbyRepository.save(lobby);

        TimetableLobbyMember leadMember = new TimetableLobbyMember();
        leadMember.setLobby(lobby);
        leadMember.setStaff(leader);
        leadMember.setJoinedAt(Instant.now());
        memberRepository.save(leadMember);

        // Auto-invite every other active HOD lecturer.
        for (Staff hod : activeHodLecturers(leader.getStaffId())) {
            TimetableLobbyMember member = new TimetableLobbyMember();
            member.setLobby(lobby);
            member.setStaff(hod);
            memberRepository.save(member);
        }
        return toResponse(lobby);
    }

    public TimetableLobbyResponse join(UUID lobbyId) {
        TimetableLobby lobby = findLobby(lobbyId);
        requireOpen(lobby);
        Staff staff = currentHod();
        TimetableLobbyMember member = memberRepository
                .findByLobby_LobbyIdAndStaff_StaffId(lobbyId, staff.getStaffId())
                .orElseThrow(() -> new BusinessRuleException(
                        "You have not been invited to this lobby"));
        if (member.getJoinedAt() == null) {
            member.setJoinedAt(Instant.now());
            memberRepository.save(member);
            // Push an instant join notification so every waiting-room view
            // (leader + other members) updates its progress bar without polling.
            // Deferred until commit so subscribers can never observe the event
            // before the joined state is actually readable from the database.
            final UUID joinedLobbyId = lobby.getLobbyId();
            final String staffId = staff.getStaffId().toString();
            final String staffName = staff.getStaffName();
            afterCommit(() -> realtimeEventService.publish(joinedLobbyId,
                    TimetableRealtimeEventService.LOBBY_MEMBER_JOINED,
                    Map.of("lobbyId", joinedLobbyId,
                            "staffId", staffId,
                            "staffName", staffName)));
        }
        return toResponse(lobby);
    }

    public TimetableLobbyResponse invite(UUID lobbyId, InviteLobbyMemberRequest request) {
        TimetableLobby lobby = findLobby(lobbyId);
        requireOpen(lobby);
        requireLeader(lobby);
        Staff target = staffRepository.findById(request.staffId())
                .orElseThrow(() -> new ResourceNotFoundException("Staff member not found"));
        requireActiveHod(target);
        if (memberRepository.findByLobby_LobbyIdAndStaff_StaffId(lobbyId, target.getStaffId())
                .isPresent()) {
            throw new BusinessRuleException("This HOD is already in the lobby");
        }
        TimetableLobbyMember member = new TimetableLobbyMember();
        member.setLobby(lobby);
        member.setStaff(target);
        memberRepository.save(member);
        return toResponse(lobby);
    }

    public TimetableLobbyResponse cancel(UUID lobbyId) {
        TimetableLobby lobby = findLobby(lobbyId);
        requireActive(lobby);
        requireLeader(lobby);
        lobby.setStatus(LobbyStatus.CANCELLED);
        TimetableLobby saved = lobbyRepository.save(lobby);
        final UUID cancelledLobbyId = lobby.getLobbyId();
        afterCommit(() -> realtimeEventService.publish(cancelledLobbyId,
                TimetableRealtimeEventService.LOBBY_CANCELLED,
                Map.of("lobbyId", cancelledLobbyId)));
        return toResponse(saved);
    }

    /**
     * Starts the shared timetable management workspace for the lobby.
     *
     * <p>This is <b>not</b> timetable generation: it verifies the creator, that
     * every invited HOD has joined, and then creates (or reuses) the single
     * shared draft generation that every lobby member will work on. All joined
     * members are notified via a lobby-scoped realtime event so their browsers
     * navigate to the management page automatically. The actual Mid/Final,
     * semester/section selection and generation happen inside that workspace.
     */
    public TimetableLobbyResponse generate(UUID lobbyId) {
        TimetableLobby lobby = findLobby(lobbyId);
        requireActive(lobby);
        requireLeader(lobby);

        long waiting = memberRepository.findByLobby_LobbyIdOrderByInvitedAtAsc(lobbyId).stream()
                .filter(m -> m.getJoinedAt() == null)
                .count();
        if (waiting > 0) {
            throw new BusinessRuleException(
                    "Timetable management is blocked until every invited HOD joins "
                            + "(" + waiting + " still waiting)");
        }

        GenerationSession session = lobby.getGeneration();
        if (session == null) {
            // One active generation per lobby: create it only once.
            session = new GenerationSession();
            session.setTerm(lobby.getTerm());
            session.setGeneratedByStaff(lobby.getLeaderStaff());
            session.setStatus(GenerationStatus.PENDING);
            session = generationRepository.save(session);
            lobby.setGeneration(session);
        }
        lobby.setStatus(LobbyStatus.GENERATING);
        lobby = lobbyRepository.save(lobby);

        // The event must reach clients only AFTER the generation session and the
        // lobby transition are committed. Publishing inside the transaction let a
        // fast subscriber call GET /generations/{id} before commit and receive a
        // 404 ("Generation session not found") — the exact reported race.
        final UUID startedLobbyId = lobby.getLobbyId();
        final UUID generationId = session.getGenerationId();
        final UUID termId = lobby.getTerm().getTermId();
        afterCommit(() -> realtimeEventService.publishManagementStarted(
                startedLobbyId, generationId, termId));
        return toResponse(lobby);
    }

    // ---------- Helpers ----------

    /**
     * Runs the given realtime fan-out after the current transaction commits.
     * SSE consumers act on events immediately (navigate, re-fetch by id), so an
     * event that escapes before commit exposes uncommitted state as a 404.
     * When no transaction is active the action runs inline.
     */
    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private TimetableLobby findLobby(UUID lobbyId) {
        return lobbyRepository.findById(lobbyId)
                .orElseThrow(() -> new ResourceNotFoundException("Timetable generation lobby not found"));
    }

    private void requireActive(TimetableLobby lobby) {
        if (lobby.getStatus() != LobbyStatus.OPEN && lobby.getStatus() != LobbyStatus.GENERATING) {
            throw new BusinessRuleException("This lobby is no longer active");
        }
    }

    private void requireOpen(TimetableLobby lobby) {
        if (lobby.getStatus() != LobbyStatus.OPEN) {
            throw new BusinessRuleException("This lobby is no longer open");
        }
    }

    private void requireLeader(TimetableLobby lobby) {
        UUID current = currentStaff().getStaffId();
        if (!lobby.getLeaderStaff().getStaffId().equals(current)) {
            throw new BusinessRuleException("Only the lobby leader can perform this action");
        }
    }

    private Staff currentStaff() {
        return staffRepository.findByUser_UserId(securityUtil.currentUserId())
                .orElseThrow(() -> new BusinessRuleException(
                        "Only staff can use the timetable generation lobby"));
    }

    private Staff currentHod() {
        Staff staff = currentStaff();
        requireActiveHod(staff);
        return staff;
    }

    private void requireActiveHod(Staff staff) {
        if (!isActiveHodLecturer(staff)) {
            throw new BusinessRuleException(
                    "Only HOD lecturers can use the timetable generation lobby");
        }
    }

    private boolean isActiveHodLecturer(Staff staff) {
        LocalDate today = LocalDate.now();
        Set<String> active = assignmentRepository.findByStaff_StaffId(staff.getStaffId()).stream()
                .filter(pa -> isActiveAssignment(pa, today))
                .map(pa -> pa.getPosition().getPositionName())
                .collect(Collectors.toSet());
        return active.contains("HOD") && active.contains("LECTURER");
    }

    /**
     * All active HOD lecturers (academic departments/faculties) except the excluded
     * staff member. These are the automatically invited lobby members.
     */
    private List<Staff> activeHodLecturers(UUID excludeStaffId) {
        LocalDate today = LocalDate.now();
        List<StaffPositionAssignment> allAssignments = assignmentRepository.findAllWithPositionAndStaff();
        Set<UUID> hodIds = allAssignments.stream()
                .filter(pa -> isActiveAssignment(pa, today))
                .filter(pa -> "HOD".equals(pa.getPosition().getPositionName()))
                .map(pa -> pa.getStaff().getStaffId())
                .collect(Collectors.toSet());
        Set<UUID> lecturerIds = allAssignments.stream()
                .filter(pa -> isActiveAssignment(pa, today))
                .filter(pa -> "LECTURER".equals(pa.getPosition().getPositionName()))
                .map(pa -> pa.getStaff().getStaffId())
                .collect(Collectors.toSet());
        Map<UUID, Staff> staffById = staffRepository.findAllWithUserAndUnit().stream()
                .collect(Collectors.toMap(Staff::getStaffId, s -> s));
        return staffById.values().stream()
                .filter(s -> !s.getStaffId().equals(excludeStaffId))
                .filter(s -> hodIds.contains(s.getStaffId()) && lecturerIds.contains(s.getStaffId()))
                .sorted(Comparator.comparing(Staff::getStaffName))
                .toList();
    }

    private boolean isActiveAssignment(StaffPositionAssignment pa, LocalDate today) {
        return !pa.getStartDate().isAfter(today)
                && (pa.getEndDate() == null || !pa.getEndDate().isBefore(today));
    }

    private AcademicTerm activeTerm() {
        return termRepository.findByStatus(TermStatus.ACTIVE).stream().findFirst()
                .orElseThrow(() -> new BusinessRuleException("No active academic term found"));
    }

    private TimetableLobbyResponse toResponse(TimetableLobby lobby) {
        List<TimetableLobbyMemberResponse> members = memberRepository
                .findByLobby_LobbyIdOrderByInvitedAtAsc(lobby.getLobbyId()).stream()
                .map(this::toMemberResponse)
                .toList();
        return new TimetableLobbyResponse(
                lobby.getLobbyId(),
                lobby.getTerm().getTermId(),
                lobby.getTerm().getAcademicYear(),
                lobby.getLeaderStaff().getStaffId(),
                lobby.getLeaderStaff().getStaffNo(),
                lobby.getLeaderStaff().getStaffName(),
                lobby.getStatus(),
                lobby.getGeneration() != null ? lobby.getGeneration().getGenerationId() : null,
                lobby.getCreatedAt(),
                members);
    }

    private TimetableLobbyMemberResponse toMemberResponse(TimetableLobbyMember member) {
        Staff staff = member.getStaff();
        return new TimetableLobbyMemberResponse(
                member.getMemberId(),
                staff.getStaffId(),
                staff.getStaffNo(),
                staff.getStaffName(),
                staff.getUnit() != null ? staff.getUnit().getUnitName() : null,
                member.getInvitedAt(),
                member.getJoinedAt(),
                member.getJoinedAt() != null);
    }
}
