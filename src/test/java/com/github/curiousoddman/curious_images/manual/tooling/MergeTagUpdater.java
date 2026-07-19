package com.github.curiousoddman.curious_images.manual.tooling;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// TODO: for further expansion: https://github.com/dariusk/corpora/tree/master/data/animals
@Slf4j
public class MergeTagUpdater {

    // Matches: ('someTag')
    private static final Pattern TAG_PATTERN =
            Pattern.compile("\\('((?:''|[^'])*)'\\)");

    @SneakyThrows
    public static void updateMergeFile(Path mergeFile, List<ReadLine> lineWithCategory) {
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
        log.info("Found {} lines", lineWithCategory.size());

        Set<ReadLine> tagsToAdd = new LinkedHashSet<>();

        for (ReadLine line : lineWithCategory) {
            String tag = line.line();

            if (!existingTags.contains(tag)) {
                tagsToAdd.add(line);
            }
        }

        if (tagsToAdd.isEmpty()) {
            log.warn("No new tags.");
            return;
        }

        StringBuilder values = new StringBuilder();

        List<ReadLine> sortedTags = new ArrayList<>(tagsToAdd);
        sortedTags.sort(Comparator.comparing(ReadLine::category)
                                  .thenComparing(ReadLine::line));

        for (ReadLine tag : sortedTags) {
            values.append(",\n            ('")
                  .append(tag.category())
                  .append("', '")
                  .append(tag.line()
                             .replace("'", "''"))
                  .append("')");
        }

        String marker = "\n        ) AS src(category, tag)";

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

    @SneakyThrows
    private static List<ReadLine> readAndParseNewTagsFile(Path newTagsFile) {
        String fileName = newTagsFile.getFileName()
                                     .toString();
        int    i        = fileName.indexOf('.');
        String category = fileName.substring(0, i);
        return Files.readAllLines(newTagsFile)
                    .stream()
                    .map(String::trim)
                    .map(String::toLowerCase)
                    .filter(l -> !l.isEmpty())
                    .map(MergeTagUpdater::removeEnquote)
                    .collect(Collectors.toSet())
                    .stream()
                    .map(l -> new ReadLine(l, category))
                    .toList();
    }

    private static String removeEnquote(String l) {
        int startIndex = 0;
        int endIndex   = l.length();
        if (l.startsWith("\"")) {
            startIndex = 1;
        }
        if (l.endsWith("\",")) {
            endIndex = l.length() - 2;
        } else if (l.endsWith("\"")) {
            endIndex = l.length() - 1;
        }
        return l.substring(startIndex, endIndex);
    }

    public record ReadLine(String line, String category) {

    }

    public static void main(String[] args) throws IOException {

        Path           mergeFile  = Path.of("src/main/resources/db/migration/R__tag_data.sql");
        List<ReadLine> inputLines = new ArrayList<>();
        try (Stream<Path> list = Files.list(Path.of("W:\\Programming\\git\\curiousImages\\new"))) {
            list.forEach(path ->
                    inputLines.addAll(readAndParseNewTagsFile(path))
            );
        }

        Map<String, List<String>> collect = inputLines
                .stream()
                .collect(Collectors.groupingBy(ReadLine::line, Collectors.mapping(ReadLine::category, Collectors.toList())));

        for (Map.Entry<String, List<String>> entry : collect.entrySet()) {
            if (entry.getValue()
                     .size() > 1) {
                log.info("Duplicate found: {} - in categories -> {}", entry.getKey(), entry.getValue());
            }
        }


        updateMergeFile(mergeFile, inputLines);
    }
}