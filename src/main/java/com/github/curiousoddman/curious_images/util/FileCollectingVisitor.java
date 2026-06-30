package com.github.curiousoddman.curious_images.util;

import lombok.RequiredArgsConstructor;

import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Set;

@RequiredArgsConstructor
public class FileCollectingVisitor extends SimpleFileVisitor<Path> {
    private final Set<String> supportedExtensions;
    private final List<Path>  found;

    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
        if (attrs.isRegularFile()
                && supportedExtensions.contains(FileUtils.extensionOf(file.getFileName()
                                                                          .toString()))) {
            found.add(file);
        }
        return FileVisitResult.CONTINUE;
    }
}
