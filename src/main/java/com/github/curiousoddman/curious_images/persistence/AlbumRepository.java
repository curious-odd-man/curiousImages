package com.github.curiousoddman.curious_images.persistence;

import com.github.curiousoddman.curious_images.dbobj.tables.records.AlbumRecord;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static com.github.curiousoddman.curious_images.dbobj.Tables.ALBUM;

/**
 * Hand-written jOOQ repository for {@code album}.
 * <p>
 * {@link #insert} executes immediately and returns the new ID — the album ID is needed
 * before {@code album_photo} rows can be queued. {@link #deleteByType} also executes
 * immediately inside the caller-provided {@link DSLContext} (which is bound to an active
 * transaction in {@code AlbumGenerationService}).
 */
@Repository
@RequiredArgsConstructor
public class AlbumRepository {

    private final DSLContext dsl;

    public long insert(String name, String type, Long coverPhotoId,
                       String metaJson, LocalDateTime now) {
        return dsl.insertInto(ALBUM)
                  .set(ALBUM.NAME, name)
                  .set(ALBUM.TYPE, type)
                  .set(ALBUM.COVER_MEDIA_ID, coverPhotoId)
                  .set(ALBUM.META_JSON, metaJson)
                  .set(ALBUM.CREATED_AT, now)
                  .returning(ALBUM.ID)
                  .fetchOne()
                  .getId();
    }

    /**
     * Deletes all auto-generated albums of the given type before rebuilding.
     * Must be called inside the caller's transaction (pass the transaction-bound context).
     */
    public void deleteByType(DSLContext ctx, String type) {
        ctx.deleteFrom(ALBUM)
           .where(ALBUM.TYPE.eq(type))
           .execute();
    }

    public List<AlbumRecord> findAll() {
        return dsl.selectFrom(ALBUM)
                  .orderBy(ALBUM.TYPE, ALBUM.NAME)
                  .fetch();
    }

    public Optional<AlbumRecord> findById(long id) {
        return Optional.ofNullable(
                dsl.selectFrom(ALBUM)
                   .where(ALBUM.ID.eq(id))
                   .fetchOne());
    }

    public List<AlbumRecord> findByType(String type) {
        return dsl.selectFrom(ALBUM)
                  .where(ALBUM.TYPE.eq(type))
                  .orderBy(ALBUM.NAME)
                  .fetch();
    }
}
