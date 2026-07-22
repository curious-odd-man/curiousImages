package com.github.curiousoddman.curious_images.model.bundle;

import com.github.curiousoddman.curious_images.model.ImageDetails;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Collections;
import java.util.Enumeration;
import java.util.ResourceBundle;
import java.util.function.Consumer;

@Getter
@RequiredArgsConstructor
public class PhotoCellResources extends ResourceBundle {
    private final Consumer<ImageDetails> imageDetailsConsumer;

    @Override
    protected Object handleGetObject(String key) {
        return null;
    }

    @Override
    public Enumeration<String> getKeys() {
        return Collections.emptyEnumeration();
    }
}
