package com.github.curiousoddman.curious_images.util;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class VersionService {
    private final BuildProperties buildProperties;

    public String getVersionLabel() {
        return String.format("v%s  |  %s  |  %s",
                buildProperties.getVersion(),                  // from axion
                buildProperties.get("commit.hash"),            // custom field
                buildProperties.get("commit.date")             // custom field
        );
    }

    @PostConstruct
    void reportVersionInLogs() {
        log.info("Running version {}", getVersionLabel());
    }
}