package com.github.curiousoddman.curious_images.model;

import com.github.curiousoddman.curious_images.dbobj.tables.records.MediaPhotoRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.MediaRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.MediaTagRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.MediaVideoRecord;
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

public record GridCellData(Media media, Image image,
                           Map<MediaTagRecord, TagEmbeddingRecord> tags, List<PersonDetails> persons,
                           boolean hasDuplicates) {

    public Long mediaId() {
        return media.getId();
    }

    public boolean isPhoto() {
        return media.isPhoto();
    }

    public boolean isVideo() {
        return !isPhoto();
    }

    public MediaPhotoRecord photo() {
        return media.photo();
    }

    public MediaVideoRecord video() {
        return media.video();
    }

    public String tooltipText() {
        Map<String, DetailRow> mediaDetails = getMediaDetails(media);
        StringBuilder          sb           = new StringBuilder();

        mediaDetails.values()
                    .forEach(dr ->
                            sb.append(dr.getLabel())
                              .append(": ")
                              .append(dr.getValue())
                              .append('\n')
                    );
        return sb.toString();
    }

    public static Map<String, DetailRow> getMediaDetails(Media rawMedia) {
        MediaRecord media = rawMedia.map(
                v -> v.into(MediaRecord.class),
                v -> v.into(MediaRecord.class)
        );

        Map<String, DetailRow> details = new LinkedHashMap<>();

        details.put("filename",
                new DetailRow(FILE_EARMARK_FONT_FILL, "Filename", media.getFilename()));

        details.put("path",
                new DetailRow(FOLDER2_OPEN, "Path", media.getAbsolutePath()));

        details.put("extension",
                new DetailRow(
                        TAG,
                        "Extension",
                        media.getExtension() == null ? "—" : media.getExtension()));

        details.put("size",
                new DetailRow(
                        SERVER,
                        "Size",
                        size(media.getFileSize())));

        if (media.getWidth() != null && media.getHeight() != null) {
            details.put("dimensions",
                    new DetailRow(
                            ASPECT_RATIO,
                            "Dimensions",
                            media.getWidth() + " × " + media.getHeight()));
        }

        if (media.getCaptureDate() != null) {
            String value = media.getCaptureDate()
                                .toString();
            if (media.getCaptureDateSource() != null) {
                value += " (" + media.getCaptureDateSource() + ")";
            }

            details.put("captured",
                    new DetailRow(
                            CAMERA,
                            "Captured",
                            value));
        }

        if (media.getImportedAt() != null) {
            details.put("imported",
                    new DetailRow(
                            DOWNLOAD,
                            "Imported",
                            media.getImportedAt()
                                 .toString()));
        }

        if (media.getLastSeenAt() != null) {
            details.put("lastSeen",
                    new DetailRow(
                            CLOCK_HISTORY,
                            "Last seen",
                            media.getLastSeenAt()
                                 .toString()));
        }

        return details;
    }
}
