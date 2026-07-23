package com.github.curiousoddman.curious_images.domain.ai;

import com.github.curiousoddman.curious_images.config.AiConfig;
import com.github.curiousoddman.curious_images.dbobj.tables.records.ClipEmbeddingRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.MediaPhotoRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.PersonRecord;
import com.github.curiousoddman.curious_images.domain.common.thumbnail.PersonService;
import com.github.curiousoddman.curious_images.event.model.AiPipelineCompleteEvent;
import com.github.curiousoddman.curious_images.persistence.AlbumPhotoRepository;
import com.github.curiousoddman.curious_images.persistence.AlbumRepository;
import com.github.curiousoddman.curious_images.persistence.ClipEmbeddingRepository;
import com.github.curiousoddman.curious_images.persistence.MediaRepository;
import com.github.curiousoddman.curious_images.util.EmbeddingMath;
import com.github.curiousoddman.curious_images.util.TimeProvider;
import com.github.curiousoddman.curious_images.util.async.jobs.BackgroundJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.Query;
import org.jooq.impl.DSL;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.github.curiousoddman.curious_images.util.EmbeddingMath.dot;
import static com.github.curiousoddman.curious_images.util.EmbeddingMath.getFloats;
import static com.github.curiousoddman.curious_images.util.EmbeddingMath.l2Normalize;

@Slf4j
@RequiredArgsConstructor
public class AlbumGenerationJob extends BackgroundJob {
    private final DSLContext              dsl;
    private final AlbumRepository         albumRepo;
    private final AlbumPhotoRepository    albumPhotoRepo;
    private final ClipEmbeddingRepository clipEmbeddingRepo;
    private final AiConfig                aiConfig;
    private final TimeProvider            timeProvider;
    private final PersonService           personService;
    private final MediaRepository         mediaRepository;


    @Override
    public void runImpl() {
        publishStarted(getProcessName());
        log.info("Regenerating albums...");
        try {
            buildPersonAlbums();
            buildEventAlbums();
            buildLocationAlbums();
            //buildSimilarityAlbums();      // TODO: do I need that?
            publishEnded(getProcessName());
            eventPublisher.publishEvent(new AiPipelineCompleteEvent(this));
            log.info("Album generation complete");
        } catch (Exception e) {
            publishFailed(e);
            log.error("Album generation failed", e);
        }
    }

    // ── Person albums ─────────────────────────────────────────────────────────

    private void buildPersonAlbums() {
        dsl.transaction(cfg -> {
            DSLContext ctx = DSL.using(cfg);
            albumRepo.deleteByType(ctx, "PERSON");

            LocalDateTime      now              = timeProvider.now();
            List<PersonRecord> personRecordList = personService.findAllPersons();
            publishProgressThrottled("Build Person Albums", 0, personRecordList.size(), "", false);
            for (int j = 0; j < personRecordList.size(); j++) {
                PersonRecord person   = personRecordList.get(j);
                List<Long>   photoIds = personService.getPersonPhotoIds(person);

                publishProgressThrottled("Build Person Albums", j, personRecordList.size(), "", j + 1 == personRecordList.size());
                if (photoIds.isEmpty()) {
                    continue;
                }

                String name = person.getName() != null
                        ? person.getName()
                        : "Person #" + person.getId();
                long albumId = albumRepo.insert(name, "PERSON", photoIds.getFirst(), null, now);

                List<Query> buf = new ArrayList<>(photoIds.size());
                for (int i = 0; i < photoIds.size(); i++) {
                    buf.add(albumPhotoRepo.insertQuery(albumId, photoIds.get(i), i, now));
                }
                ctx.batch(buf)
                   .execute();
            }
        });
    }

    // ── Event albums ──────────────────────────────────────────────────────────

    private void buildEventAlbums() {
        List<MediaPhotoRecord> dated = mediaRepository.findOrderedByCaptureDate();
        if (dated.isEmpty()) {
            return;
        }

        long                         gapMillis = TimeUnit.HOURS.toMillis(aiConfig.getEventGapHours());
        List<List<MediaPhotoRecord>> events    = new ArrayList<>();
        List<MediaPhotoRecord>       current   = new ArrayList<>();
        current.add(dated.getFirst());

        publishProgressThrottled("Build Event Albums", 0, dated.size(), "", false);

        for (int i = 1; i < dated.size(); i++) {
            MediaPhotoRecord prev = dated.get(i - 1);
            MediaPhotoRecord next = dated.get(i);
            long gap = Duration.between(prev.getCaptureDate(), next.getCaptureDate())
                               .toMillis();
            if (gap > gapMillis) {
                events.add(current);
                current = new ArrayList<>();
            }
            current.add(next);
            publishProgressThrottled("Build Event Albums", i, dated.size() + events.size(), "", false);
        }
        events.add(current);

        dsl.transaction(cfg -> {
            DSLContext ctx = DSL.using(cfg);
            albumRepo.deleteByType(ctx, "EVENT");
            LocalDateTime now = timeProvider.now();

            for (int j = 0; j < events.size(); j++) {
                publishProgressThrottled("Build Event Albums", j + dated.size(), dated.size() + events.size(), "", false);

                List<MediaPhotoRecord> eventPhotos = events.get(j);
                if (eventPhotos.size() < aiConfig.getMinEventSize()) {
                    continue;
                }

                // Name = date of first media; cover = sharpest media in event
                String name = eventPhotos.getFirst()
                                         .getCaptureDate()
                                         .toLocalDate()
                                         .toString();
                long coverId = eventPhotos.getFirst()
                                          .getId();

                long albumId = albumRepo.insert(name, "EVENT", coverId, null, now);
                prepareAndExecuteBatch(ctx, now, eventPhotos, albumId);
            }
        });
    }

    // ── Location albums ───────────────────────────────────────────────────────

    /**
     * Groups photos by GPS cell (lat/lon rounded to 2 decimal places ≈ 1 km resolution)
     * and creates one album per cell with ≥ {@code minLocationSize} photos.
     */
    private void buildLocationAlbums() {
        publishProgressThrottled("Build Location Albums", 0, 1, "", false);
        List<MediaPhotoRecord> withGps = mediaRepository.findAllWithGps();
        if (withGps.isEmpty()) {
            return;
        }

        Map<String, List<MediaPhotoRecord>> cells = new LinkedHashMap<>();
        for (int i = 0; i < withGps.size(); i++) {
            publishProgressThrottled("Build Event Albums", i, withGps.size() + cells.size(), "", false);

            MediaPhotoRecord p   = withGps.get(i);
            double           lat = Math.round(p.getGpsLat() * 100.0) / 100.0;
            double           lon = Math.round(p.getGpsLon() * 100.0) / 100.0;
            String           key = lat + "," + lon;
            cells.computeIfAbsent(key, k -> new ArrayList<>())
                 .add(p);
        }

        dsl.transaction(cfg -> {
            DSLContext ctx = DSL.using(cfg);
            albumRepo.deleteByType(ctx, "LOCATION");
            LocalDateTime now = timeProvider.now();

            int i = 0;
            for (Map.Entry<String, List<MediaPhotoRecord>> entry : cells.entrySet()) {
                i++;
                publishProgressThrottled("Build Event Albums", withGps.size() + i, withGps.size() + cells.size(), "", false);
                List<MediaPhotoRecord> group = entry.getValue();
                if (group.size() < aiConfig.getMinLocationSize()) {
                    continue;
                }

                // Name = "lat, lon" with 2dp — legible enough as a placeholder
                String name = entry.getKey();
                long coverId = group.getFirst()
                                    .getId();
                long albumId = albumRepo.insert(name, "LOCATION", coverId, null, now);

                prepareAndExecuteBatch(ctx, now, group, albumId);
            }
        });
    }

    // ── Similarity albums (CLIP k-means) ─────────────────────────────────────

    private void buildSimilarityAlbums() {
        publishProgressThrottled("Build Similarity Albums", 0, 1, "", false);

        List<ClipEmbeddingRecord> all = clipEmbeddingRepo.findAll();
        if (all.isEmpty()) {
            return;
        }

        int total = all.size();
        int k     = Math.max(2, (int) Math.sqrt(total / 2.0));
        log.info("CLIP k-means: {} photos, k={}", total, k);

        long[]    mediaIds = new long[total];
        float[][] vectors  = new float[total][];
        for (int i = 0; i < total; i++) {
            mediaIds[i] = all.get(i)
                             .getMediaId();
            vectors[i] = getFloats(all.get(i)
                                      .getEmbedding());
        }

        int[] assignments = EmbeddingMath.kMeans(vectors, k, 20);

        // Group by cluster
        Map<Integer, List<Integer>> clusters = new LinkedHashMap<>();
        for (int i = 0; i < total; i++) {
            clusters.computeIfAbsent(assignments[i], x -> new ArrayList<>())
                    .add(i);
        }

        dsl.transaction(cfg -> {
            DSLContext ctx = DSL.using(cfg);
            albumRepo.deleteByType(ctx, "SIMILARITY");
            LocalDateTime now = timeProvider.now();

            for (Map.Entry<Integer, List<Integer>> entry : clusters.entrySet()) {
                List<Integer> members = entry.getValue();
                if (members.size() < aiConfig.getMinClusterSize()) {
                    continue;
                }

                // Compute centroid
                float[] centroid = centroid(vectors, members);

                // Check intra-cluster average cosine similarity
                double avgSim = 0;
                for (int idx : members) {
                    avgSim += dot(centroid, vectors[idx]);
                }
                avgSim /= members.size();
                if (avgSim < aiConfig.getMinClusterSimilarity()) {
                    continue;
                }

                // Name via zero-shot CLIP label matching (centroid vs. pre-encoded vocab labels)
                // We skip the text-encoder call here and use a simple heuristic name instead
                // to avoid a circular dependency. Full zero-shot naming requires ClipTextEncoder.
                String name = "Cluster " + (entry.getKey() + 1);

                long coverId = mediaIds[members.getFirst()];
                long albumId = albumRepo.insert(name, "SIMILARITY", coverId, null, now);

                List<Query> buf = new ArrayList<>(members.size());
                for (int i = 0; i < members.size(); i++) {
                    buf.add(albumPhotoRepo.insertQuery(albumId, mediaIds[members.get(i)], i, now));
                }
                ctx.batch(buf)
                   .execute();
            }
        });
    }

    private float[] centroid(float[][] data, List<Integer> indices) {
        int     dims = data[0].length;
        float[] sum  = new float[dims];
        for (int idx : indices) {
            for (int d = 0; d < dims; d++) {
                sum[d] += data[idx][d];
            }
        }
        return l2Normalize(sum);
    }

    @Override
    public String getProcessName() {
        return "Album generation";
    }

    private void prepareAndExecuteBatch(DSLContext ctx, LocalDateTime now, List<MediaPhotoRecord> event, long albumId) {
        List<Query> buf = new ArrayList<>(event.size());
        for (int i = 0; i < event.size(); i++) {
            buf.add(albumPhotoRepo.insertQuery(albumId, event.get(i)
                                                             .getId(), i, now));
        }
        ctx.batch(buf)
           .execute();
    }
}
