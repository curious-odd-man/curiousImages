package com.github.curiousoddman.curious_images.util;

import com.github.curiousoddman.curious_images.util.async.InterruptChecker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

@Slf4j
@RequiredArgsConstructor
public class CopyFileTreeVisitor extends SimpleFileVisitor<Path> {
    private final InterruptChecker interruptChecker;
    private final Path             src;
    private final Path             copyRoot;

    @Override
    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
            throws IOException {
        if (interruptChecker.shouldInterrupt()) {
            return FileVisitResult.TERMINATE;
        }
        Path relative = src.relativize(dir);
        Path target   = copyRoot.resolve(relative);
        Files.createDirectories(target);
        return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
            throws IOException {
        if (interruptChecker.shouldInterrupt()) {
            return FileVisitResult.TERMINATE;
        }
        Path relative = src.relativize(file);
        Path target   = copyRoot.resolve(relative);
        FileUtils.copyFileIfDifferentSize(file, target);
        return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFileFailed(Path file, IOException exc) {
        log.warn("AddFilesService: could not copy {}", file, exc);
        return FileVisitResult.CONTINUE;
    }
}
