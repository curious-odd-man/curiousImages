package com.github.curiousoddman.curious_images.domain.ai;

import com.github.curiousoddman.curious_images.config.AiConfig;
import com.github.curiousoddman.curious_images.dbobj.tables.records.ClipEmbeddingRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.ClusterRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.FaceRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.PersonRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.PhotoRecord;
import com.github.curiousoddman.curious_images.event.model.AiPipelineCompleteEvent;
import com.github.curiousoddman.curious_images.persistence.AlbumPhotoRepository;
import com.github.curiousoddman.curious_images.persistence.AlbumRepository;
import com.github.curiousoddman.curious_images.persistence.ClipEmbeddingRepository;
import com.github.curiousoddman.curious_images.persistence.ClusterRepository;
import com.github.curiousoddman.curious_images.persistence.FaceRepository;
import com.github.curiousoddman.curious_images.persistence.PersonRepository;
import com.github.curiousoddman.curious_images.util.TimeProvider;
import com.github.curiousoddman.curious_images.util.async.jobs.BackgroundJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.Query;
import org.jooq.impl.DSL;

import java.awt.image.BufferedImage;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.github.curiousoddman.curious_images.dbobj.Tables.PHOTO;
import static com.github.curiousoddman.curious_images.persistence.ClipEmbeddingRepository.getFloats;
import static com.github.curiousoddman.curious_images.util.EmbeddingMath.dot;
import static com.github.curiousoddman.curious_images.util.EmbeddingMath.l2Normalize;

@Slf4j
@RequiredArgsConstructor
public class AlbumGenerationJob extends BackgroundJob {
    private final DSLContext              dsl;
    private final AlbumRepository         albumRepo;
    private final AlbumPhotoRepository    albumPhotoRepo;
    private final FaceRepository          faceRepo;
    private final PersonRepository        personRepo;
    private final ClipEmbeddingRepository clipEmbeddingRepo;
    private final AiConfig                aiConfig;
    private final TimeProvider            timeProvider;
    private final ClusterRepository       clusterRepository;


    @Override
    public void runImpl() throws Exception {
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
            List<PersonRecord> personRecordList = personRepo.findAll();
            publishProgressThrottled("Build Person Albums", 0, personRecordList.size(), "", false);
            for (int j = 0; j < personRecordList.size(); j++) {
                PersonRecord person = personRecordList.get(j);
                //TODO: this probably could be moved under Facade repository - this is used in multiple places.
                List<Long> clusterIds = clusterRepository.findByPersonId(person.getId())
                                                         .stream()
                                                         .map(ClusterRecord::getId)
                                                         .toList();
                List<Long> photoIds = faceRepo.findByClusterIdIn(clusterIds)
                                              .stream()
                                              .map(FaceRecord::getPhotoId)
                                              .distinct()
                                              .toList();

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
        // Load all photos with a non-null capture_date ordered chronologically
        List<PhotoRecord> dated = dsl.selectFrom(PHOTO)
                                     .where(PHOTO.CAPTURE_DATE.isNotNull())
                                     .orderBy(PHOTO.CAPTURE_DATE)
                                     .fetch();
        if (dated.isEmpty()) {
            return;
        }

        long                    gapMillis = (long) aiConfig.getEventGapHours() * 3_600_000L;
        List<List<PhotoRecord>> events    = new ArrayList<>();
        List<PhotoRecord>       current   = new ArrayList<>();
        current.add(dated.getFirst());

        publishProgressThrottled("Build Event Albums", 0, dated.size(), "", false);

        for (int i = 1; i < dated.size(); i++) {
            PhotoRecord prev = dated.get(i - 1);
            PhotoRecord next = dated.get(i);
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

                List<PhotoRecord> event = events.get(j);
                if (event.size() < aiConfig.getMinEventSize()) {
                    continue;
                }

                // Name = date of first photo; cover = sharpest photo in event
                String name = event.getFirst()
                                   .getCaptureDate()
                                   .toLocalDate()
                                   .toString();
                long coverId = sharpestPhoto(event);

                long        albumId = albumRepo.insert(name, "EVENT", coverId, null, now);
                List<Query> buf     = new ArrayList<>(event.size());
                for (int i = 0; i < event.size(); i++) {
                    buf.add(albumPhotoRepo.insertQuery(albumId, event.get(i)
                                                                     .getId(), i, now));
                }
                ctx.batch(buf)
                   .execute();
            }
        });
    }

    /**
     * Returns the photo ID with the highest estimated sharpness (variance of Laplacian
     * approximated by a simple pixel-neighbourhood difference on the thumbnail).
     * Falls back to the first photo if sharpness cannot be computed.
     */
    private long sharpestPhoto(List<PhotoRecord> photos) {
        long bestId = photos.getFirst()
                            .getId();
        // TODO: This is commented out because it is very slow, yet hardly very beneficial
//        double bestScore = -1;
//        for (PhotoRecord photo : photos) {
//            try {
//                BufferedImage img = ImageIO.read(new File(photo.getAbsolutePath()));
//                if (img == null) {
//                    continue;
//                }
//                // Downsample to 64×64 for speed
//                BufferedImage small = Thumbnails.of(img)
//                                                .forceSize(64, 64)
//                                                .asBufferedImage();
//                double score = laplacianVariance(small);
//                if (score > bestScore) {
//                    bestScore = score;
//                    bestId = photo.getId();
//                }
//            } catch (Exception ignored) {
//            }
//        }
        return bestId;
    }

    /**
     * Approximates the Laplacian variance as a sharpness score.
     */
    private double laplacianVariance(BufferedImage img) {
        int    w     = img.getWidth(), h = img.getHeight();
        double sum   = 0, sumSq = 0;
        int    count = 0;
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int    c   = gray(img.getRGB(x, y));
                int    n   = gray(img.getRGB(x, y - 1));
                int    s   = gray(img.getRGB(x, y + 1));
                int    e   = gray(img.getRGB(x + 1, y));
                int    ww  = gray(img.getRGB(x - 1, y));
                double lap = 4 * c - n - s - e - ww;
                sum += lap;
                sumSq += lap * lap;
                count++;
            }
        }
        if (count == 0) {
            return 0;
        }
        double mean = sum / count;
        return sumSq / count - mean * mean;
    }

    private int gray(int rgb) {
        return ((rgb >> 16 & 0xFF) * 299 + (rgb >> 8 & 0xFF) * 587 + (rgb & 0xFF) * 114) / 1000;
    }

    // ── Location albums ───────────────────────────────────────────────────────

    /**
     * Groups photos by GPS cell (lat/lon rounded to 2 decimal places ≈ 1 km resolution)
     * and creates one album per cell with ≥ {@code minLocationSize} photos.
     */
    private void buildLocationAlbums() {
        publishProgressThrottled("Build Location Albums", 0, 1, "", false);

        List<PhotoRecord> withGps = dsl.selectFrom(PHOTO)
                                       .where(PHOTO.GPS_LAT.isNotNull()
                                                           .and(PHOTO.GPS_LON.isNotNull()))
                                       .fetch();
        if (withGps.isEmpty()) {
            return;
        }

        Map<String, List<PhotoRecord>> cells = new LinkedHashMap<>();
        for (int i = 0; i < withGps.size(); i++) {
            publishProgressThrottled("Build Event Albums", i, withGps.size() + cells.size(), "", false);

            PhotoRecord p   = withGps.get(i);
            double      lat = Math.round(p.getGpsLat() * 100.0) / 100.0;
            double      lon = Math.round(p.getGpsLon() * 100.0) / 100.0;
            String      key = lat + "," + lon;
            cells.computeIfAbsent(key, k -> new ArrayList<>())
                 .add(p);
        }

        dsl.transaction(cfg -> {
            DSLContext ctx = DSL.using(cfg);
            albumRepo.deleteByType(ctx, "LOCATION");
            LocalDateTime now = timeProvider.now();

            int i = 0;
            for (Map.Entry<String, List<PhotoRecord>> entry : cells.entrySet()) {
                i++;
                publishProgressThrottled("Build Event Albums", withGps.size() + i, withGps.size() + cells.size(), "", false);
                List<PhotoRecord> group = entry.getValue();
                if (group.size() < aiConfig.getMinLocationSize()) {
                    continue;
                }

                // Name = "lat, lon" with 2dp — legible enough as a placeholder
                String name = entry.getKey();
                long coverId = group.getFirst()
                                    .getId();
                long albumId = albumRepo.insert(name, "LOCATION", coverId, null, now);

                List<Query> buf = new ArrayList<>(group.size());
                for (int j = 0; j < group.size(); j++) {
                    buf.add(albumPhotoRepo.insertQuery(albumId, group.get(j)
                                                                     .getId(), j, now));
                }
                ctx.batch(buf)
                   .execute();
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

        long[]    photoIds = new long[total];
        float[][] vectors  = new float[total][];
        for (int i = 0; i < total; i++) {
            photoIds[i] = all.get(i)
                             .getPhotoId();
            vectors[i] = getFloats(all.get(i)
                                      .getEmbedding());
        }

        int[] assignments = kMeans(vectors, k, 20);

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
                for (int idx : members) avgSim += dot(centroid, vectors[idx]);
                avgSim /= members.size();
                if (avgSim < aiConfig.getMinClusterSimilarity()) {
                    continue;
                }

                // Name via zero-shot CLIP label matching (centroid vs. pre-encoded vocab labels)
                // We skip the text-encoder call here and use a simple heuristic name instead
                // to avoid a circular dependency. Full zero-shot naming requires ClipTextEncoder.
                String name = "Cluster " + (entry.getKey() + 1);

                long coverId = photoIds[members.getFirst()];
                long albumId = albumRepo.insert(name, "SIMILARITY", coverId, null, now);

                List<Query> buf = new ArrayList<>(members.size());
                for (int i = 0; i < members.size(); i++) {
                    buf.add(albumPhotoRepo.insertQuery(albumId, photoIds[members.get(i)], i, now));
                }
                ctx.batch(buf)
                   .execute();
            }
        });
    }

    /**
     * Pure-Java k-means. Returns assignment array (cluster index per point).
     */
    private int[] kMeans(float[][] data, int k, int maxIter) {
        int n = data.length, dims = data[0].length;
        // Initialise centroids from first k points (simple, deterministic)
        float[][] centroids = new float[k][dims];
        for (int c = 0; c < k && c < n; c++) centroids[c] = Arrays.copyOf(data[c], dims);

        int[] assignments = new int[n];
        for (int iter = 0; iter < maxIter; iter++) {
            // Assignment step
            boolean changed = false;
            for (int i = 0; i < n; i++) {
                int   best    = 0;
                float bestSim = Float.NEGATIVE_INFINITY;
                for (int c = 0; c < k; c++) {
                    float sim = dot(data[i], centroids[c]);
                    if (sim > bestSim) {
                        bestSim = sim;
                        best = c;
                    }
                }
                if (assignments[i] != best) {
                    assignments[i] = best;
                    changed = true;
                }
            }
            if (!changed) {
                break;
            }

            // Update step
            float[][] sums   = new float[k][dims];
            int[]     counts = new int[k];
            for (int i = 0; i < n; i++) {
                int c = assignments[i];
                for (int d = 0; d < dims; d++) sums[c][d] += data[i][d];
                counts[c]++;
            }
            for (int c = 0; c < k; c++) {
                if (counts[c] > 0) {
                    centroids[c] = l2Normalize(sums[c]);
                }
            }
        }
        return assignments;
    }

    private float[] centroid(float[][] data, List<Integer> indices) {
        int     dims = data[0].length;
        float[] sum  = new float[dims];
        for (int idx : indices) for (int d = 0; d < dims; d++) sum[d] += data[idx][d];
        return l2Normalize(sum);
    }

    @Override
    public String getProcessName() {
        return "Album generation";
    }
}
