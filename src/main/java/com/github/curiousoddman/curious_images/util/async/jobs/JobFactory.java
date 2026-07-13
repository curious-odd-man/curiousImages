package com.github.curiousoddman.curious_images.util.async.jobs;

import com.github.curiousoddman.curious_images.config.AiConfig;
import com.github.curiousoddman.curious_images.domain.ai.AiPipelineJob;
import com.github.curiousoddman.curious_images.domain.ai.AlbumGenerationJob;
import com.github.curiousoddman.curious_images.domain.ai.ArcFaceEncoder;
import com.github.curiousoddman.curious_images.domain.ai.ClipImageEncoder;
import com.github.curiousoddman.curious_images.domain.ai.FaceAligner;
import com.github.curiousoddman.curious_images.domain.ai.PersonClusteringService;
import com.github.curiousoddman.curious_images.domain.ai.RetinaFaceDetector;
import com.github.curiousoddman.curious_images.domain.common.thumbnail.PersonService;
import com.github.curiousoddman.curious_images.domain.common.thumbnail.ThumbnailGenerationJob;
import com.github.curiousoddman.curious_images.domain.common.thumbnail.ThumbnailGenerator;
import com.github.curiousoddman.curious_images.domain.dedupe.DuplicateDetectionJob;
import com.github.curiousoddman.curious_images.domain.dedupe.PhotoHashRepository;
import com.github.curiousoddman.curious_images.domain.dedupe.PixelHasher;
import com.github.curiousoddman.curious_images.domain.imports.AddFilesJob;
import com.github.curiousoddman.curious_images.domain.imports.ImportJob;
import com.github.curiousoddman.curious_images.domain.imports.metadata.PhotoMetadataExtractor;
import com.github.curiousoddman.curious_images.domain.index.ClipVectorIndex;
import com.github.curiousoddman.curious_images.domain.index.FaceVectorIndex;
import com.github.curiousoddman.curious_images.model.AddFilesRequest;
import com.github.curiousoddman.curious_images.persistence.AlbumPhotoRepository;
import com.github.curiousoddman.curious_images.persistence.AlbumRepository;
import com.github.curiousoddman.curious_images.persistence.ClipEmbeddingRepository;
import com.github.curiousoddman.curious_images.persistence.ClusterRepository;
import com.github.curiousoddman.curious_images.persistence.DuplicateGroupRepository;
import com.github.curiousoddman.curious_images.persistence.DuplicateJobRepository;
import com.github.curiousoddman.curious_images.persistence.FaceEmbeddingRepository;
import com.github.curiousoddman.curious_images.persistence.FaceRepository;
import com.github.curiousoddman.curious_images.persistence.FaceThumbnailsRepository;
import com.github.curiousoddman.curious_images.persistence.FolderRepository;
import com.github.curiousoddman.curious_images.persistence.ImportRootRepository;
import com.github.curiousoddman.curious_images.persistence.PhotoPreviewRepository;
import com.github.curiousoddman.curious_images.persistence.PhotoRepository;
import com.github.curiousoddman.curious_images.persistence.ThumbnailRepository;
import com.github.curiousoddman.curious_images.util.ImageUtils;
import com.github.curiousoddman.curious_images.util.TimeProvider;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class JobFactory {
    private final DSLContext             dsl;
    private final ImportRootRepository   importRootRepository;
    private final FolderRepository       folderRepository;
    private final PhotoRepository        photoRepository;
    private final ThumbnailRepository    thumbnailRepository;
    private final PhotoPreviewRepository photoPreviewRepository;
    private final PhotoMetadataExtractor photoMetadataExtractor;
    private final ThumbnailGenerator     thumbnailGenerator;
    private final ImageUtils             imageUtils;
    private final TimeProvider           timeProvider;

    private final PhotoHashRepository      photoHashRepository;
    private final DuplicateJobRepository   duplicateJobRepository;
    private final DuplicateGroupRepository duplicateGroupRepository;
    private final PixelHasher              pixelHasher;

    @Value("${app.duplicate-detection.thread-count:4}")
    private final int                      duplicateDetectionThreadCount;
    private final FaceRepository           faceRepository;
    private final FaceEmbeddingRepository  faceEmbeddingRepository;
    private final ClipEmbeddingRepository  clipEmbeddingRepository;
    private final RetinaFaceDetector       retinaFaceDetector;
    private final ArcFaceEncoder           arcFaceEncoder;
    private final FaceAligner              faceAligner;
    private final ClipImageEncoder         clipImageEncoder;
    private final ClipVectorIndex          clipVectorIndex;
    private final FaceVectorIndex          faceVectorIndex;
    private final PersonClusteringService  personClusteringService;
    private final FaceThumbnailsRepository faceThumbnailsRepository;
    @Value("${ai.features.face-only:true}")
    private final boolean                  aiFaceDetectionOnly;
    private final AlbumRepository          albumRepository;
    private final AlbumPhotoRepository     albumPhotoRepository;
    private final AiConfig                 aiConfig;
    private final ClusterRepository        clusterRepository;
    private final PersonService            personService;

    public ImportJob createImportJob(List<String> paths) {
        return new ImportJob(
                dsl,
                importRootRepository,
                folderRepository,
                photoRepository,
                photoPreviewRepository,
                photoMetadataExtractor,
                timeProvider,
                paths
        );
    }

    /**
     * Supersedable, on-demand real-thumbnail generation for a page/selection of photo IDs — see
     * implementation plan §5/§6. Submitted by {@code LibraryController} whenever the grid is
     * about to render a set of photo IDs, never as a bulk sweep.
     */
    public ThumbnailGenerationJob createThumbnailGenerationJob(List<Long> photoIds) {
        return new ThumbnailGenerationJob(
                photoRepository,
                thumbnailRepository,
                imageUtils,
                thumbnailGenerator,
                timeProvider,
                photoIds
        );
    }

    public DuplicateDetectionJob createDuplicateDetectionJob() {
        return new DuplicateDetectionJob(
                dsl,
                photoRepository,
                photoHashRepository,
                duplicateJobRepository,
                duplicateGroupRepository,
                pixelHasher,
                timeProvider,
                duplicateDetectionThreadCount
        );
    }

    public AiPipelineJob createAiPipelineJob(JobManager jobManager) {
        return new AiPipelineJob(
                dsl,
                photoRepository,
                faceRepository,
                clusterRepository,
                faceEmbeddingRepository,
                clipEmbeddingRepository,
                retinaFaceDetector,
                arcFaceEncoder,
                faceAligner,
                clipImageEncoder,
                clipVectorIndex,
                faceVectorIndex,
                personClusteringService,
                timeProvider,
                faceThumbnailsRepository,
                jobManager,
                imageUtils,
                aiFaceDetectionOnly
        );
    }

    public AddFilesJob createAddFilesJob(AddFilesRequest request, JobManager jobManager) {
        return new AddFilesJob(
                createImportJob(List.of()),
                jobManager,
                request
        );
    }

    public AlbumGenerationJob createAlbumGenerationJob() {
        return new AlbumGenerationJob(
                dsl,
                albumRepository,
                albumPhotoRepository,
                clipEmbeddingRepository,
                aiConfig,
                timeProvider,
                personService,
                photoRepository
        );
    }
}
