package com.github.curiousoddman.curious_images.model.bundle;

import com.github.curiousoddman.curious_images.model.Media;
import com.github.curiousoddman.curious_images.ui.controller.custom.DetailRow;
import javafx.scene.image.Image;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Collection;
import java.util.List;
import java.util.ListResourceBundle;

@Getter
@RequiredArgsConstructor
public final class DuplicateCellDataBundle extends ListResourceBundle {
    private final Image                 image;
    private final Collection<DetailRow> rows;
    private final List<Media>           groupPhotos;
    private final int                   currentIndex;

    @Override
    protected Object[][] getContents() {
        return new Object[][]{
                {"image", image},
                {"currentIndex", currentIndex},
                {"groupPhotos", groupPhotos},
                {"rows", rows}
        };
    }
}
