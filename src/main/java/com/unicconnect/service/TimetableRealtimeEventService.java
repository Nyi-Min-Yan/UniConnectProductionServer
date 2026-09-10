package com.unicconnect.service;

import com.unicconnect.entity.LobbyStatus;
import com.unicconnect.entity.TimetableLobby;
import com.unicconnect.repository.TeachingAssignmentRepository;
import com.unicconnect.repository.TimetableLobbyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-Sent Events fan-out for the timetable generation lobby.
 *
 * <p>One {@link SseEmitter} is registered per authenticated lobby member who is
 * currently connected to the lobby's realtime stream (see
 * {@code TimetableRealtimeController}). Every event published here is scoped to
 * a single {@code lobby_id} — a lobby member never receives events from another
 * lobby, and registration itself requires verified lobby membership.
 *
 * <p>Events are a convenience: the database is always the authoritative source,
 * and clients re-fetch full state on reconnect. No business logic depends on a
 * push event having been delivered.
 */
@Service
public class TimetableRealtimeEventService {

    private static final Logger log = LoggerFactory.getLogger(TimetableRealtimeEventService.class);

    // Event names (SSE data payloads carry a "type" field).
    public static final String MANAGEMENT_STARTED = "TIMETABLE_MANAGEMENT_STARTED";
    public static final String EDIT_STARTED = "TIMETABLE_EDIT_STARTED";
    public static final String LOBBY_CANCELLED = "LOBBY_CANCELLED";
    public static final String LOBBY_MEMBER_JOINED = "LOBBY_MEMBER_JOINED";
    public static final String GENERATION_COMPLETED = "GENERATION_COMPLETED";
    public static final String GENERATION_STARTED = "GENERATION_STARTED";
    public static final String GENERATION_FAILED = "GENERATION_FAILED";
    public static final String SCHEDULE_CREATED = "SCHEDULE_CREATED";
    public static final String SCHEDULE_UPDATED = "SCHEDULE_UPDATED";
    public static final String SCHEDULE_DELETED = "SCHEDULE_DELETED";
    public static final String SCHEDULE_LOCKED = "SCHEDULE_LOCKED";
    public static final String SCHEDULE_UNLOCKED = "SCHEDULE_UNLOCKED";
    public static final String DRAG_STARTED = "DRAG_STARTED";
    public static final String DRAG_MOVED = "DRAG_MOVED";
    public static final String DRAG_ENDED = "DRAG_ENDED";
    /** Live undo/redo swap animation relayed to every connected HOD browser. */
    public static final String SWAP_ANIMATED = "SWAP_ANIMATED";
    public static final String TIMETABLE_PUBLISHED = "TIMETABLE_PUBLISHED";
    public static final String TIMETABLE_DELETED = "TIMETABLE_DELETED";
    public static final String COURSE_REQUIREMENT_CREATED = "COURSE_REQUIREMENT_CREATED";
    public static final String COURSE_REQUIREMENT_UPDATED = "COURSE_REQUIREMENT_UPDATED";
    public static final String COURSE_REQUIREMENT_DELETED = "COURSE_REQUIREMENT_DELETED";
    public static final String TEACHING_GROUP_CREATED = "TEACHING_GROUP_CREATED";
    public static final String TEACHING_GROUP_UPDATED = "TEACHING_GROUP_UPDATED";
    public static final String TEACHING_GROUP_DELETED = "TEACHING_GROUP_DELETED";
    /** Shared-snapshot lifecycle state (SNAPSHOT_PREPARING/READY/INVALID/ERROR). */
    public static final String SNAPSHOT_STATE = "SNAPSHOT_STATE";

    private static final List<LobbyStatus> ACTIVE_STATUSES = List.of(LobbyStatus.OPEN, LobbyStatus.GENERATING);

    private final ConcurrentHashMap<UUID, ConcurrentHashMap<UUID, SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, ConcurrentHashMap<UUID, SseEmitter>> generationEmitters = new ConcurrentHashMap<>();
    private final TimetableLobbyRepository lobbyRepository;
    private final TeachingAssignmentRepository assignmentRepository;
    private final ObjectMapper objectMapper;

    public TimetableRealtimeEventService(TimetableLobbyRepository lobbyRepository,
                                         TeachingAssignmentRepository assignmentRepository,
                                         ObjectMapper objectMapper) {
        this.lobbyRepository = lobbyRepository;
        this.assignmentRepository = assignmentRepository;
        this.objectMapper = objectMapper;
    }

    public void register(UUID lobbyId, UUID staffId, SseEmitter emitter) {
        emitters.computeIfAbsent(lobbyId, k -> new ConcurrentHashMap<>()).put(staffId, emitter);
        log.info("Realtime stream registered for lobby {} / staff {}", lobbyId, staffId);
    }

    public void unregister(UUID lobbyId, UUID staffId, SseEmitter emitter) {
        ConcurrentHashMap<UUID, SseEmitter> lobbyEmitters = emitters.get(lobbyId);
        if (lobbyEmitters == null) return;
        lobbyEmitters.remove(staffId, emitter);
        if (lobbyEmitters.isEmpty()) {
            emitters.remove(lobbyId, lobbyEmitters);
        }
        log.info("Realtime stream removed for lobby {} / staff {}", lobbyId, staffId);
    }

    /**
     * Registers a generation-scoped stream. Generation streams work for both
     * lobby-linked and direct (lobby-less) drafts, so live drag/lock/schedule
     * events reach every HOD browser viewing the generation.
     */
    public void registerForGeneration(UUID generationId, UUID staffId, SseEmitter emitter) {
        generationEmitters.computeIfAbsent(generationId, k -> new ConcurrentHashMap<>()).put(staffId, emitter);
        log.info("Realtime generation stream registered for generation {} / staff {}", generationId, staffId);
    }

    public void unregisterForGeneration(UUID generationId, UUID staffId, SseEmitter emitter) {
        ConcurrentHashMap<UUID, SseEmitter> genEmitters = generationEmitters.get(generationId);
        if (genEmitters == null) return;
        genEmitters.remove(staffId, emitter);
        if (genEmitters.isEmpty()) {
            generationEmitters.remove(generationId, genEmitters);
        }
        log.info("Realtime generation stream removed for generation {} / staff {}", generationId, staffId);
    }

    /**
     * Fires a management-started event to every connected member of a lobby so
     * all of them navigate to the shared timetable management workspace.
     */
    public void publishManagementStarted(UUID lobbyId, UUID generationId, UUID termId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("lobbyId", lobbyId);
        payload.put("generationId", generationId);
        payload.put("termId", termId);
        payload.put("mode", "manage");
        publish(lobbyId, MANAGEMENT_STARTED, payload);
    }

    /** Publishes to generation-keyed streams, plus the lobby that owns the generation (if any). */
    public void publishForGeneration(UUID generationId, String type, Map<String, Object> payload) {
        ConcurrentHashMap<UUID, SseEmitter> genEmitters = generationEmitters.get(generationId);
        if (genEmitters != null && !genEmitters.isEmpty()) {
            publishTo(genEmitters, type, payload);
        }
        lobbyRepository.findByGeneration_GenerationId(generationId)
                .ifPresent(lobby -> publish(lobby.getLobbyId(), type, payload));
    }

    /**
     * Broadcasts the shared snapshot lifecycle state to every connected HOD in
     * the lobby that owns the generation, so HOD B immediately sees the same
     * SNAPSHOT_READY that HOD A prepared (requirement 10/11).
     */
    public void publishSnapshotState(UUID generationId, String status, String sourceVersion) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("generationId", generationId);
        payload.put("snapshotStatus", status);
        if (sourceVersion != null) {
            payload.put("sourceVersion", sourceVersion);
        }
        publishForGeneration(generationId, SNAPSHOT_STATE, payload);
    }

    /** Publishes to the single active lobby for a term (if any). */
    public void publishForTerm(UUID termId, String type, Map<String, Object> payload) {
        lobbyRepository.findFirstByStatusInAndTerm_TermId(ACTIVE_STATUSES, termId)
                .ifPresent(lobby -> publish(lobby.getLobbyId(), type, payload));
    }

    /**
     * Publishes to every active lobby whose term teaches the given course. Used
     * for course-meeting-requirement changes, which are term-scoped rather than
     * generation-scoped.
     */
    public void publishForCourse(UUID courseId, String type, Map<String, Object> payload) {
        for (TimetableLobby lobby : lobbyRepository.findByStatusIn(ACTIVE_STATUSES)) {
            if (assignmentRepository.existsByTerm_TermIdAndCourse_CourseId(
                    lobby.getTerm().getTermId(), courseId)) {
                publish(lobby.getLobbyId(), type, payload);
            }
        }
    }

    /** Fans an event out to every connected member of one lobby. */
    public void publish(UUID lobbyId, String type, Map<String, Object> payload) {
        ConcurrentHashMap<UUID, SseEmitter> lobbyEmitters = emitters.get(lobbyId);
        if (lobbyEmitters == null || lobbyEmitters.isEmpty()) return;
        publishTo(lobbyEmitters, type, payload);
    }

    private void publishTo(ConcurrentHashMap<UUID, SseEmitter> targets, String type, Map<String, Object> payload) {
        if (targets == null || targets.isEmpty()) return;

        Map<String, Object> data = new HashMap<>();
        data.put("type", type);
        if (payload != null) data.putAll(payload);

        String json;
        try {
            json = objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            log.warn("Could not serialize realtime event {} payload", type, e);
            return;
        }

        for (Map.Entry<UUID, SseEmitter> entry : targets.entrySet()) {
            SseEmitter emitter = entry.getValue();
            try {
                emitter.send(SseEmitter.event().data(json, MediaType.APPLICATION_JSON));
            } catch (IOException e) {
                targets.remove(entry.getKey(), emitter);
                if (targets.isEmpty()) {
                    log.debug("Realtime targets emptied while broadcasting {}", type);
                }
                log.debug("Dropped disconnected realtime stream for staff {} during {}", entry.getKey(), type);
            }
        }
    }

    /**
     * Periodic heartbeat keeps every SSE connection alive (some proxies close
     * idle connections) and reaps dead emitters so the registry never leaks.
     */
    @Scheduled(fixedDelayString = "25000")
    public void heartbeat() {
        for (ConcurrentHashMap<UUID, SseEmitter> scopeEmitters : emitters.values()) {
            sendHeartbeat(scopeEmitters);
        }
        for (UUID generationId : generationEmitters.keySet()) {
            ConcurrentHashMap<UUID, SseEmitter> genEmitters = generationEmitters.get(generationId);
            if (genEmitters == null) continue;
            sendHeartbeat(genEmitters);
            if (genEmitters.isEmpty()) {
                generationEmitters.remove(generationId, genEmitters);
            }
        }
    }

    private void sendHeartbeat(ConcurrentHashMap<UUID, SseEmitter> scopeEmitters) {
        for (Map.Entry<UUID, SseEmitter> entry : scopeEmitters.entrySet()) {
            try {
                entry.getValue().send(SseEmitter.event().comment("hb"));
            } catch (IOException e) {
                scopeEmitters.remove(entry.getKey(), entry.getValue());
                log.debug("Dropped disconnected realtime stream for staff {}", entry.getKey());
            }
        }
    }
}
