package com.github.curiousoddman.curious_images.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.curiousoddman.curious_images.ui.FxmlLoader;
import com.github.curiousoddman.curious_images.util.VersionService;
import javafx.stage.Stage;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration
@RequiredArgsConstructor
public class ApplicationConfig {

    private final FxmlLoader fxmlLoader;
    @Value("${spring.title}")
    private final String     applicationTitle;
    private final ApplicationEventPublisher eventPublisher;
    private final VersionService            versionService;

    @Bean
    @Lazy
    public StageManager stageManager(Stage stage) {
        return new StageManager(fxmlLoader, stage,
                applicationTitle + "   -   " + versionService.getVersionLabel(),
                eventPublisher);
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
