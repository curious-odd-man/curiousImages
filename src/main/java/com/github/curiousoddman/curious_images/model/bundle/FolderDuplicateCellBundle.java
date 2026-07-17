package com.github.curiousoddman.curious_images.model.bundle;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Collections;
import java.util.Enumeration;
import java.util.ResourceBundle;

/**
 * Smuggles typed data into
 * {@code FolderDuplicateCellController#initialize(URL, ResourceBundle)} through FXMLLoader's
 * {@code ResourceBundle} parameter — same trick as {@code DuplicateCellData} on the file-level
 * cell.
 */
@Getter
@RequiredArgsConstructor
public class FolderDuplicateCellBundle extends ResourceBundle {
    private final String  folderPath;
    private final int     groupCount;
    private final int     photoCount;
    private final long    totalSize;
    private final boolean initiallyChecked;

    @Override
    protected Object handleGetObject(String key) {
        return null;
    }

    @Override
    public Enumeration<String> getKeys() {
        return Collections.emptyEnumeration();
    }
}
