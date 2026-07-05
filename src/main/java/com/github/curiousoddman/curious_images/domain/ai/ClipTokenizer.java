package com.github.curiousoddman.curious_images.domain.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-Java BPE tokenizer compatible with the OpenAI CLIP ViT-B/32 text encoder.
 * <p>
 * Vocabulary and merge rules are loaded from {@code /clip-tokenizer/vocab.json} and
 * {@code /clip-tokenizer/merges.txt} on the classpath (download from the openai/CLIP GitHub
 * repository and place them in {@code src/main/resources/clip-tokenizer/}).
 * <p>
 * Port of the reference Python implementation in {@code clip/simple_tokenizer.py}, translating
 * the regex-based pre-tokenisation and iterative BPE merge loop to Java.
 *
 * <p>Output is always padded/truncated to exactly 77 tokens with the SOT token prepended
 * and EOT token appended, matching the format the CLIP text encoder expects.
 */
@Slf4j
@Component
public class ClipTokenizer {

    private static final int    CONTEXT_LENGTH  = 77;
    private static final String VOCAB_RESOURCE  = "/clip-tokenizer/vocab.json";
    private static final String MERGES_RESOURCE = "/clip-tokenizer/merges.txt";

    // CLIP uses specific SOT/EOT tokens
    private static final String SOT_TEXT = "<|startoftext|>";
    private static final String EOT_TEXT = "<|endoftext|>";

    // Regex to pre-tokenise text (same as CLIP reference implementation)
    private static final Pattern WORD_PATTERN = Pattern.compile(
            "(?i)<\\|startoftext\\|>|<\\|endoftext\\|>|'s|'t|'re|'ve|'m|'ll|'d" +
                    "|[\\p{L}]+|[\\p{N}]|[^\\s\\p{L}\\p{N}]+",
            Pattern.UNICODE_CHARACTER_CLASS);

    private Map<String, Integer>      encoder;     // token string → id
    private Map<Integer, String>      decoder;     // id → token string
    private Map<String, Integer>      bpeRanks;    // "a b" merge → rank
    private Map<String, List<String>> bpeCache;    // word → merged tokens (memoised)

    private int sotToken;
    private int eotToken;

    @PostConstruct
    public void init() throws IOException {
        loadVocab();
        loadMerges();
        bpeCache = new HashMap<>();
        sotToken = encoder.get(SOT_TEXT.toLowerCase());
        eotToken = encoder.get(EOT_TEXT.toLowerCase());
        log.info("CLIP tokenizer initialised: {} vocab entries, {} merge rules",
                encoder.size(), bpeRanks.size());
    }

    /**
     * Tokenises {@code text} and returns a {@code long[1][77]} tensor ready for the CLIP
     * text encoder. The output is SOT + tokens + EOT, padded with zeros to 77 tokens.
     */
    public long[][] tokenize(String text) {
        List<Integer> tokens = encode(text);

        // SOT + content + EOT, truncate if needed
        List<Integer> result = new ArrayList<>();
        result.add(sotToken);
        int remain = CONTEXT_LENGTH - 2;
        for (int i = 0; i < Math.min(tokens.size(), remain); i++) {
            result.add(tokens.get(i));
        }
        result.add(eotToken);

        long[][] output = new long[1][CONTEXT_LENGTH];
        for (int i = 0; i < result.size(); i++) {
            output[0][i] = result.get(i);
        }
        return output;
    }

    // ── Encoding ──────────────────────────────────────────────────────────────

    private List<Integer> encode(String text) {
        List<Integer> tokens = new ArrayList<>();
        Matcher m = WORD_PATTERN.matcher(text.toLowerCase(Locale.ROOT)
                                             .strip());
        while (m.find()) {
            String word = m.group();
            if (word.equals(SOT_TEXT.toLowerCase()) || word.equals(EOT_TEXT.toLowerCase())) {
                tokens.add(encoder.getOrDefault(word, eotToken));
                continue;
            }
            // Convert word to byte-level representation, then BPE
            StringBuilder sb    = new StringBuilder();
            byte[]        bytes = word.getBytes(StandardCharsets.UTF_8);
            for (byte b : bytes) {
                sb.append(byteToUnicode(b & 0xFF));
            }
            // Mark end-of-word with </w> suffix on last character
            String       wordStr    = sb.toString();
            List<String> wordTokens = bpe(wordStr + "</w>");
            for (String t : wordTokens) {
                tokens.add(encoder.getOrDefault(t, eotToken));
            }
        }
        return tokens;
    }

    /**
     * Iterative BPE merge. Results are memoised in {@link #bpeCache}.
     */
    private List<String> bpe(String word) {
        if (bpeCache.containsKey(word)) {
            return bpeCache.get(word);
        }

        // Start with characters as individual tokens (split into Unicode code points as strings)
        List<String> symbols = new ArrayList<>();
        int[] cps = word.codePoints()
                        .toArray();
        for (int i = 0; i < cps.length; i++) {
            // Detect </w> suffix: last 4 chars
            if (i == cps.length - 4 && word.endsWith("</w>")) {
                // Add the </w> as a single token
                symbols.add("</w>");
                break;
            }
            symbols.add(new String(Character.toChars(cps[i])));
        }
        // Re-handle: split properly respecting the </w> suffix
        symbols = splitWordToSymbols(word);

        while (symbols.size() > 1) {
            // Find the highest-ranked adjacent pair
            int bestRank = Integer.MAX_VALUE;
            int bestIdx  = -1;
            for (int i = 0; i < symbols.size() - 1; i++) {
                String pair = symbols.get(i) + " " + symbols.get(i + 1);
                int    rank = bpeRanks.getOrDefault(pair, Integer.MAX_VALUE);
                if (rank < bestRank) {
                    bestRank = rank;
                    bestIdx = i;
                }
            }
            if (bestIdx < 0 || bestRank == Integer.MAX_VALUE) {
                break;
            }

            // Merge the best pair everywhere in the symbol list
            String       merged = symbols.get(bestIdx) + symbols.get(bestIdx + 1);
            List<String> next   = new ArrayList<>();
            int          i      = 0;
            while (i < symbols.size()) {
                if (i == bestIdx && i + 1 < symbols.size()
                        && symbols.get(i)
                                  .equals(symbols.get(bestIdx))
                        && symbols.get(i + 1)
                                  .equals(symbols.get(bestIdx + 1))) {
                    next.add(merged);
                    i += 2;
                    // Continue merging all occurrences of the same pair
                    while (i + 1 < symbols.size()
                            && symbols.get(i)
                                      .equals(symbols.get(bestIdx))
                            && symbols.get(i + 1)
                                      .equals(symbols.get(bestIdx + 1))) {
                        next.add(merged);
                        i += 2;
                    }
                } else {
                    next.add(symbols.get(i));
                    i++;
                }
            }
            symbols = next;
        }

        bpeCache.put(word, symbols);
        return symbols;
    }

    /**
     * Splits a word (ending with {@code </w>}) into its initial BPE symbols.
     * All characters are individual symbols except the closing {@code </w>}.
     */
    private List<String> splitWordToSymbols(String word) {
        List<String> result = new ArrayList<>();
        if (word.endsWith("</w>")) {
            String stem = word.substring(0, word.length() - 4);
            stem.codePoints()
                .forEach(cp -> result.add(new String(Character.toChars(cp))));
            result.add("</w>");
        } else {
            word.codePoints()
                .forEach(cp -> result.add(new String(Character.toChars(cp))));
        }
        return result;
    }

    // ── Byte-to-Unicode mapping ───────────────────────────────────────────────
    // CLIP uses a specific mapping of bytes [0,255] to printable Unicode characters
    // so that byte-level BPE has no unknown tokens.

    private static final char[] BYTE_ENCODER = buildByteEncoder();

    private static char[] buildByteEncoder() {
        // Visible ASCII ranges that map to themselves
        List<Integer> bs = new ArrayList<>();
        for (int b = '!'; b <= '~'; b++) bs.add(b);
        for (int b = '¡'; b <= '¬'; b++) bs.add(b);
        for (int b = '®'; b <= 'ÿ'; b++) bs.add(b);

        List<Integer> cs = new ArrayList<>(bs);
        int           n  = 0;
        for (int b = 0; b < 256; b++) {
            if (!bs.contains(b)) {
                bs.add(b);
                cs.add(256 + n);
                n++;
            }
        }
        char[] encoder = new char[256];
        for (int i = 0; i < bs.size(); i++) {
            encoder[bs.get(i)] = (char) (int) cs.get(i);
        }
        return encoder;
    }

    private String byteToUnicode(int byteVal) {
        return String.valueOf(BYTE_ENCODER[byteVal]);
    }

    // ── Resource loading ──────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void loadVocab() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream in = ClipTokenizer.class.getResourceAsStream(VOCAB_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(
                        "CLIP vocab not found: " + VOCAB_RESOURCE + ". " +
                                "Download from https://github.com/openai/CLIP and place in src/main/resources/clip-tokenizer/");
            }
            encoder = mapper.readValue(in, new TypeReference<Map<String, Integer>>() {});
        }
        decoder = new HashMap<>();
        encoder.forEach((k, v) -> decoder.put(v, k));
    }

    private void loadMerges() throws IOException {
        try (InputStream in = ClipTokenizer.class.getResourceAsStream(MERGES_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(
                        "CLIP merges not found: " + MERGES_RESOURCE + ". " +
                                "Download from https://github.com/openai/CLIP and place in src/main/resources/clip-tokenizer/");
            }
            String   content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            String[] lines   = content.split("\n");
            bpeRanks = new HashMap<>();
            // First line is a comment (#version: ...)
            int rank = 0;
            for (String line : lines) {
                if (line.startsWith("#") || line.isBlank()) {
                    continue;
                }
                bpeRanks.put(line.trim(), rank++);
            }
        }
    }
}
