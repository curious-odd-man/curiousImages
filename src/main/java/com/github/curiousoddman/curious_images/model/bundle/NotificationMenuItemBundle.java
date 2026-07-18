package com.github.curiousoddman.curious_images.model.bundle;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.kordamp.ikonli.javafx.FontIcon;

import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.ResourceBundle;

@Getter
@RequiredArgsConstructor
public class NotificationMenuItemBundle extends ResourceBundle {
    private final FontIcon     icon;
    private final String       message;
    private final List<String> details;
    private final Runnable     onDismiss;

    @Override
    protected Object handleGetObject(String key) {
        return null;
    }

    @Override
    public Enumeration<String> getKeys() {
        return Collections.emptyEnumeration();
    }
}
