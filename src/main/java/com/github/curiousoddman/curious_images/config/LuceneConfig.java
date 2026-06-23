package com.github.curiousoddman.curious_images.config;

import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.MMapDirectory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Manages Lucene {@link IndexWriter} and {@link SearcherManager} beans for the CLIP and face
 * vector indexes. Both indexes are stored under {@code ~/.cimages/index/} using
 * {@link MMapDirectory} so the OS page cache handles the working set automatically.
 * <p>
 * Both writers commit on close ({@code setCommitOnClose(true)} is the
 * {@link IndexWriterConfig} default); Spring's {@code destroyMethod = "close"} ensures an
 * orderly flush on shutdown.
 */
@Configuration
public class LuceneConfig {

    private static final Path INDEX_ROOT =
            Path.of(System.getProperty("user.home"), ".cimages", "index");

    // ── CLIP index ────────────────────────────────────────────────────────────

    @Bean(destroyMethod = "close")
    public IndexWriter clipIndexWriter() throws IOException {
        Directory dir = MMapDirectory.open(INDEX_ROOT.resolve("clip"));
        IndexWriterConfig cfg = new IndexWriterConfig()
                .setRAMBufferSizeMB(64)
                .setCommitOnClose(true);
        return new IndexWriter(dir, cfg);
    }

    @Bean(destroyMethod = "close")
    public SearcherManager clipSearcherManager(IndexWriter clipIndexWriter) throws IOException {
        return new SearcherManager(clipIndexWriter, null);
    }

    // ── Face index ────────────────────────────────────────────────────────────

    @Bean(destroyMethod = "close")
    public IndexWriter faceIndexWriter() throws IOException {
        Directory dir = MMapDirectory.open(INDEX_ROOT.resolve("face"));
        IndexWriterConfig cfg = new IndexWriterConfig()
                .setRAMBufferSizeMB(32)
                .setCommitOnClose(true);
        return new IndexWriter(dir, cfg);
    }

    @Bean(destroyMethod = "close")
    public SearcherManager faceSearcherManager(IndexWriter faceIndexWriter) throws IOException {
        return new SearcherManager(faceIndexWriter, null);
    }
}
