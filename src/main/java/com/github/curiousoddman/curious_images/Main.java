package com.github.curiousoddman.curious_images;

import com.github.curiousoddman.curious_images.app.preloader.AnimatedPreloader;
import com.sun.javafx.application.LauncherImpl;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringBootApplication
@EnableTransactionManagement
public class Main {
    // FIXME: rerunning AI pipeline breaks people profile - there are other faces shown.
    // FIXME: Open containing directory for any image file context menu
    // FIXME: Duplicates screen - better view. some parts are hard to compare. Path is very long. Maybe highlight similar/different parts? Ensure whole contents can be fit into view.
    // FIXME: Duplicates screen load long, if large number of duplicates (lazy loading)?
    // FIXME: Duplicates screen - thumbnails are generated for all images at once, but should for only visible.
    public static void main(String[] args) {
        LauncherImpl.launchApplication(JavafxApplication.class, AnimatedPreloader.class, args);
    }
}
