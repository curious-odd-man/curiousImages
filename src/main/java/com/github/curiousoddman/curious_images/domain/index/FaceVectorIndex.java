package com.github.curiousoddman.curious_images.domain.index;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.List;

import static org.apache.lucene.index.VectorSimilarityFunction.DOT_PRODUCT;

/**
 * Lucene HNSW KNN vector index for 512-dim ArcFace face embeddings.
 * <p>
 * Each document stores three fields:
 * <ul>
 *   <li>{@code face_id}   — stored string, the face row PK</li>
 *   <li>{@code person_id} — stored string, {@code "-1"} if unassigned</li>
 *   <li>{@code face_vec}  — KNN float vector (512 dims, DOT_PRODUCT similarity)</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FaceVectorIndex {

    private static final int    DIMS              = 512;
    private static final String UNASSIGNED_PERSON = "-1";

    private final IndexWriter     faceIndexWriter;
    private final SearcherManager faceSearcherManager;

    /**
     * Adds or replaces the face embedding document for {@code faceId}.
     *
     * @param faceId    face row PK
     * @param personId  person row PK, or {@code null} if unassigned
     * @param embedding 512-dim L2-normalised embedding
     */
    public void upsert(long faceId, Long personId, float[] embedding) throws IOException {
        Document doc       = new Document();
        String   faceKey   = String.valueOf(faceId);
        String   personKey = personId != null ? String.valueOf(personId) : UNASSIGNED_PERSON;
        doc.add(new StringField("face_id", faceKey, Field.Store.YES));
        doc.add(new StringField("person_id", personKey, Field.Store.YES));
        doc.add(new KnnFloatVectorField("face_vec", embedding, DOT_PRODUCT));
        faceIndexWriter.updateDocument(new Term("face_id", faceKey), doc);
    }

    /**
     * Removes the face embedding document for {@code faceId}, if present. Call {@link #commit()}
     * afterward to make the removal visible to searchers. Used by {@code PhotoRotationService}
     * when a photo's rotation is manually corrected and its faces are deleted outright.
     */
    public void delete(long faceId) throws IOException {
        faceIndexWriter.deleteDocuments(new Term("face_id", String.valueOf(faceId)));
    }

    /**
     * Commits buffered writes and refreshes searchers.
     */
    public void commit() throws IOException {
        faceIndexWriter.commit();
        faceSearcherManager.maybeRefresh();
    }

    /**
     * Returns up to {@code k} face IDs most similar to {@code queryVec}, optionally filtered
     * to a specific person.
     *
     * @param queryVec       L2-normalised query embedding
     * @param k              maximum results
     * @param personIdFilter if non-null, only return faces belonging to this person
     */
    // TODO: Unused
    public List<Long> searchFaces(float[] queryVec, int k, Long personIdFilter) throws IOException {
        IndexSearcher searcher = faceSearcherManager.acquire();
        try {
            Query knnQuery = new KnnFloatVectorQuery("face_vec", queryVec, k * 4);
            Query finalQuery;
            if (personIdFilter != null) {
                Query personFilter = new TermQuery(new Term("person_id", String.valueOf(personIdFilter)));
                finalQuery = new BooleanQuery.Builder()
                        .add(knnQuery, BooleanClause.Occur.MUST)
                        .add(personFilter, BooleanClause.Occur.FILTER)
                        .build();
            } else {
                finalQuery = knnQuery;
            }
            TopDocs hits = searcher.search(finalQuery, k);
            return Arrays.stream(hits.scoreDocs)
                         .map(sd -> {
                             try {
                                 return Long.parseLong(
                                         searcher.storedFields()
                                                 .document(sd.doc)
                                                 .get("face_id"));
                             } catch (IOException e) {
                                 throw new UncheckedIOException(e);
                             }
                         })
                         .toList();
        } finally {
            faceSearcherManager.release(searcher);
        }
    }
}
