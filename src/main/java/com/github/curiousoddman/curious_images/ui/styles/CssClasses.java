package com.github.curiousoddman.curious_images.ui.styles;

import lombok.experimental.UtilityClass;

/**
 * Central registry of every CSS class name the Java code references, so a class name is never
 * duplicated as a string literal in more than one controller. Each constant here must have a
 * matching rule in {@code /styles/global.css}.
 */
@UtilityClass
public class CssClasses {

    // Duplicate-resolution hover preview (DuplicatesController)
    public static final String KEEP_PREVIEW = "keep-preview";
    public static final String DROP_PREVIEW = "drop-preview";

    // Face-picker grid cell selection border (FacePickerCellController toggles this dynamically;
    // the base "face-cell" class itself is applied statically in face_picker_cell.fxml)
    public static final String FACE_CELL_SELECTED = "face-cell-selected";

    // Inline-editable Name/DoB/Notes fields (PersonDetailController)
    public static final String EDITABLE_FIELD        = "editable-field";
    public static final String EDITABLE_FIELD_ACTIVE = "editable-field-active";
}
