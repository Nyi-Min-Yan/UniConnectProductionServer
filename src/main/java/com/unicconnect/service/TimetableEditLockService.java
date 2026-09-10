package com.unicconnect.service;

import com.unicconnect.dto.response.TimetableLockResponse;
import com.unicconnect.entity.GenerationSession;
import com.unicconnect.entity.GenerationStatus;
import com.unicconnect.entity.Staff;
import com.unicconnect.exception.BusinessRuleException;
import com.unicconnect.exception.ResourceNotFoundException;
import com.unicconnect.repository.GenerationSessionRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single-operator drag/drop operation lock for a timetable generation draft.
 *
 * <p>This is an <b>operation</b> lock, never a permanent timetable lock:
 * <ul>
 *   <li>acquire: fails if another HOD currently holds a live lock on the same
 *       generation; otherwise (or after TTL expiry) the caller becomes the holder.</li>
 *   <li>heartbeat: extends the holder's lease so the lock survives page stays.</li>
 *   <li>release: explicit release on drop / cancel / leaving the workspace.</li>
 *   <li>status: read-only; used for the live "editing by X" indicator.</li>
 *   <li>requireLockOwned: enforced server-side by schedule mutations so a client
 *       cannot bypass the lock by calling the API directly.</li>
 * </ul>
 * Locks always expire after {@link #LEASE} even if the holder disconnects.
 */
@Service
public class TimetableEditLockService {

    private static final Duration LEASE = Duration.ofSeconds(30);

    private final Map<UUID, LockEntry> locks = new ConcurrentHashMap<>();
    private final GenerationSessionRepository generationRepository;
    private final HodAccessService hodAccessService;
    private final TimetableLobbyAccessService lobbyAccessService;
    private final TimetableRealtimeEventService realtimeEventService;

    public TimetableEditLockService(GenerationSessionRepository generationRepository,
                                    HodAccessService hodAccessService,
                                    TimetableLobbyAccessService lobbyAccessService,
                                    TimetableRealtimeEventService realtimeEventService) {
        this.generationRepository = generationRepository;
        this.hodAccessService = hodAccessService;
        this.lobbyAccessService = lobbyAccessService;
        this.realtimeEventService = realtimeEventService;
    }

    public synchronized TimetableLockResponse acquire(UUID generationId) {
        Staff hod = lobbyAccessService.requireSharedDraftAccess(generationId);
        GenerationSession generation = requireDraft(generationId);

        LockEntry entry = locks.get(generationId);
        if (entry != null && entry.isLive() && !entry.staffId.equals(hod.getStaffId())) {
            throw new BusinessRuleException("Timetable is currently being edited by " + entry.staffName);
        }

        LockEntry acquired = new LockEntry(hod.getStaffId(), hod.getStaffName(),
                Instant.now().plus(LEASE));
        locks.put(generationId, acquired);
        publishLocked(generationId, acquired);
        return toResponse(generation, acquired);
    }

    public synchronized TimetableLockResponse heartbeat(UUID generationId) {
        Staff hod = lobbyAccessService.requireSharedDraftAccess(generationId);
        requireDraft(generationId);

        LockEntry entry = locks.get(generationId);
        if (entry == null || !entry.isLive()) {
            throw new BusinessRuleException("No active editing lock for this timetable");
        }
        if (!entry.staffId.equals(hod.getStaffId())) {
            throw new BusinessRuleException("Only " + entry.staffName + " can extend this editing lock");
        }
        entry.expiresAt = Instant.now().plus(LEASE);
        // Re-broadcast so the other lobby members keep seeing the live lease.
        publishLocked(generationId, entry);
        return toResponse(generationRepository.findById(generationId).orElseThrow(), entry);
    }

    public synchronized TimetableLockResponse release(UUID generationId) {
        Staff hod = hodAccessService.requireHod();
        LockEntry entry = locks.get(generationId);
        if (entry != null && entry.staffId.equals(hod.getStaffId())) {
            locks.remove(generationId);
            publishUnlocked(generationId);
        }
        return TimetableLockResponse.free(generationId);
    }

    public TimetableLockResponse status(UUID generationId) {
        LockEntry entry = locks.get(generationId);
        if (entry == null || !entry.isLive()) {
            if (entry != null) {
                locks.remove(generationId);
                publishUnlocked(generationId);
            }
            return TimetableLockResponse.free(generationId);
        }
        return toResponse(generationRepository.findById(generationId).orElseThrow(), entry);
    }

    /**
     * Enforced on every schedule mutation for a draft generation: the caller must
     * currently hold the live editing lock, so a direct API call cannot bypass it.
     */
    public void requireLockOwned(UUID generationId) {
        Staff hod = lobbyAccessService.requireSharedDraftAccess(generationId);
        LockEntry entry = locks.get(generationId);
        if (entry == null || !entry.isLive()) {
            throw new BusinessRuleException(
                    "You must acquire the editing lock before modifying this timetable");
        }
        if (!entry.staffId.equals(hod.getStaffId())) {
            throw new BusinessRuleException(
                    "Timetable is currently being edited by " + entry.staffName);
        }
    }

    private GenerationSession requireDraft(UUID generationId) {
        GenerationSession generation = generationRepository.findById(generationId)
                .orElseThrow(() -> new ResourceNotFoundException("Generation session not found"));
        if (generation.getStatus() == GenerationStatus.PUBLISHED) {
            throw new BusinessRuleException("A published timetable cannot be edited");
        }
        return generation;
    }

    private TimetableLockResponse toResponse(GenerationSession generation, LockEntry entry) {
        return new TimetableLockResponse(
                generation.getGenerationId(), true, entry.staffId, entry.staffName, entry.expiresAt);
    }

    private void publishLocked(UUID generationId, LockEntry entry) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("generationId", generationId);
        payload.put("scheduleId", null);
        payload.put("staffId", entry.staffId);
        payload.put("lockOwner", entry.staffName);
        payload.put("expiresAt", entry.expiresAt);
        realtimeEventService.publishForGeneration(generationId,
                TimetableRealtimeEventService.SCHEDULE_LOCKED, payload);
    }

    private void publishUnlocked(UUID generationId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("generationId", generationId);
        payload.put("scheduleId", null);
        realtimeEventService.publishForGeneration(generationId,
                TimetableRealtimeEventService.SCHEDULE_UNLOCKED, payload);
    }

    private static final class LockEntry {
        private final UUID staffId;
        private final String staffName;
        private Instant expiresAt;

        private LockEntry(UUID staffId, String staffName, Instant expiresAt) {
            this.staffId = staffId;
            this.staffName = staffName;
            this.expiresAt = expiresAt;
        }

        private boolean isLive() {
            return expiresAt.isAfter(Instant.now());
        }
    }
}
