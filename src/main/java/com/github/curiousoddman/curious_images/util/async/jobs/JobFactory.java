package com.github.curiousoddman.curious_images.util.async.jobs;

import com.github.curiousoddman.curious_images.domain.ai.AiPipelineJob;
import com.github.curiousoddman.curious_images.domain.ai.ArcFaceEncoder;
import com.github.curiousoddman.curious_images.domain.ai.ClipImageEncoder;
import com.github.curiousoddman.curious_images.domain.ai.FaceAligner;
import com.github.curiousoddman.curious_images.domain.ai.PersonClusteringService;
import com.github.curiousoddman.curious_images.domain.ai.RetinaFaceDetector;
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
import com.github.curiousoddman.curious_images.persistence.ClipEmbeddingRepository;
import com.github.curiousoddman.curious_images.persistence.DuplicateGroupRepository;
import com.github.curiousoddman.curious_images.persistence.DuplicateJobRepository;
import com.github.curiousoddman.curious_images.persistence.FaceEmbeddingRepository;
import com.github.curiousoddman.curious_images.persistence.FaceRepository;
import com.github.curiousoddman.curious_images.persistence.FaceThumbnailsRepository;
import com.github.curiousoddman.curious_images.persistence.FolderRepository;
import com.github.curiousoddman.curious_images.persistence.ImportRootRepository;
import com.github.curiousoddman.curious_images.persistence.PhotoRepository;
import com.github.curiousoddman.curious_images.persistence.ThumbnailRepository;
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
    private final PhotoMetadataExtractor photoMetadataExtractor;
    private final ThumbnailGenerator     thumbnailGenerator;
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
    private final JobManager               jobManager;

    public ImportJob createImportJob(List<String> paths) {
        return new ImportJob(
                dsl,
                importRootRepository,
                folderRepository,
                photoRepository,
                thumbnailRepository,
                photoMetadataExtractor,
                thumbnailGenerator,
                timeProvider,
                paths
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

    public AiPipelineJob createAiPipelineJob() {
        return new AiPipelineJob(
                dsl,
                photoRepository,
                faceRepository,
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
                faceThumbnailsRepository
        );
    }

    public AddFilesJob createAddFilesJob(AddFilesRequest request) {
        return new AddFilesJob(
                createImportJob(List.of()),
                jobManager,
                request
        );
    }
}
