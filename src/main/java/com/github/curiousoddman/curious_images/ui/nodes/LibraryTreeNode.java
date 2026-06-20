package com.github.curiousoddman.curious_images.ui.nodes;

/**
 * Value object backing nodes in the library {@link javafx.scene.control.TreeView}.
 * <p>
 * Deliberately generic rather than tied 1:1 to {@code IMPORT_ROOT} / {@code FOLDER} rows: a future
 * grouping layer ("collections") is expected to sit between an import root and its folder tree
 * (root -&gt; collections -&gt; ... -&gt; folders). Any node — regardless of {@link NodeType} — can carry
 * a {@code folderId} to load photos for, and any node can have children, so introducing the new
 * type later should only mean adding a case to the tree-building code in {@code LibraryController},
 * not reshaping it.
 *
 * @param displayName text shown in the tree
 * @param folderId    id of the {@code FOLDER} row whose photos should be shown when this node is
 *                    selected, or {@code null} if this node has no photos of its own (e.g. a pure
 *                    grouping node, or an import root that hasn't been scanned yet)
 * @param type        kind of node, for future use (icons, context menus, etc.)
 */
public record LibraryTreeNode(String displayName, Long folderId, NodeType type) {

    public enum NodeType {
        IMPORT_ROOT,
        FOLDER,
        /**
         * Reserved: a future user-defined grouping layer between import roots and folders.
         */
        COLLECTION
    }

    @Override
    public String toString() {
        return displayName;
    }
}
