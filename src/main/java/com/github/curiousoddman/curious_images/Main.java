package com.github.curiousoddman.curious_images;

import com.github.curiousoddman.curious_images.app.preloader.AnimatedPreloader;
import com.sun.javafx.application.LauncherImpl;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringBootApplication
@EnableTransactionManagement
public class Main {
    // FIXME: Duplicates screen - Maybe highlight similar/different parts?
    // FIXME: Duplicates detection would fail if one photo is rotated, while another is not.
    public static void main(String[] args) {
        LauncherImpl.launchApplication(JavafxApplication.class, AnimatedPreloader.class, args);
    }
}
