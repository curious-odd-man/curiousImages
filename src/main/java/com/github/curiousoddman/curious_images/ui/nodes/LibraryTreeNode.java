package com.github.curiousoddman.curious_images.ui.nodes;

import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.materialdesign2.*;

/**
 * Value object backing nodes in the library {@link javafx.scene.control.TreeView}.
 *
 * @param displayName text shown in the tree
 * @param payload     what to load when this node is selected, or {@code null} for pure grouping
 *                    nodes (FOLDERS_ROOT, TIMELINE_ROOT, TIMELINE_YEAR) that show nothing
 * @param type        kind of node — drives icon selection and selection behaviour
 */
public record LibraryTreeNode(String displayName, NodePayload payload, NodeType type) {

    public enum NodeType {
        FOLDERS_ROOT,
        IMPORT_ROOT,
        FOLDER,
        TIMELINE_ROOT,
        TIMELINE_YEAR,
        TIMELINE_MONTH,
        TIMELINE_DAY,
        TIMELINE_UNDATED
    }

    /**
     * Returns the Ikonli icon for this node type.
     */
    public Ikon icon() {
        return switch (type) {
            case FOLDERS_ROOT -> MaterialDesignF.FOLDER_MULTIPLE;
            case IMPORT_ROOT -> MaterialDesignD.DATABASE;
            case FOLDER -> MaterialDesignF.FOLDER;
            case TIMELINE_ROOT -> MaterialDesignC.CLOCK_OUTLINE;
            case TIMELINE_YEAR -> MaterialDesignC.CALENDAR_BLANK;
            case TIMELINE_MONTH -> MaterialDesignC.CALENDAR;
            case TIMELINE_DAY -> MaterialDesignC.CALENDAR_TODAY;
            case TIMELINE_UNDATED -> MaterialDesignC.CALENDAR_REMOVE;
        };
    }

    @Override
    public String toString() {
        return displayName;
    }
}