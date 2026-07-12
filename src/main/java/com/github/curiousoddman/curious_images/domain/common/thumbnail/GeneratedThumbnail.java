package com.github.curiousoddman.curious_images.domain.common.thumbnail;

import java.nio.file.Path;

public record GeneratedThumbnail(Path cachePath, int width, int height) {
}
