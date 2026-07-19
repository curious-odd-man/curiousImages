package com.github.curiousoddman.curious_images.manual.tooling;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Slf4j
public class MergeTagUpdater {

    // Matches: ('someTag')
    private static final Pattern TAG_PATTERN =
            Pattern.compile("\\('((?:''|[^'])*)'\\)");

    @SneakyThrows
    public static void updateMergeFile(Path mergeFile, Path newTagsFile) {
        log.info("Reading {}", newTagsFile);
        String sql = Files.readString(mergeFile);

        // Existing tags in merge file
        Set<String> existingTags = new LinkedHashSet<>();
        Matcher     matcher      = TAG_PATTERN.matcher(sql);

        while (matcher.find()) {
            existingTags.add(matcher.group(1)
                                    .replace("''", "'"));
        }

        log.info("Found {} known tags", existingTags.size());

        // Read new tags
        List<String> lines = readAndParseNewTagsFile(newTagsFile);
        log.info("Found {} lines", lines.size());

        Set<String> tagsToAdd = new LinkedHashSet<>();

        for (String line : lines) {
            String tag = line.trim()
                             .toLowerCase();
            if (tag.isEmpty()) {
                continue;
            }

            if (!existingTags.contains(tag)) {
                tagsToAdd.add(tag);
            }
        }

        if (tagsToAdd.isEmpty()) {
            log.warn("No new tags.");
            return;
        }

        StringBuilder values = new StringBuilder();

        List<String> sortedTags = new ArrayList<>(tagsToAdd);
        Collections.sort(sortedTags);

        for (String tag : sortedTags) {
            values.append(",\n            ('")
                  .append(tag.replace("'", "''"))
                  .append("')");
        }

        String marker = "\n        ) AS src(tag)";

        int pos = sql.indexOf(marker);

        if (pos < 0) {
            throw new IllegalStateException("Cannot locate VALUES section.");
        }

        sql = sql.substring(0, pos)
                + values
                + sql.substring(pos);

        Files.writeString(mergeFile, sql);

        log.info("Added {} new tags.", tagsToAdd.size());
    }

    private static List<String> readAndParseNewTagsFile(Path newTagsFile) throws IOException {
        return Files.readAllLines(newTagsFile);
    }

    public static void main(String[] args) throws IOException {

        Path mergeFile = Path.of("src/main/resources/db/migration/R__tag_data.sql");
        try (Stream<Path> list = Files.list(Path.of("W:\\Programming\\git\\curiousImages\\new"))) {
            list.forEach(p -> {
                updateMergeFile(mergeFile, p);
            });
        }
    }
}