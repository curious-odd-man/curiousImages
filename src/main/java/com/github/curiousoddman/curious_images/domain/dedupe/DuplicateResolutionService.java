package com.github.curiousoddman.curious_images.domain.dedupe;

import com.github.curiousoddman.curious_images.dbobj.tables.records.MediaPhotoRecord;
import com.github.curiousoddman.curious_images.model.DupResolveStrategy;
import com.github.curiousoddman.curious_images.model.DuplicateGroup;
import com.github.curiousoddman.curious_images.model.FolderDuplicatePair;
import com.github.curiousoddman.curious_images.model.Media;
import com.github.curiousoddman.curious_images.model.MediaWithThumbnail;
import com.github.curiousoddman.curious_images.model.PhotoFailure;
import com.github.curiousoddman.curious_images.persistence.DuplicateGroupRepository;
import com.github.curiousoddman.curious_images.persistence.MediaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Resolves a duplicate group from the Duplicates tab: for each media being dropped, moves its
 * file to the OS recycle bin via {@link Desktop#moveToTrash(File)} and — only if that succeeds —
 * deletes its {@code DUPLICATE_GROUP_MEMBER}, {@code THUMBNAIL}, and {@code PHOTO} rows in one
 * transaction. A file that can't be trashed (locked, already missing, unsupported platform) is
 * reported back as a failure and its DB rows are left untouched, so the DB never points at a
 * file that's been silently lost track of.
 * <p>
 * Once at least one media from a group is removed, the group is re-checked: if fewer than 2
 * members remain it's no longer a meaningful duplicate set, so the group itself is deleted too.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DuplicateResolutionService {
    private final DSLContext               dsl;
    private final MediaRepository          mediaRepository;
    private final DuplicateGroupRepository duplicateGroupRepository;

    /**
     * @param groupId      the group these photos belong to
     * @param photosToDrop the photos to trash + delete — passed in directly (already loaded by
     *                     the controller from {@link DuplicateGroup}) to avoid a redundant fetch
     * @param strategy
     */
    public Result resolve(long groupId, List<MediaPhotoRecord> photosToDrop, DupResolveStrategy strategy) {
        if (strategy == DupResolveStrategy.KEEP_ALL) {
            duplicateGroupRepository.markGroupResolved(groupId);
            return new Result(List.of(), List.of());
        }
        List<Long>         deletedPhotoIds = new ArrayList<>();
        List<PhotoFailure> failures        = new ArrayList<>();

        boolean trashSupported = Desktop.isDesktopSupported()
                && Desktop.getDesktop()
                          .isSupported(Desktop.Action.MOVE_TO_TRASH);
        if (!trashSupported) {
            for (MediaPhotoRecord photo : photosToDrop) {
                failures.add(new PhotoFailure(photo, "Recycle bin is not supported on this system"));
            }
            return new Result(deletedPhotoIds, failures);
        }
        Desktop desktop = Desktop.getDesktop();

        for (MediaPhotoRecord photo : photosToDrop) {
            File    file = new File(photo.getAbsolutePath());
            boolean trashed;
            try {
                // A file that's already gone (e.g. removed outside the app) is treated as
                // already-trashed rather than a failure, so the stale DB rows still get cleaned up.
                trashed = !file.isFile() || desktop.moveToTrash(file);
            } catch (Exception e) {
                log.warn("Failed to move {} to the recycle bin", photo.getAbsolutePath(), e);
                failures.add(new PhotoFailure(photo, e.getMessage() == null ? e.toString() : e.getMessage()));
                continue;
            }
            if (!trashed) {
                failures.add(new PhotoFailure(photo, "The OS declined to move the file to the recycle bin"));
                continue;
            }

            dsl.transaction(cfg -> {
                DSLContext ctx = cfg.dsl();
                duplicateGroupRepository.deleteMember(ctx, groupId, photo.getId());
                mediaRepository.deleteById(ctx, photo.getId());
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

    /**
     * Folder-level resolution: applies the exact same per-media trash + DB-delete logic as
     * {@link #resolve} to every group in a {@link FolderDuplicatePair} in one pass.
     * <p>
     * For {@code KEEP_ALL}, every group in the pair is marked resolved with nothing deleted,
     * matching {@link #resolve}'s KEEP_ALL semantics. For every other strategy, the caller has
     * already turned the folder checkbox state into {@code keptFolderIds} — this method just
     * derives, per group, which of its member photos live outside that set and drops them:
     * <ul>
     *   <li>KEEP_CHECKED → caller passes the checked folder id(s)</li>
     *   <li>REMOVE_CHECKED → caller passes the unchecked folder id(s)</li>
     *   <li>REMOVE_ALL → caller passes an empty set</li>
     * </ul>
     * Because a group here always spans exactly two folders (see
     * {@link FolderDuplicateGroupingService}), this never has to guess which member to keep
     * within a folder — every media in a dropped folder is dropped, every media in a kept folder
     * survives.
     */
    public FolderPairResult resolveFolderPair(FolderDuplicatePair pair,
                                              DupResolveStrategy strategy,
                                              Set<Long> keptFolderIds) {
        List<Long>         deletedPhotoIds = new ArrayList<>();
        List<PhotoFailure> failures        = new ArrayList<>();

        for (DuplicateGroup group : pair.groups()) {
            if (strategy == DupResolveStrategy.KEEP_ALL) {
                duplicateGroupRepository.markGroupResolved(group.groupId());
                continue;
            }
            List<MediaPhotoRecord> toDrop = group.photos()
                                                 .stream()
                                                 .map(MediaWithThumbnail::media)
                                                 .filter(p -> !keptFolderIds.contains(p.getFolderId()))
                                                 .map(Media::photo)
                                                 .toList();
            Result result = resolve(group.groupId(), toDrop, strategy);
            deletedPhotoIds.addAll(result.deletedPhotoIds());
            failures.addAll(result.failures());
        }

        return new FolderPairResult(deletedPhotoIds, failures);
    }

    public record Result(List<Long> deletedPhotoIds, List<PhotoFailure> failures) {
    }

    public record FolderPairResult(List<Long> deletedPhotoIds, List<PhotoFailure> failures) {
    }
}
