package com.github.curiousoddman.curious_images.util.async.jobs;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Getter
@RequiredArgsConstructor
public class JobDescriptor {
    private final UUID    id;
    private final String  name;
    private final String  details;
    private final Instant submittedAt = Instant.now();
}
