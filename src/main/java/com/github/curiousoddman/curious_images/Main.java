package com.github.curiousoddman.curious_images;

import com.github.curiousoddman.curious_images.app.preloader.AnimatedPreloader;
import com.sun.javafx.application.LauncherImpl;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringBootApplication
@EnableTransactionManagement
public class Main {
    // TODO: search:
    //      - add @Person and #Tag search options with auto-complete suggestions
    // TODO: engagement features - rank, order and plan - see doc
    // TODO: metadata editing (DB only with "changed" flag for later dump to files if necessary)
    // TODO: Selections/collections/albums whatever - integrate photo-shoot-magic here
    // TODO: Scan and add video
    // TODO: user friendly configuration (paths, cuda vs cpu, AI features...)
    // TODO: AI models eviction - no need to store those in memory all the time.
    public static void main(String[] args) {
        LauncherImpl.launchApplication(JavafxApplication.class, AnimatedPreloader.class, args);
    }
}
