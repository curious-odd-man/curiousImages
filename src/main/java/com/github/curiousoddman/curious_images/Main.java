package com.github.curiousoddman.curious_images;

import com.github.curiousoddman.curious_images.app.preloader.AnimatedPreloader;
import com.sun.javafx.application.LauncherImpl;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringBootApplication
@EnableTransactionManagement
public class Main {
    // TODO: engagement features - ask AI, for example - show photos for this day in past etc...
    public static void main(String[] args) {
        LauncherImpl.launchApplication(JavafxApplication.class, AnimatedPreloader.class, args);
    }
}
