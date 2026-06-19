package com.github.curiousoddman.curious_images.model.bundle;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.ListResourceBundle;

@Getter
@RequiredArgsConstructor
public class RescanBundle extends ListResourceBundle {
    private final String libraryRoot;
    @Override
    protected Object[][] getContents() {
        return new Object[][]{
                {"libraryRoot", libraryRoot}
        };
    }
}
