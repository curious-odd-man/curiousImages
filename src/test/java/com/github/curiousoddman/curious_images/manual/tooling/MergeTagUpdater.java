package com.github.curiousoddman.curious_images.manual.tooling;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MergeTagUpdater {

    // Matches: ('someTag')
    private static final Pattern TAG_PATTERN =
            Pattern.compile("\\('((?:''|[^'])*)'\\)");

    public static void updateMergeFile(Path mergeFile, Path newTagsFile) throws IOException {

        String sql = Files.readString(mergeFile);

        // Existing tags in merge file
        Set<String> existingTags = new LinkedHashSet<>();
        Matcher     matcher      = TAG_PATTERN.matcher(sql);

        while (matcher.find()) {
            existingTags.add(matcher.group(1)
                                    .replace("''", "'"));
        }

        // Read new tags
        List<String> lines = readAndParseNewTagsFile(newTagsFile);

        Set<String> tagsToAdd = new LinkedHashSet<>();

        for (String line : lines) {
            String tag = line.trim();
            if (tag.isEmpty()) {
                continue;
            }

            if (!existingTags.contains(tag)) {
                tagsToAdd.add(tag);
            }
        }

        if (tagsToAdd.isEmpty()) {
            System.out.println("No new tags.");
            return;
        }

        StringBuilder values = new StringBuilder();

        for (String tag : tagsToAdd) {
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

        System.out.println("Added " + tagsToAdd.size() + " new tags.");
    }

    private static List<String> readAndParseNewTagsFile(Path newTagsFile) throws IOException {
        return Files.readAllLines(newTagsFile);
    }

    public static void main(String[] args) throws IOException {

        Path mergeFile = Path.of("src/main/resources/db/migration/R__tag_data.sql");
        Path newTags   = Path.of("tags.txt");

        updateMergeFile(mergeFile, newTags);
    }
}