package com.github.curiousoddman.curious_images.domain.ai;

import com.github.curiousoddman.curious_images.config.AiConfig;
import com.github.curiousoddman.curious_images.util.HumanReadableUtils;
import com.github.curiousoddman.curious_images.util.async.jobs.BackgroundJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;

/**
 * Downloads whatever {@link ModelPaths#missingModels()} reports missing. Submitted by
 * {@code LibraryController} either at app startup (with a no-op {@code onSuccess}) or when the
 * user tries to trigger the AI pipeline without the models present, in which case
 * {@code onSuccess} is wired to automatically submit the AI pipeline job once the download
 * finishes.
 * <p>
 * Reports a single overall progress bar across all missing files, tracked by cumulative bytes
 * (see implementation plan discussion: one bar, not one-per-file). Sizes are looked up via HEAD
 * request up front; if a HEAD request fails to report Content-Length, that file's bytes are
 * excluded from the total and the overall bar will be a slight underestimate for that file —
 * acceptable since it just means the bar can jump the last bit at the very end.
 * <p>
 * Not marked {@link #isSupersedable()} — {@code JobManager} will already discard a duplicate
 * submission while one is queued or running, which is the behavior we want here (no reason to
 * interrupt/restart an in-flight download for a second request).
 */
@Slf4j
@RequiredArgsConstructor
public class ModelDownloadJob extends BackgroundJob {
    public static final String PROCESS_NAME = "Downloading AI models";

    private static final int  BUFFER_SIZE           = 64 * 1024;
    private static final long PROGRESS_UNIT_DIVISOR = 1024L; // report progress in KB to stay well within int range

    private final ModelPaths modelPaths;
    private final AiConfig   aiConfig;
    private final Runnable   onSuccess;
    private final HttpClient httpClient = HttpClient.newBuilder()
                                                    .connectTimeout(Duration.ofSeconds(30))
                                                    .followRedirects(HttpClient.Redirect.NORMAL)
                                                    .build();

    @Override
    public String getProcessName() {
        return PROCESS_NAME;
    }

    @Override
    public void runImpl() throws Exception {
        List<AiConfig.ModelDownload> missing = modelPaths.missingModels();
        if (missing.isEmpty()) {
            log.info("All models already present, nothing to download");
            publishEnded("Models already downloaded");
            return;
        }

        publishStarted("Preparing to download " + missing.size() + " model file(s)...");

        long totalBytes = 0;
        for (AiConfig.ModelDownload model : missing) {
            totalBytes += Math.max(contentLength(model.getUrl()), 0);
        }

        long downloadedBytes = 0;

        for (AiConfig.ModelDownload model : missing) {
            if (isInterruptRequested()) {
                publishInterrupted();
                return;
            }

            Path target = aiConfig.getModelDir()
                                  .resolve(model.getFilename());
            Path partial = target.resolveSibling(target.getFileName() + ".part");
            Files.createDirectories(target.getParent());

            try {
                downloadedBytes = downloadOne(model, partial, target, downloadedBytes, totalBytes);
            } catch (IOException e) {
                log.error("Failed to download model {}", model.getFilename(), e);
                publishFailed(e);
                throw e;
            }

            log.info("Downloaded model {}", model.getFilename());
        }

        publishEnded("Downloaded " + missing.size() + " model file(s)");
        onSuccess.run();
    }

    /**
     * Streams a single model into {@code partial}, then atomically-ish moves it to
     * {@code target} on success (so a crash mid-download never leaves a file that
     * {@link ModelPaths#missingModels()} would mistake for a complete one — it's left as
     * {@code *.part} instead). Returns the updated cumulative-downloaded-bytes count used for the
     * overall progress bar.
     */
    private long downloadOne(AiConfig.ModelDownload model, Path partial, Path target,
                             long downloadedBytesSoFar, long totalBytes) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(model.getUrl()))
                                         .GET()
                                         .build();
        long downloaded = downloadedBytesSoFar;

        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) {
                throw new IOException("Unexpected HTTP status " + response.statusCode() +
                        " downloading " + model.getUrl());
            }

            try (InputStream in = response.body();
                 OutputStream out = Files.newOutputStream(partial, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {

                byte[] buffer = new byte[BUFFER_SIZE];
                int    read;
                while ((read = in.read(buffer)) != -1) {
                    if (isInterruptRequested()) {
                        break;
                    }
                    out.write(buffer, 0, read);
                    downloaded += read;

                    publishProgressThrottled(
                            "Downloading AI models",
                            (int) (downloaded / PROGRESS_UNIT_DIVISOR),
                            (int) Math.max(totalBytes / PROGRESS_UNIT_DIVISOR, 1),
                            model.getFilename() + " (" + HumanReadableUtils.size(downloaded) + " / " +
                                    HumanReadableUtils.size(totalBytes) + ")",
                            false
                    );
                }
            }

            if (isInterruptRequested()) {
                Files.deleteIfExists(partial);
                return downloaded;
            }

            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
            return downloaded;
        } catch (InterruptedException e) {
            Thread.currentThread()
                  .interrupt();
            throw new IOException(e);
        }
    }

    private long contentLength(String url) {
        try {
            HttpRequest head = HttpRequest.newBuilder(URI.create(url))
                                          .method("HEAD", HttpRequest.BodyPublishers.noBody())
                                          .build();
            HttpResponse<Void> response = httpClient.send(head, HttpResponse.BodyHandlers.discarding());
            return response.headers()
                           .firstValueAsLong("Content-Length")
                           .orElse(-1);
        } catch (Exception e) {
            log.warn("Could not determine size of {} ({}), overall progress may be approximate",
                    url, e.getMessage());
            return -1;
        }
    }
}
