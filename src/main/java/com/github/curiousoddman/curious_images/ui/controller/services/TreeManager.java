package com.github.curiousoddman.curious_images.ui.controller.services;

import com.github.curiousoddman.curious_images.dbobj.tables.records.AlbumRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.FolderRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.ImportRootRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.PersonRecord;
import com.github.curiousoddman.curious_images.event.model.AiPipelineCompleteEvent;
import com.github.curiousoddman.curious_images.event.model.LibraryUpdatedEvent;
import com.github.curiousoddman.curious_images.event.model.TreeViewUpdateEvent;
import com.github.curiousoddman.curious_images.event.payload.TreeViewUpdatePayload;
import com.github.curiousoddman.curious_images.model.TimelineData;
import com.github.curiousoddman.curious_images.persistence.AlbumRepository;
import com.github.curiousoddman.curious_images.persistence.FolderRepository;
import com.github.curiousoddman.curious_images.persistence.ImportRootRepository;
import com.github.curiousoddman.curious_images.persistence.MediaRepository;
import com.github.curiousoddman.curious_images.persistence.PersonRepository;
import com.github.curiousoddman.curious_images.ui.nodes.LibraryTreeNode;
import com.github.curiousoddman.curious_images.ui.nodes.NodePayload;
import javafx.collections.ObservableList;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Month;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static com.github.curiousoddman.curious_images.util.async.ThreadUtils.runOnDaemonThread;
import static com.sun.javafx.util.Utils.runOnFxThread;

@Slf4j
@Component
@RequiredArgsConstructor
public class TreeManager {
    private static final List<AlbumTypeGroup> ALBUM_TYPE_GROUPS = List.of(
            new AlbumTypeGroup("EVENT", "Event", LibraryTreeNode.NodeType.ALBUM_EVENT_ROOT, LibraryTreeNode.NodeType.ALBUM_EVENT),
            new AlbumTypeGroup("LOCATION", "Location", LibraryTreeNode.NodeType.ALBUM_LOCATION_ROOT, LibraryTreeNode.NodeType.ALBUM_LOCATION),
            new AlbumTypeGroup("SIMILARITY", "Similarity", LibraryTreeNode.NodeType.ALBUM_SIMILARITY_ROOT, LibraryTreeNode.NodeType.ALBUM_SIMILARITY));

    private final ImportRootRepository importRootRepository;
    private final FolderRepository folderRepository;
    private final MediaRepository  mediaRepository;
    private final AlbumRepository  albumRepository;
    private final PersonRepository     personRepository;

    private TreeView<LibraryTreeNode> libraryTreeView;

    public void initialize(TreeView<LibraryTreeNode> libraryTreeView) {
        this.libraryTreeView = libraryTreeView;
    }

    public List<TreeItem<LibraryTreeNode>> buildImportRootItems() {
        List<TreeItem<LibraryTreeNode>> rootItems = new ArrayList<>();
        for (ImportRootRecord importRoot : importRootRepository.findAll()) {
            FolderRecord rootFolder = folderRepository.findRootFolder(importRoot.getId())
                                                      .orElse(null);
            Long        rootFolderId = rootFolder == null ? null : rootFolder.getId();
            NodePayload payload      = rootFolderId == null ? null : new NodePayload.FolderPayload(rootFolderId);
            TreeItem<LibraryTreeNode> folderRootItem = new TreeItem<>(
                    new LibraryTreeNode(importRoot.getPath(), payload, LibraryTreeNode.NodeType.IMPORT_ROOT));
            if (rootFolderId != null) {
                folderRootItem.getChildren()
                              .addAll(buildFolderItems(rootFolderId));
            }
            rootItems.add(folderRootItem);
        }
        return rootItems;
    }

    public List<TreeItem<LibraryTreeNode>> buildTimelineItems() {
        TimelineData data = mediaRepository.findTimelineData();

        Map<Integer, Map<Integer, List<TimelineData.TimelineDay>>> byYearMonth = new LinkedHashMap<>();
        for (TimelineData.TimelineDay day : data.days()) {
            byYearMonth.computeIfAbsent(day.year(), y -> new LinkedHashMap<>())
                       .computeIfAbsent(day.month(), m -> new ArrayList<>())
                       .add(day);
        }

        List<TreeItem<LibraryTreeNode>> items = new ArrayList<>();

        for (var yearEntry : byYearMonth.entrySet()) {
            int year = yearEntry.getKey();
            int yearCount = yearEntry.getValue()
                                     .values()
                                     .stream()
                                     .flatMap(List::stream)
                                     .mapToInt(TimelineData.TimelineDay::count)
                                     .sum();

            TreeItem<LibraryTreeNode> yearItem = new TreeItem<>(new LibraryTreeNode(
                    year + " (" + yearCount + ")",
                    new NodePayload.TimelinePayload(year, null, null),
                    LibraryTreeNode.NodeType.TIMELINE_YEAR));

            for (var monthEntry : yearEntry.getValue()
                                           .entrySet()) {
                int month = monthEntry.getKey();
                int monthCount = monthEntry.getValue()
                                           .stream()
                                           .mapToInt(TimelineData.TimelineDay::count)
                                           .sum();
                String monthName = Month.of(month)
                                        .getDisplayName(TextStyle.FULL, Locale.getDefault());

                TreeItem<LibraryTreeNode> monthItem = new TreeItem<>(new LibraryTreeNode(
                        monthName + " (" + monthCount + ")",
                        new NodePayload.TimelinePayload(year, month, null),
                        LibraryTreeNode.NodeType.TIMELINE_MONTH));

                for (TimelineData.TimelineDay day : monthEntry.getValue()) {
                    monthItem.getChildren()
                             .add(new TreeItem<>(new LibraryTreeNode(
                                     day.day() + " (" + day.count() + ")",
                                     new NodePayload.TimelinePayload(year, month, day.day()),
                                     LibraryTreeNode.NodeType.TIMELINE_DAY)));
                }
                yearItem.getChildren()
                        .add(monthItem);
            }
            items.add(yearItem);
        }

        if (data.undatedCount() > 0) {
            items.add(new TreeItem<>(new LibraryTreeNode(
                    "Undated (" + data.undatedCount() + ")",
                    new NodePayload.UndatedPayload(),
                    LibraryTreeNode.NodeType.TIMELINE_UNDATED)));
        }
        return items;
    }

    public List<TreeItem<LibraryTreeNode>> buildAlbumItems() {
        Map<String, List<AlbumRecord>> byType = new LinkedHashMap<>();
        for (AlbumRecord album : albumRepository.findAll()) {
            byType.computeIfAbsent(album.getType(), k -> new ArrayList<>())
                  .add(album);
        }

        List<TreeItem<LibraryTreeNode>> items = new ArrayList<>();
        for (AlbumTypeGroup group : ALBUM_TYPE_GROUPS) {
            List<AlbumRecord> albums = byType.getOrDefault(group.albumType(), List.of());

            TreeItem<LibraryTreeNode> groupRoot = new TreeItem<>(
                    new LibraryTreeNode(group.label(), null, group.rootType()));
            List<TreeItem<LibraryTreeNode>> children = new ArrayList<>();
            for (AlbumRecord album : albums) {
                children.add(new TreeItem<>(new LibraryTreeNode(
                        album.getName(), new NodePayload.AlbumPayload(album.getId()), group.leafType())));
            }
            groupRoot.getChildren()
                     .setAll(children);
            groupRoot.setExpanded(false);

            items.add(groupRoot);
        }
        return items;
    }

    public List<TreeItem<LibraryTreeNode>> buildPersonItems() {
        List<TreeItem<LibraryTreeNode>> items = new ArrayList<>();
        for (PersonRecord person : personRepository.findAll()) {
            String label = person.getName() != null ? person.getName() : "Person #" + person.getId();
            items.add(new TreeItem<>(new LibraryTreeNode(
                    label, new NodePayload.PersonPayload(person.getId()), LibraryTreeNode.NodeType.PERSON)));
        }
        return items;
    }

    @SneakyThrows
    @EventListener
    public void onLibraryDataUpdated(LibraryUpdatedEvent event) {
        log.info("Rebuilding library tree");
        runOnDaemonThread("UpdateLibraryData", () -> {
            List<TreeItem<LibraryTreeNode>> folderItems   = buildImportRootItems();
            List<TreeItem<LibraryTreeNode>> timelineItems = buildTimelineItems();
            List<TreeItem<LibraryTreeNode>> albumItems    = buildAlbumItems();
            List<TreeItem<LibraryTreeNode>> personItems   = buildPersonItems();

            runOnFxThread(() -> {
                TreeItem<LibraryTreeNode> foldersRoot = new TreeItem<>(
                        new LibraryTreeNode("Folders", null, LibraryTreeNode.NodeType.FOLDERS_ROOT));
                foldersRoot.getChildren()
                           .setAll(folderItems);
                foldersRoot.setExpanded(true);

                TreeItem<LibraryTreeNode> timelineRoot = new TreeItem<>(
                        new LibraryTreeNode("Timeline", null, LibraryTreeNode.NodeType.TIMELINE_ROOT));
                timelineRoot.getChildren()
                            .setAll(timelineItems);
                timelineRoot.setExpanded(false);

                TreeItem<LibraryTreeNode> albumsRoot = new TreeItem<>(
                        new LibraryTreeNode("Albums", null, LibraryTreeNode.NodeType.ALBUMS_ROOT));
                albumsRoot.getChildren()
                          .setAll(albumItems);
                albumsRoot.setExpanded(false);

                TreeItem<LibraryTreeNode> personsRoot = new TreeItem<>(
                        new LibraryTreeNode("People", null, LibraryTreeNode.NodeType.PERSONS_ROOT));
                personsRoot.getChildren()
                           .setAll(personItems);
                personsRoot.setExpanded(false);

                // Pure grouping node (like albumsRoot above) with two selectable children: the
                // original per-media resolution view, and the new per-folder-pair one. Selecting
                // either child shows its duplicates-review view (see onTreeSelectionChanged).
                TreeItem<LibraryTreeNode> duplicatesFileItem = new TreeItem<>(
                        new LibraryTreeNode("Files", null, LibraryTreeNode.NodeType.DUPLICATES_FILE_ROOT));
                TreeItem<LibraryTreeNode> duplicatesFolderItem = new TreeItem<>(
                        new LibraryTreeNode("Folders", null, LibraryTreeNode.NodeType.DUPLICATES_FOLDER_ROOT));
                TreeItem<LibraryTreeNode> duplicatesRoot = new TreeItem<>(
                        new LibraryTreeNode("Duplicates", null, LibraryTreeNode.NodeType.DUPLICATES_ROOT));
                duplicatesRoot.getChildren()
                              .setAll(duplicatesFileItem, duplicatesFolderItem);
                duplicatesRoot.setExpanded(false);

                TreeItem<LibraryTreeNode> invisibleRoot = new TreeItem<>();
                invisibleRoot.getChildren()
                             .setAll(foldersRoot, timelineRoot, albumsRoot, personsRoot, duplicatesRoot);
                libraryTreeView.setRoot(invisibleRoot);
            });
        });
    }

    /**
     * Refreshes the Albums and People sections of the tree when the AI pipeline completes.
     * Cheaper than a full rebuild: leaves the Folders and Timeline sections untouched.
     */
    @EventListener
    public void onAiPipelineComplete(AiPipelineCompleteEvent event) {
        log.info("AI pipeline complete — refreshing Albums and People tree sections");
        runOnDaemonThread("Refresh Albums", () -> {
            List<TreeItem<LibraryTreeNode>> albumItems  = buildAlbumItems();
            List<TreeItem<LibraryTreeNode>> personItems = buildPersonItems();
            runOnFxThread(() -> {
                TreeItem<LibraryTreeNode> root = libraryTreeView.getRoot();
                if (root == null) {
                    return;
                }
                ObservableList<TreeItem<LibraryTreeNode>> children = root.getChildren();
                if (children.size() < 4) {
                    return;
                }
                children.get(2)
                        .getChildren()
                        .setAll(albumItems);
                children.get(3)
                        .getChildren()
                        .setAll(personItems);
            });
        });
    }

    @EventListener
    public void onTreeUpdateEvent(TreeViewUpdateEvent event) {
        TreeItem<LibraryTreeNode> root = libraryTreeView.getRoot();
        if (root == null) {
            return;
        }
        for (TreeItem<LibraryTreeNode> section : root.getChildren()) {
            if (section.getValue() == null || section.getValue()
                                                     .type() != LibraryTreeNode.NodeType.PERSONS_ROOT) {
                continue;
            }
            switch (event.getPayload()) {
                case TreeViewUpdatePayload.PersonRename(long personId, String newName) ->
                        findPersonNode(section, personId)
                                .ifPresent(personItem -> runOnFxThread(() -> {
                                    LibraryTreeNode value = personItem.getValue();
                                    personItem.setValue(new LibraryTreeNode(newName, value.payload(), value.type()));
                                }));
                case TreeViewUpdatePayload.PersonDelete(long personId) ->
                        findPersonNode(section, personId).ifPresent(personItem ->
                                runOnFxThread(() -> section.getChildren()
                                                           .remove(personItem)));
                default -> throw new UnsupportedOperationException();
            }
        }
    }

    private Optional<TreeItem<LibraryTreeNode>> findPersonNode(TreeItem<LibraryTreeNode> section, long lookupId) {
        for (TreeItem<LibraryTreeNode> personItem : section.getChildren()) {
            LibraryTreeNode node = personItem.getValue();
            if (node != null && node.payload() instanceof NodePayload.PersonPayload(long personId)
                    && personId == lookupId) {
                return Optional.of(personItem);
            }
        }
        return Optional.empty();
    }

    private List<TreeItem<LibraryTreeNode>> buildFolderItems(long parentFolderId) {
        List<TreeItem<LibraryTreeNode>> items = new ArrayList<>();
        for (FolderRecord folder : folderRepository.findChildren(parentFolderId)) {
            TreeItem<LibraryTreeNode> item = new TreeItem<>(
                    new LibraryTreeNode(folder.getName(),
                            new NodePayload.FolderPayload(folder.getId()),
                            LibraryTreeNode.NodeType.FOLDER));
            item.getChildren()
                .addAll(buildFolderItems(folder.getId()));
            items.add(item);
        }
        return items;
    }

    private record AlbumTypeGroup(String albumType, String label, LibraryTreeNode.NodeType rootType,
                                  LibraryTreeNode.NodeType leafType) {}
}
