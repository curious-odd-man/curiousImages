package com.github.curiousoddman.curious_images.domain.dedupe;

import com.github.curiousoddman.curious_images.model.DuplicateGroup;
import com.github.curiousoddman.curious_images.model.FolderDuplicatePair;
import com.github.curiousoddman.curious_images.persistence.DuplicateGroupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds the folder-level Duplicates view on top of the groups already loaded for the file-level
 * one — no new query, no schema change. {@link DuplicateGroupRepository#findAllGroupsWithMembers()}
 * already excludes accepted/resolved groups, so this only ever sees live, actionable groups.
 * <p>
 * A group qualifies for a folder pair only if its members span <b>exactly two</b> distinct
 * {@code folder_id}s:
 * <ul>
 *   <li>Groups confined to a single folder (two copies of the same image saved side-by-side in
 *       the same directory) have nothing meaningful to say about a folder-vs-folder decision —
 *       excluded.</li>
 *   <li>Groups spanning three or more folders don't have an unambiguous "keep A / drop B" answer
 *       at the pair level — excluded. They remain fully resolvable from the file-level view.</li>
 * </ul>
 * This mirrors the product decision to keep folder-level resolution strictly "by folder identity
 * only" — no partial/majority logic, no silent narrowing of multi-folder groups into a pair.
 */
@Service
@RequiredArgsConstructor
public class FolderDuplicateGroupingService {

    private final DuplicateGroupRepository duplicateGroupRepository;

    /**
     * @return every qualifying folder pair, sorted by {@link FolderDuplicatePair#groupCount()}
     *         descending — pairs with the most duplicate groups (the biggest cleanup win) first.
     */
    public List<FolderDuplicatePair> buildFolderPairs() {
        return buildFolderPairs(duplicateGroupRepository.findAllGroupsWithMembers());
    }

    /**
     * Package-visible overload taking the group list directly, so this can be unit tested without
     * a database.
     */
    List<FolderDuplicatePair> buildFolderPairs(List<DuplicateGroup> allGroups) {
        Map<PairKey, List<DuplicateGroup>> byPair = new LinkedHashMap<>();

        for (DuplicateGroup group : allGroups) {
            Set<Long> folderIds = group.photos()
                                        .stream()
                                        .map(pwt -> pwt.photo().getFolderId())
                                        .collect(Collectors.toSet());
            if (folderIds.size() != 2) {
                continue;
            }
            var it = folderIds.iterator();
            PairKey key = PairKey.of(it.next(), it.next());
            byPair.computeIfAbsent(key, k -> new ArrayList<>())
                  .add(group);
        }

        List<FolderDuplicatePair> result = new ArrayList<>();
        for (Map.Entry<PairKey, List<DuplicateGroup>> entry : byPair.entrySet()) {
            PairKey               key    = entry.getKey();
            List<DuplicateGroup>  groups = entry.getValue();
            result.add(new FolderDuplicatePair(
                    key.lowId(), pathOf(groups, key.lowId()),
                    key.highId(), pathOf(groups, key.highId()),
                    groups
            ));
        }

        result.sort(Comparator.comparingInt(FolderDuplicatePair::groupCount)
                               .reversed());
        return result;
    }

    /**
     * Derives a display path for a folder from any member photo's absolute path, rather than a
     * FOLDER-table lookup — folder_id already uniquely determines the directory, so this is exact,
     * not an approximation.
     */
    private static String pathOf(List<DuplicateGroup> groups, long folderId) {
        return groups.stream()
                      .flatMap(g -> g.photos().stream())
                      .filter(pwt -> folderId == pwt.photo().getFolderId())
                      .findFirst()
                      .map(pwt -> {
                          File parent = new File(pwt.photo().getAbsolutePath()).getParentFile();
                          return parent == null ? pwt.photo().getAbsolutePath() : parent.getAbsolutePath();
                      })
                      .orElse("(unknown folder)");
    }

    private record PairKey(long lowId, long highId) {
        static PairKey of(long a, long b) {
            return a <= b ? new PairKey(a, b) : new PairKey(b, a);
        }
    }
}
