package com.github.curiousoddman.curious_images.domain.index;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TopDocs;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.List;

import static org.apache.lucene.index.VectorSimilarityFunction.DOT_PRODUCT;

/**
 * Lucene HNSW KNN vector index for 512-dim CLIP image embeddings.
 * <p>
 * Uses {@link org.apache.lucene.index.VectorSimilarityFunction#DOT_PRODUCT} which equals
 * cosine similarity when vectors are L2-normalised (as our CLIP embeddings always are).
 * {@link SearcherManager#maybeRefresh()} is called after each {@link #commit()} so the
 * reader sees freshly committed segments within the same process.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClipVectorIndex {

    private static final int DIMS = 512;

    private final IndexWriter     clipIndexWriter;
    private final SearcherManager clipSearcherManager;

    /**
     * Adds or replaces the CLIP embedding for {@code photoId}. Call {@link #commit()} after
     * each batch to make writes visible to searchers.
     */
    public void upsert(long photoId, float[] embedding) throws IOException {
        Document doc = new Document();
        String   key = String.valueOf(photoId);
        doc.add(new StringField("photo_id", key, Field.Store.YES));
        doc.add(new KnnFloatVectorField("clip_vec", embedding, DOT_PRODUCT));
        clipIndexWriter.updateDocument(new Term("photo_id", key), doc);
    }

    /**
     * Commits buffered writes and refreshes searchers. Call after each batch in the indexing
     * pipeline stage.
     */
    public void commit() throws IOException {
        clipIndexWriter.commit();
        clipSearcherManager.maybeRefresh();
    }

    /**
     * Returns up to {@code k} photo IDs ordered by descending cosine similarity to
     * {@code queryVec}. The query vector must be L2-normalised.
     */
    public List<Long> search(float[] queryVec, int k) throws IOException {
        IndexSearcher searcher = clipSearcherManager.acquire();
        try {
            TopDocs hits = searcher.search(new KnnFloatVectorQuery("clip_vec", queryVec, k), k);
            return Arrays.stream(hits.scoreDocs)
                         .map(sd -> {
                             try {
                                 return Long.parseLong(
                                         searcher.storedFields()
                                                 .document(sd.doc)
                                                 .get("photo_id"));
                             } catch (IOException e) {
                                 throw new UncheckedIOException(e);
                             }
                         })
                         .toList();
        } finally {
            clipSearcherManager.release(searcher);
        }
    }
}
