package com.unicconnect.controller;

import com.unicconnect.dto.request.CreateGenerationRequest;
import com.unicconnect.dto.request.DragStatusRequest;
import com.unicconnect.dto.request.GenerateTimetableRequest;
import com.unicconnect.dto.request.SwapCellRequest;
import com.unicconnect.dto.request.SwapScheduleRequest;
import com.unicconnect.dto.request.SwapAnimRequest;
import com.unicconnect.dto.response.GenerationManageResponse;
import com.unicconnect.dto.response.GenerationScopeSemester;
import com.unicconnect.dto.response.GenerationSessionResponse;
import com.unicconnect.dto.response.GenerationSnapshotResponse;
import com.unicconnect.dto.response.ScheduleResponse;
import com.unicconnect.dto.response.SwapScheduleResponse;
import com.unicconnect.dto.response.TimetableLockResponse;
import com.unicconnect.service.ClassScheduleService;
import com.unicconnect.service.TimetableEditLockService;
import com.unicconnect.service.TimetableGenerationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/generations")
public class TimetableGenerationController {

    private final TimetableGenerationService service;
    private final TimetableEditLockService lockService;
    private final ClassScheduleService scheduleService;

    public TimetableGenerationController(TimetableGenerationService service,
                                         TimetableEditLockService lockService,
                                         ClassScheduleService scheduleService) {
        this.service = service;
        this.lockService = lockService;
        this.scheduleService = scheduleService;
    }

    @GetMapping
    public ResponseEntity<List<GenerationSessionResponse>> getAll(
            @RequestParam(required = false) UUID termId) {
        return ResponseEntity.ok(service.getAll(termId));
    }

    /**
     * Server-side management authorization + current draft for the generation
     * workspace. {@code canManage} is decided from the authenticated staff member's
     * active HOD assignment, never from a frontend flag.
     */
    @GetMapping("/manage")
    public ResponseEntity<GenerationManageResponse> manage(
            @RequestParam(required = false) UUID termId) {
        return ResponseEntity.ok(service.getManagementContext(termId));
    }

    /**
     * Generation configuration options: applicable semesters (per the selected
     * Mid/Final exam type) and the existing sections that have teaching
     * assignments for the term.
     */
    @GetMapping("/scope")
    public ResponseEntity<List<GenerationScopeSemester>> scope(
            @RequestParam UUID termId,
            @RequestParam(required = false) UUID examTypeId) {
        return ResponseEntity.ok(service.getGenerationScope(termId, examTypeId));
    }

    @GetMapping("/{generationId}")
    public ResponseEntity<GenerationSessionResponse> getById(@PathVariable UUID generationId) {
        return ResponseEntity.ok(service.getById(generationId));
    }

    @PostMapping
    public ResponseEntity<GenerationSessionResponse> create(
            @Valid @RequestBody CreateGenerationRequest request) {
        return ResponseEntity.ok(service.create(request));
    }

    @PostMapping("/{generationId}/generate")
    public ResponseEntity<GenerationSessionResponse> generate(
            @PathVariable UUID generationId,
            @RequestBody(required = false) GenerateTimetableRequest request) {
        return ResponseEntity.ok(service.generate(generationId, request));
    }

    /**
     * Prepares (or reuses) the backend snapshot owned by generationId. Called
     * when an HOD enters the shared workspace so the expensive source preload
     * happens once and is shared by every HOD; Generate then reuses it.
     */
    @PostMapping("/{generationId}/snapshot/prepare")
    public ResponseEntity<GenerationSnapshotResponse> snapshotPrepare(@PathVariable UUID generationId) {
        return ResponseEntity.ok(service.prepareSnapshot(generationId));
    }

    /**
     * Returns the current snapshot status/metadata for generationId. The
     * frontend uses it to recover SNAPSHOT_READY after refresh/reconnect without
     * rebuilding a snapshot unnecessarily.
     */
    @GetMapping("/{generationId}/snapshot")
    public ResponseEntity<GenerationSnapshotResponse> snapshotStatus(@PathVariable UUID generationId) {
        return ResponseEntity.ok(service.getSnapshotStatus(generationId));
    }

    @PostMapping("/{generationId}/publish")
    public ResponseEntity<GenerationSessionResponse> publish(@PathVariable UUID generationId) {
        return ResponseEntity.ok(service.publish(generationId));
    }

    @PostMapping("/{generationId}/cancel")
    public ResponseEntity<GenerationSessionResponse> cancel(@PathVariable UUID generationId) {
        return ResponseEntity.ok(service.cancel(generationId));
    }

    /**
     * Live drag/drop broadcast: relays the lock holder's drag gesture to every
     * connected lobby member so dragging state is visible on all screens.
     */
    @PostMapping("/{generationId}/drag")
    public ResponseEntity<Void> dragStatus(@PathVariable UUID generationId,
            @RequestBody(required = false) DragStatusRequest request) {
        service.publishDragStatus(generationId, request);
        return ResponseEntity.noContent().build();
    }

    /**
     * Live undo/redo swap animation broadcast: relays the two cells being
     * exchanged to every connected HOD browser so they render the same animated
     * swap overlay while the save request is in flight.
     */
    @PostMapping("/{generationId}/swap-anim")
    public ResponseEntity<Void> swapAnim(@PathVariable UUID generationId,
            @RequestBody(required = false) SwapAnimRequest request) {
        service.publishSwapAnimation(generationId, request);
        return ResponseEntity.noContent().build();
    }

    /**
     * Broadcasts a lobby-scoped {@code TIMETABLE_EDIT_STARTED} event when the
     * lobby creator opens the dedicated edit workspace, so every joined HOD
     * browser automatically navigates to the same edit page.
     */
    @PostMapping("/{generationId}/edit")
    public ResponseEntity<Void> editStarted(@PathVariable UUID generationId) {
        service.notifyEditStarted(generationId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Drag-and-drop period reordering: swap the dragged schedule with the one
     * occupying the drop cell (or move it when the cell is empty). A swap that
     * would create conflicts is returned un-applied unless the HOD confirms
     * ({@code force=true}) — see {@link SwapScheduleResponse}.
     */
    @PostMapping("/{generationId}/swap")
    public ResponseEntity<SwapScheduleResponse> swapSchedules(@PathVariable UUID generationId,
            @Valid @RequestBody SwapScheduleRequest request) {
        return ResponseEntity.ok(scheduleService.swap(generationId, request));
    }

    /**
     * Single-cell drag swap: the dragged period and the drop cell exchange their
     * course content (each multi-period schedule is decomposed around its swapped
     * cell so the courses stay contiguous where possible). Applicable when the
     * drop cell is occupied by a course of the same semester; anything else falls
     * back to the plain block swap.
     */
    @PostMapping("/{generationId}/swap-cell")
    public ResponseEntity<SwapScheduleResponse> swapCells(@PathVariable UUID generationId,
            @Valid @RequestBody SwapCellRequest request) {
        return ResponseEntity.ok(scheduleService.swapCell(generationId, request));
    }

    @GetMapping("/{generationId}/schedules")
    public ResponseEntity<List<ScheduleResponse>> getSchedules(@PathVariable UUID generationId) {
        return ResponseEntity.ok(service.getSchedules(generationId));
    }

    @DeleteMapping("/{generationId}")
    public ResponseEntity<Void> delete(@PathVariable UUID generationId) {
        service.delete(generationId);
        return ResponseEntity.noContent().build();
    }

    // ---------- Single-operator drag/drop editing lock ----------

    @GetMapping("/{generationId}/lock")
    public ResponseEntity<TimetableLockResponse> lockStatus(@PathVariable UUID generationId) {
        return ResponseEntity.ok(lockService.status(generationId));
    }

    @PostMapping("/{generationId}/lock")
    public ResponseEntity<TimetableLockResponse> acquireLock(@PathVariable UUID generationId) {
        return ResponseEntity.ok(lockService.acquire(generationId));
    }

    @PostMapping("/{generationId}/lock/heartbeat")
    public ResponseEntity<TimetableLockResponse> heartbeatLock(@PathVariable UUID generationId) {
        return ResponseEntity.ok(lockService.heartbeat(generationId));
    }

    @PostMapping("/{generationId}/lock/release")
    public ResponseEntity<TimetableLockResponse> releaseLock(@PathVariable UUID generationId) {
        return ResponseEntity.ok(lockService.release(generationId));
    }
}
