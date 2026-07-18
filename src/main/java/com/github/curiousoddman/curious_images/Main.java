package com.github.curiousoddman.curious_images;

import com.github.curiousoddman.curious_images.app.preloader.AnimatedPreloader;
import com.sun.javafx.application.LauncherImpl;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringBootApplication
@EnableTransactionManagement
public class Main {
    // TODO: fix/verify search/index
    // TODO: engagement features - rank, order and plan
    // TODO: metadata editing (DB only with "changed" flag for later dump to files if necessary)
    // TODO: Selections/collections/albums whatever - integrate photo-shoot-magic here
    // TODO: Scan and add video
    public static void main(String[] args) {
        LauncherImpl.launchApplication(JavafxApplication.class, AnimatedPreloader.class, args);
    }
}
