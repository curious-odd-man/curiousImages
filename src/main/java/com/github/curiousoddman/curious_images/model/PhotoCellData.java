package com.github.curiousoddman.curious_images.model;

import com.github.curiousoddman.curious_images.dbobj.tables.records.PhotoRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.PhotoTagRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.TagEmbeddingRecord;
import com.github.curiousoddman.curious_images.ui.controller.custom.DetailRow;
import javafx.scene.image.Image;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.github.curiousoddman.curious_images.util.HumanReadableUtils.size;
import static org.kordamp.ikonli.bootstrapicons.BootstrapIcons.ASPECT_RATIO;
import static org.kordamp.ikonli.bootstrapicons.BootstrapIcons.CAMERA;
import static org.kordamp.ikonli.bootstrapicons.BootstrapIcons.CLOCK_HISTORY;
import static org.kordamp.ikonli.bootstrapicons.BootstrapIcons.DOWNLOAD;
import static org.kordamp.ikonli.bootstrapicons.BootstrapIcons.FILE_EARMARK_FONT_FILL;
import static org.kordamp.ikonli.bootstrapicons.BootstrapIcons.FOLDER2_OPEN;
import static org.kordamp.ikonli.bootstrapicons.BootstrapIcons.SERVER;
import static org.kordamp.ikonli.bootstrapicons.BootstrapIcons.TAG;

public record PhotoCellData(PhotoRecord photo, Image image, Map<PhotoTagRecord, TagEmbeddingRecord> tags,
                            List<PersonDetails> persons, boolean hasDuplicates) {

    public Long photoId() {
        return photo.getId();
    }

    public String tooltipText() {
        Map<String, DetailRow> photoDetailsText = getPhotoDetails(photo);
        StringBuilder          sb               = new StringBuilder();

        photoDetailsText.values()
                        .forEach(dr ->
                                sb.append(dr.getLabel())
                                  .append(": ")
                                  .append(dr.getValue())
                                  .append('\n')
                        );
        return sb.toString();
    }

    public static Map<String, DetailRow> getPhotoDetails(PhotoRecord photo) {
        Map<String, DetailRow> details = new LinkedHashMap<>();

        details.put("filename",
                new DetailRow(FILE_EARMARK_FONT_FILL, "Filename", photo.getFilename()));

        details.put("path",
                new DetailRow(FOLDER2_OPEN, "Path", photo.getAbsolutePath()));

        details.put("extension",
                new DetailRow(
                        TAG,
                        "Extension",
                        photo.getExtension() == null ? "—" : photo.getExtension()));

        details.put("size",
                new DetailRow(
                        SERVER,
                        "Size",
                        size(photo.getFileSize())));

        if (photo.getImageWidth() != null && photo.getImageHeight() != null) {
            details.put("dimensions",
                    new DetailRow(
                            ASPECT_RATIO,
                            "Dimensions",
                            photo.getImageWidth() + " × " + photo.getImageHeight()));
        }

        if (photo.getCaptureDate() != null) {
            String value = photo.getCaptureDate()
                                .toString();
            if (photo.getCaptureDateSource() != null) {
                value += " (" + photo.getCaptureDateSource() + ")";
            }

            details.put("captured",
                    new DetailRow(
                            CAMERA,
                            "Captured",
                            value));
        }

        if (photo.getImportedAt() != null) {
            details.put("imported",
                    new DetailRow(
                            DOWNLOAD,
                            "Imported",
                            photo.getImportedAt()
                                 .toString()));
        }

        if (photo.getLastSeenAt() != null) {
            details.put("lastSeen",
                    new DetailRow(
                            CLOCK_HISTORY,
                            "Last seen",
                            photo.getLastSeenAt()
                                 .toString()));
        }

        return details;
    }
}
