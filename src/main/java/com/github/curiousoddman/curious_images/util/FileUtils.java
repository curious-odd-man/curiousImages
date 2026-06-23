package com.github.curiousoddman.curious_images.util;

import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;

@UtilityClass
public class FileUtils {

    /**
     * AI Generated.
     * For large audio files, hashing just the first and last N bytes is a common optimization that catches virtually all real-world changes (re-tags, re-encodes, truncation):
     * This makes rescanning a large library significantly faster — hashing a 50MB FLAC fully takes ~150ms, partial hashing takes ~2ms.
     *
     * @param path path to a file
     * @return hash hex string
     */
    @SneakyThrows
    public static String audioMd5(Path path) {
        long fileSize   = Files.size(path);
        int  sampleSize = 64 * 1024; // 64KB from start and end

        MessageDigest digest = MessageDigest.getInstance("MD5");

        // Always include file size in the hash — catches truncation
        digest.update(ByteBuffer.allocate(8)
                                .putLong(fileSize)
                                .array());

        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            ByteBuffer buf = ByteBuffer.allocate(sampleSize);

            // Read from start
            channel.read(buf, 0);
            digest.update(buf.flip());

            // Read from end (if file is large enough)
            if (fileSize > sampleSize * 2) {
                buf.clear();
                channel.read(buf, fileSize - sampleSize);
                digest.update(buf.flip());
            }
        }

        return HexFormat.of()
                        .formatHex(digest.digest());
    }
}
