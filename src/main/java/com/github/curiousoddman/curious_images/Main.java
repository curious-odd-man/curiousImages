package com.github.curiousoddman.curious_images;

import com.github.curiousoddman.curious_images.app.preloader.AnimatedPreloader;
import com.sun.javafx.application.LauncherImpl;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringBootApplication
@EnableTransactionManagement
public class Main {
    // FIXME: Duplicates screen - Maybe highlight similar/different parts?
    // FIXME: cr2 images ai pipeline fails - codec is not supported.
    // FIXME: Rotated face thumbnail is produced - need to investigate
    // FIXME: Duplicates detection would fail if one photo is rotated, while another is not.
    // FIXME: Duplicates detection - cannot delete files - DB constraints
    public static void main(String[] args) {
        LauncherImpl.launchApplication(JavafxApplication.class, AnimatedPreloader.class, args);
    }
}
