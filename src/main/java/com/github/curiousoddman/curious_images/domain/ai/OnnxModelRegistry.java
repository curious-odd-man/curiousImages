package com.github.curiousoddman.curious_images.domain.ai;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.github.curiousoddman.curious_images.config.AiConfig;
import com.github.curiousoddman.curious_images.util.async.jobs.IrrecoverableIterationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Singleton registry for ONNX Runtime sessions. Sessions are expensive to create; load each
 * model once and reuse across all inference calls. Models are loaded lazily on first use so the
 * application starts even when model files have not been downloaded yet.
 * <p>
 * The {@link #evict(String)} method lets callers drop rarely-used sessions (e.g. the CLIP text
 * encoder between searches) to reclaim RAM; they will be reloaded transparently on next access.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OnnxModelRegistry implements DisposableBean {

    private final OrtEnvironment                        env      = OrtEnvironment.getEnvironment();
    private final ConcurrentHashMap<String, OrtSession> sessions = new ConcurrentHashMap<>();
    private final AiConfig                              config;

    /**
     * Returns the cached session for {@code modelKey}, loading it from {@code modelPath} if this
     * is the first call for that key. Thread-safe via {@link ConcurrentHashMap#computeIfAbsent}.
     */
    public OrtSession getOrLoad(String modelKey, Path modelPath, List<String> expectedOutputNames) throws IrrecoverableIterationException {
        OrtSession ortSession = sessions.get(modelKey);
        if (ortSession != null) {
            return ortSession;
        }

        try {
            log.info("Loading ONNX model '{}' from {}: {}", modelKey, modelPath, OrtEnvironment.getAvailableProviders());
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            opts.setIntraOpNumThreads(config.getIntraOpThreads());
            switch (config.getExecutionProvider()) {
                case CUDA -> opts.addCUDA(0);
                case DIRECTML -> opts.addDirectML(0);
                case CPU -> { /* ONNX Runtime default — no extra provider needed */ }
            }
            OrtSession session = env.createSession(modelPath.toString(), opts);
            log.info("ONNX model '{}' loaded successfully", modelKey);
            sessions.put(modelKey, session);

            List<String> sessionNames = session.getOutputNames()
                                               .stream()
                                               .toList();
            // Verify that output order matches indexes that are used to fetch outputs
            if (!sessionNames.equals(expectedOutputNames)) {
                throw new IrrecoverableIterationException(new IllegalArgumentException(sessionNames + " vs " + expectedOutputNames));
            }

            return session;
        } catch (OrtException e) {
            throw new IrrecoverableIterationException("Failed to load ONNX model '" + modelKey + "' from " + modelPath, e);
        }
    }

    /**
     * Evicts the session for {@code modelKey} from the cache and closes it, freeing native
     * memory. A subsequent call to {@link #getOrLoad} will reload it from disk.
     * No-op if the key is not currently loaded.
     */
    public void evict(String modelKey) {
        OrtSession removed = sessions.remove(modelKey);
        if (removed != null) {
            try {
                removed.close();
                log.info("Evicted ONNX model '{}'", modelKey);
            } catch (OrtException e) {
                log.warn("Error closing evicted ONNX session '{}'", modelKey, e);
            }
        }
    }

    /**
     * Closes all sessions and the shared {@link OrtEnvironment} on Spring shutdown.
     */
    @Override
    public void destroy() {
        sessions.forEach((key, session) -> {
            try {
                session.close();
            } catch (OrtException e) {
                log.warn("Error closing ONNX session '{}'", key, e);
            }
        });
        sessions.clear();
        env.close();
    }
}
