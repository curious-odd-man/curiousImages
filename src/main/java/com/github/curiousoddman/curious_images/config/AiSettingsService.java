package com.github.curiousoddman.curious_images.config;

import com.github.curiousoddman.curious_images.domain.DataAccess;
import com.github.curiousoddman.curious_images.domain.ai.OnnxModelRegistry;
import com.github.curiousoddman.curious_images.domain.user.prefs.UserPrefKey;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Single place the Settings screen talks to for every AI-related setting that can be changed
 * without restarting the app.
 * <p>
 * On startup, {@link #applyPersistedOverrides()} loads any previously-saved values from
 * {@link DataAccess} (the {@code USER_PREFERENCES} table) on top of the {@code application.yaml}
 * defaults already bound onto {@link AiConfig}. From then on, every setter here:
 * <ol>
 *     <li>updates the live {@link AiConfig} bean (read by {@code JobFactory}, {@code OnnxModelRegistry}
 *     and the album-generation jobs on their next run/call),</li>
 *     <li>persists the new value so it survives a restart, and</li>
 *     <li>for the two settings that affect an already-open ONNX session (execution provider,
 *     intra-op threads), evicts all cached sessions so the next inference call recreates them
 *     with the new options.</li>
 * </ol>
 * Settings that are read once while building beans that can't be safely re-created at runtime
 * (model directory, index root, thumbnail cache directory) are NOT handled here — see
 * {@link RuntimeSettingsBootstrap} for those.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiSettingsService {

    private final AiConfig           aiConfig;
    private final DataAccess         dataAccess;
    private final OnnxModelRegistry  onnxModelRegistry;

    // Fallback defaults if nothing has ever been persisted for these two - they used to be bound
    // via @Value directly onto JobFactory; now AiConfig is the single source of truth, seeded
    // from these yaml keys the first time the app runs.
    @Value("${app.duplicate-detection.thread-count:4}")
    private int yamlDuplicateDetectionThreadCount;
    @Value("${ai.features.face-only:false}")
    private boolean yamlFaceOnly;

    @PostConstruct
    public void applyPersistedOverrides() {
        aiConfig.setExecutionProvider(
                AiConfig.ExecutionProvider.valueOf(
                        dataAccess.getUserPref(UserPrefKey.AI_EXECUTION_PROVIDER, aiConfig.getExecutionProvider().name())));
        aiConfig.setIntraOpThreads(getInt(UserPrefKey.AI_INTRA_OP_THREADS, aiConfig.getIntraOpThreads()));
        aiConfig.setBatchSize(getInt(UserPrefKey.AI_BATCH_SIZE, aiConfig.getBatchSize()));
        aiConfig.setDuplicateDetectionThreadCount(getInt(UserPrefKey.AI_DUPLICATE_DETECTION_THREAD_COUNT, yamlDuplicateDetectionThreadCount));
        aiConfig.setFaceOnly(getBoolean(UserPrefKey.AI_FACE_ONLY, yamlFaceOnly));
        aiConfig.setEventGapHours(getInt(UserPrefKey.AI_EVENT_GAP_HOURS, aiConfig.getEventGapHours()));
        aiConfig.setMinEventSize(getInt(UserPrefKey.AI_MIN_EVENT_SIZE, aiConfig.getMinEventSize()));
        aiConfig.setMinLocationSize(getInt(UserPrefKey.AI_MIN_LOCATION_SIZE, aiConfig.getMinLocationSize()));
        aiConfig.setMinClusterSize(getInt(UserPrefKey.AI_MIN_CLUSTER_SIZE, aiConfig.getMinClusterSize()));
        aiConfig.setMinClusterSimilarity(getFloat(UserPrefKey.AI_MIN_CLUSTER_SIMILARITY, aiConfig.getMinClusterSimilarity()));
        log.info("Applied persisted AI settings: provider={}, intraOpThreads={}, batchSize={}, dedupeThreads={}, faceOnly={}",
                aiConfig.getExecutionProvider(), aiConfig.getIntraOpThreads(), aiConfig.getBatchSize(),
                aiConfig.getDuplicateDetectionThreadCount(), aiConfig.isFaceOnly());
    }

    // ── Performance (require an ONNX session reload to take effect) ────────────

    public void setExecutionProvider(AiConfig.ExecutionProvider provider) {
        aiConfig.setExecutionProvider(provider);
        dataAccess.setUserPref(UserPrefKey.AI_EXECUTION_PROVIDER, provider.name());
        onnxModelRegistry.evictAll();
        log.info("Execution provider changed to {} - AI sessions will reload on next use", provider);
    }

    public void setIntraOpThreads(int threads) {
        aiConfig.setIntraOpThreads(threads);
        dataAccess.setUserPref(UserPrefKey.AI_INTRA_OP_THREADS, String.valueOf(threads));
        onnxModelRegistry.evictAll();
    }

    // ── Performance (take effect on next call/job, no reload needed) ───────────

    public void setBatchSize(int batchSize) {
        aiConfig.setBatchSize(batchSize);
        dataAccess.setUserPref(UserPrefKey.AI_BATCH_SIZE, String.valueOf(batchSize));
    }

    public void setDuplicateDetectionThreadCount(int threadCount) {
        aiConfig.setDuplicateDetectionThreadCount(threadCount);
        dataAccess.setUserPref(UserPrefKey.AI_DUPLICATE_DETECTION_THREAD_COUNT, String.valueOf(threadCount));
    }

    public void setFaceOnly(boolean faceOnly) {
        aiConfig.setFaceOnly(faceOnly);
        dataAccess.setUserPref(UserPrefKey.AI_FACE_ONLY, String.valueOf(faceOnly));
    }

    // ── Album-generation tuning (take effect next time albums are (re)generated) ─

    public void setEventGapHours(int hours) {
        aiConfig.setEventGapHours(hours);
        dataAccess.setUserPref(UserPrefKey.AI_EVENT_GAP_HOURS, String.valueOf(hours));
    }

    public void setMinEventSize(int size) {
        aiConfig.setMinEventSize(size);
        dataAccess.setUserPref(UserPrefKey.AI_MIN_EVENT_SIZE, String.valueOf(size));
    }

    public void setMinLocationSize(int size) {
        aiConfig.setMinLocationSize(size);
        dataAccess.setUserPref(UserPrefKey.AI_MIN_LOCATION_SIZE, String.valueOf(size));
    }

    public void setMinClusterSize(int size) {
        aiConfig.setMinClusterSize(size);
        dataAccess.setUserPref(UserPrefKey.AI_MIN_CLUSTER_SIZE, String.valueOf(size));
    }

    public void setMinClusterSimilarity(float similarity) {
        aiConfig.setMinClusterSimilarity(similarity);
        dataAccess.setUserPref(UserPrefKey.AI_MIN_CLUSTER_SIMILARITY, String.valueOf(similarity));
    }

    public AiConfig config() {
        return aiConfig;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private int getInt(UserPrefKey key, int defaultValue) {
        try {
            return Integer.parseInt(dataAccess.getUserPref(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            log.warn("Corrupt pref [{}], using default {}", key.getKey(), defaultValue);
            return defaultValue;
        }
    }

    private boolean getBoolean(UserPrefKey key, boolean defaultValue) {
        return Boolean.parseBoolean(dataAccess.getUserPref(key, String.valueOf(defaultValue)));
    }

    private float getFloat(UserPrefKey key, float defaultValue) {
        try {
            return Float.parseFloat(dataAccess.getUserPref(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            log.warn("Corrupt pref [{}], using default {}", key.getKey(), defaultValue);
            return defaultValue;
        }
    }
}
