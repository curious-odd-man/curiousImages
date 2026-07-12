package com.github.curiousoddman.curious_images;

import com.github.curiousoddman.curious_images.app.preloader.AnimatedPreloader;
import com.sun.javafx.application.LauncherImpl;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringBootApplication
@EnableTransactionManagement
public class Main {
    // FIXME: Open containing directory for any image file context menu
    // FIXME: Duplicates screen - better view. some parts are hard to compare. Path is very long. Maybe highlight similar/different parts? Ensure whole contents can be fit into view.
    // FIXME: Duplicates screen load long, if large number of duplicates (lazy loading)?
    // FIXME: Duplicates screen - thumbnails are generated for all images at once, but should for only visible.
    // FIXME: cr2 images ai pipeline fails - codec is not supported.
    // FIXME: Rotated face thumbnail is produced - need to investigate
    // FIXME: Duplicates detection would fail if one photo is rotated, while another is not.
    public static void main(String[] args) {
        LauncherImpl.launchApplication(JavafxApplication.class, AnimatedPreloader.class, args);
    }
}
