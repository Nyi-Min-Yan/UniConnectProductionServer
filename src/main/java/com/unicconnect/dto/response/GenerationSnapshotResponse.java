package com.unicconnect.dto.response;

import java.util.UUID;

/**
 * Backend-owned snapshot metadata for one generation, keyed by generationId
 * (never by a browser/lobby/temporary identifier). The authoritative snapshot
 * lives on the backend; the frontend only ever sees this status/metadata so it
 * can recover SNAPSHOT_READY after refresh/reconnect without rebuilding.
 *
 * <p>status maps to the UI snapshot states:
 * PREPARING (PRELOADING), READY (PRELOADED), INVALID, ERROR (FAILED), and
 * GENERATING/COMPLETED for reporting.
 */
public record GenerationSnapshotResponse(
        UUID generationId,
        UUID snapshotId,
        UUID termId,
        String status
) {}
