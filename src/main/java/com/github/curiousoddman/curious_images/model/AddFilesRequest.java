package com.github.curiousoddman.curious_images.model;

import com.github.curiousoddman.curious_images.domain.dedupe.DuplicateDetectionJob;

import java.util.List;

/**
 * Immutable value object carrying all parameters for one add-files job.
 *
 * @param sourcePaths           Absolute paths the user selected (files or folders).
 * @param copyToDestination     {@code true} → copy then scan;
 *                              {@code false} → register in-place as new import roots.
 * @param destinationFolder     Target folder for copies; {@code null} when
 *                              {@code copyToDestination} is {@code false}.
 * @param runDuplicateDetection Start {@link DuplicateDetectionJob} after import.
 */
public record AddFilesRequest(
        List<String> sourcePaths,
        boolean copyToDestination,
        String destinationFolder,
        boolean runAiPipeline,
        boolean runDuplicateDetection
) {}
