package com.github.curiousoddman.curious_images.model.bundle;

import com.github.curiousoddman.curious_images.dbobj.tables.records.PhotoRecord;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.ListResourceBundle;

/**
 * Passed to {@code SlideshowController} via {@code FxmlLoader} when opening the slideshow.
 * {@code startIndex} is the index within {@code photos} of the image the user clicked.
 */
@Getter
@RequiredArgsConstructor
public class SlideshowBundle extends ListResourceBundle {
    private final List<PhotoRecord> photos;
    private final int startIndex;

    @Override
    protected Object[][] getContents() {
        return new Object[][]{
                {"photos", photos},
                {"startIndex", startIndex}
        };
    }
}

