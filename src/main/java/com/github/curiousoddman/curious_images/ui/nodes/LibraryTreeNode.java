package com.github.curiousoddman.curious_images.ui.nodes;

import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignD;
import org.kordamp.ikonli.materialdesign2.MaterialDesignF;

/**
 * Value object backing nodes in the library {@link javafx.scene.control.TreeView}.
 *
 * @param displayName text shown in the tree
 * @param payload     what to load when this node is selected, or {@code null} for pure grouping
 *                    nodes (FOLDERS_ROOT, TIMELINE_ROOT, TIMELINE_YEAR, ALBUMS_ROOT,
 *                    PERSONS_ROOT) that show nothing
 * @param type        kind of node — drives icon selection and selection behaviour
 */
public record LibraryTreeNode(String displayName, NodePayload payload, NodeType type) {

    public enum NodeType {
        // Folder tree
        FOLDERS_ROOT,
        IMPORT_ROOT,
        FOLDER,
        // Timeline
        TIMELINE_ROOT,
        TIMELINE_YEAR,
        TIMELINE_MONTH,
        TIMELINE_DAY,
        TIMELINE_UNDATED,
        // Albums
        ALBUMS_ROOT,
        ALBUM_EVENT,
        ALBUM_LOCATION,
        ALBUM_SIMILARITY,
        // Persons (face-based)
        PERSONS_ROOT,
        PERSON
    }

    /**
     * Returns the Ikonli icon for this node type.
     */
    // @formatter:off
    public Ikon icon() {
        return switch (type) {
            case FOLDERS_ROOT     -> MaterialDesignF.FOLDER_MULTIPLE;
            case IMPORT_ROOT      -> MaterialDesignD.DATABASE;
            case FOLDER           -> MaterialDesignF.FOLDER;
            case TIMELINE_ROOT    -> MaterialDesignC.CLOCK_OUTLINE;
            case TIMELINE_YEAR    -> MaterialDesignC.CALENDAR_BLANK;
            case TIMELINE_MONTH   -> MaterialDesignC.CALENDAR;
            case TIMELINE_DAY     -> MaterialDesignC.CALENDAR_TODAY;
            case TIMELINE_UNDATED -> MaterialDesignC.CALENDAR_REMOVE;
            case ALBUMS_ROOT      -> MaterialDesignA.ALBUM;
            case ALBUM_EVENT      -> MaterialDesignC.CAMERA_BURST;
            case ALBUM_LOCATION   -> MaterialDesignM.MAP_MARKER_MULTIPLE;
            case ALBUM_SIMILARITY -> MaterialDesignG.GOOGLE_PHOTOS;
            case PERSONS_ROOT     -> MaterialDesignA.ACCOUNT_GROUP;
            case PERSON           -> MaterialDesignA.ACCOUNT;
        };
    }
    // @formatter:on

    @Override
    public String toString() {
        return displayName;
    }
}
