package com.github.curiousoddman.curious_images.domain.dedupe;

import com.github.curiousoddman.curious_images.dbobj.tables.records.PhotoRecord;
import com.github.curiousoddman.curious_images.persistence.DuplicateGroupRepository;
import com.github.curiousoddman.curious_images.persistence.PhotoRepository;
import com.github.curiousoddman.curious_images.persistence.ThumbnailRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

import java.awt.Desktop;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves a duplicate group from the Duplicates tab: for each photo being dropped, moves its
 * file to the OS recycle bin via {@link Desktop#moveToTrash(File)} and — only if that succeeds —
 * deletes its {@code DUPLICATE_GROUP_MEMBER}, {@code THUMBNAIL}, and {@code PHOTO} rows in one
 * transaction. A file that can't be trashed (locked, already missing, unsupported platform) is
 * reported back as a failure and its DB rows are left untouched, so the DB never points at a
 * file that's been silently lost track of.
 * <p>
 * Once at least one photo from a group is removed, the group is re-checked: if fewer than 2
 * members remain it's no longer a meaningful duplicate set, so the group itself is deleted too.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DuplicateResolutionService {
    private final DSLContext               dsl;
    private final PhotoRepository          photoRepository;
    private final ThumbnailRepository      thumbnailRepository;
    private final DuplicateGroupRepository duplicateGroupRepository;

    /**
     * @param groupId      the group these photos belong to
     * @param photosToDrop the photos to trash + delete — passed in directly (already loaded by
     *                     the controller from {@link com.github.curiousoddman.curious_images.model.DuplicateGroupView}) to avoid a redundant fetch
     */
    public Result resolve(long groupId, List<PhotoRecord> photosToDrop) {
        List<Long>    deletedPhotoIds = new ArrayList<>();
        List<Failure> failures        = new ArrayList<>();

        boolean trashSupported = Desktop.isDesktopSupported()
                && Desktop.getDesktop()
                          .isSupported(Desktop.Action.MOVE_TO_TRASH);
        if (!trashSupported) {
            for (PhotoRecord photo : photosToDrop) {
                failures.add(new Failure(photo, "Recycle bin is not supported on this system"));
            }
            return new Result(deletedPhotoIds, failures);
        }
        Desktop desktop = Desktop.getDesktop();

        for (PhotoRecord photo : photosToDrop) {
            File    file = new File(photo.getAbsolutePath());
            boolean trashed;
            try {
                // A file that's already gone (e.g. removed outside the app) is treated as
                // already-trashed rather than a failure, so the stale DB rows still get cleaned up.
                trashed = !file.isFile() || desktop.moveToTrash(file);
            } catch (Exception e) {
                log.warn("Failed to move {} to the recycle bin", photo.getAbsolutePath(), e);
                failures.add(new Failure(photo, e.getMessage() == null ? e.toString() : e.getMessage()));
                continue;
            }
            if (!trashed) {
                failures.add(new Failure(photo, "The OS declined to move the file to the recycle bin"));
                continue;
            }

            dsl.transaction(cfg -> {
                DSLContext ctx = cfg.dsl();
                duplicateGroupRepository.deleteMember(ctx, groupId, photo.getId());
                thumbnailRepository.deleteByPhotoId(ctx, photo.getId());
                photoRepository.deleteById(ctx, photo.getId());
            });
            deletedPhotoIds.add(photo.getId());
        }

        if (!deletedPhotoIds.isEmpty()) {
            dsl.transaction(cfg -> {
                DSLContext ctx = cfg.dsl();
                if (duplicateGroupRepository.countMembers(ctx, groupId) < 2) {
                    duplicateGroupRepository.deleteGroupCascade(ctx, groupId);
                }
            });
        }

        return new Result(deletedPhotoIds, failures);
    }

    public record Result(List<Long> deletedPhotoIds, List<Failure> failures) {
    }

    public record Failure(PhotoRecord photo, String reason) {
    }
}
