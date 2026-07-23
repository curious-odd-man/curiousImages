package com.github.curiousoddman.curious_images.model;

import com.github.curiousoddman.curious_images.dbobj.tables.records.MediaPhotoRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.MediaVideoRecord;
import com.github.curiousoddman.curious_images.util.Either;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.function.Consumer;
import java.util.function.Function;

@RequiredArgsConstructor
public class Media implements Either<MediaPhotoRecord, MediaVideoRecord> {
    private final Either<MediaPhotoRecord, MediaVideoRecord> either;

    public static Media video(MediaVideoRecord videoRecord) {
        return new Media(Either.right(videoRecord));
    }

    public static Media photo(MediaPhotoRecord photoRecord) {
        return new Media(Either.left(photoRecord));
    }

    @Override
    public boolean isLeft() {
        return either.isLeft();
    }

    @Override
    public MediaPhotoRecord getLeft() {
        return either.getLeft();
    }

    @Override
    public MediaVideoRecord getRight() {
        return either.getRight();
    }

    public MediaPhotoRecord photo() {
        return either.getLeft();
    }

    public MediaVideoRecord video() {
        return either.getRight();
    }

    @Override
    public <T> T map(Function<? super MediaPhotoRecord, ? extends T> leftMapper, Function<? super MediaVideoRecord, ? extends T> rightMapper) {
        return either.map(leftMapper, rightMapper);
    }

    @Override
    public void forEach(Consumer<Either<MediaPhotoRecord, MediaVideoRecord>> leftConsumer, Consumer<Either<MediaPhotoRecord, MediaVideoRecord>> rightConsumer) {
        either.forEach(leftConsumer, rightConsumer);
    }

    public Long getId() {
        return either.map(MediaPhotoRecord::getId, MediaVideoRecord::getId);
    }

    public MediaType getMediaType() {
        return either.map(MediaPhotoRecord::getMediaType, MediaVideoRecord::getMediaType);
    }

    public Long getFolderId() {
        return either.map(MediaPhotoRecord::getFolderId, MediaVideoRecord::getFolderId);
    }

    public String getAbsolutePath() {
        return either.map(MediaPhotoRecord::getAbsolutePath, MediaVideoRecord::getAbsolutePath);
    }

    public String getFilename() {
        return either.map(MediaPhotoRecord::getFilename, MediaVideoRecord::getFilename);
    }

    public String getExtension() {
        return either.map(MediaPhotoRecord::getExtension, MediaVideoRecord::getExtension);
    }

    public Long getFileSize() {
        return either.map(MediaPhotoRecord::getFileSize, MediaVideoRecord::getFileSize);
    }

    public Integer getWidth() {
        return either.map(MediaPhotoRecord::getWidth, MediaVideoRecord::getWidth);
    }

    public Integer getHeight() {
        return either.map(MediaPhotoRecord::getHeight, MediaVideoRecord::getHeight);
    }

    public LocalDateTime getCaptureDate() {
        return either.map(MediaPhotoRecord::getCaptureDate, MediaVideoRecord::getCaptureDate);
    }

    public String getCaptureDateSource() {
        return either.map(MediaPhotoRecord::getCaptureDateSource, MediaVideoRecord::getCaptureDateSource);
    }

    public LocalDateTime getImportedAt() {
        return either.map(MediaPhotoRecord::getImportedAt, MediaVideoRecord::getImportedAt);
    }

    public LocalDateTime getLastSeenAt() {
        return either.map(MediaPhotoRecord::getLastSeenAt, MediaVideoRecord::getLastSeenAt);
    }

    public String getCameraMake() {
        return either.map(MediaPhotoRecord::getCameraMake, MediaVideoRecord::getCameraMake);
    }

    public String getCameraModel() {
        return either.map(MediaPhotoRecord::getCameraModel, MediaVideoRecord::getCameraModel);
    }

    public Double getGpsLat() {
        return either.map(MediaPhotoRecord::getGpsLat, MediaVideoRecord::getGpsLat);
    }

    public Double getGpsLon() {
        return either.map(MediaPhotoRecord::getGpsLon, MediaVideoRecord::getGpsLon);
    }

    public Double getGpsAltitude() {
        return either.map(MediaPhotoRecord::getGpsAltitude, MediaVideoRecord::getGpsAltitude);
    }

    public Boolean getAiFaceDetectDone() {
        return either.map(MediaPhotoRecord::getAiFaceDetectDone, MediaVideoRecord::getAiFaceDetectDone);
    }

    public Boolean getAiFaceEmbedDone() {
        return either.map(MediaPhotoRecord::getAiFaceEmbedDone, MediaVideoRecord::getAiFaceEmbedDone);
    }

    public Boolean getAiClipEmbedDone() {
        return either.map(MediaPhotoRecord::getAiClipEmbedDone, MediaVideoRecord::getAiClipEmbedDone);
    }

    public Boolean getAiTagDone() {
        return either.map(MediaPhotoRecord::getAiTagDone, MediaVideoRecord::getAiTagDone);
    }

    public Boolean getAiLuceneIndexDone() {
        return either.map(MediaPhotoRecord::getAiLuceneIndexDone, MediaVideoRecord::getAiLuceneIndexDone);
    }

    public String getAiLastError() {
        return either.map(MediaPhotoRecord::getAiLastError, MediaVideoRecord::getAiLastError);
    }

    public Short getAiRetryCount() {
        return either.map(MediaPhotoRecord::getAiRetryCount, MediaVideoRecord::getAiRetryCount);
    }

    public LocalDateTime getAiUpdatedAt() {
        return either.map(MediaPhotoRecord::getAiUpdatedAt, MediaVideoRecord::getAiUpdatedAt);
    }

    public boolean isPhoto() {
        return either.isLeft();
    }

    public boolean isVideo() {
        return !isPhoto();
    }
}
