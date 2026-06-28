package com.github.curiousoddman.curious_images.model.bundle;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.ListResourceBundle;

/**
 * Data carrier injected into {@code add_files.fxml} when it is opened.
 *
 */
@Getter
@RequiredArgsConstructor
public class AddFilesBundle extends ListResourceBundle {
    private final List<String> prefilledSourcePaths;
    private final String       prefilledDestinationRoot;

    @Override
    protected Object[][] getContents() {
        return new Object[][]{
                {"prefilledSourcePaths", prefilledSourcePaths},
                {"prefilledDestinationRoot", prefilledDestinationRoot}
        };
    }
}
