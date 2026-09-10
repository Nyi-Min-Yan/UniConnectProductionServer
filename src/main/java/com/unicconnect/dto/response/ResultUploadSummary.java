package com.unicconnect.dto.response;

import java.util.List;
import java.util.UUID;

/**
 * Result of a multi-file exam result upload. Unmatched / failed files are reported
 * here instead of failing the whole request.
 */
public record ResultUploadSummary(
        UUID batchId,
        ResultBatchResponse batch,
        int totalFiles,
        int matchedFiles,
        int unmatchedFiles,
        int insertedDocuments,
        int updatedDocuments,
        int failedFiles,
        int skippedFiles,
        List<String> unmatchedFileNames,
        List<String> failedFileNames,
        List<String> skippedFileNames
) {}
