package com.github.curiousoddman.curious_images.ui.controller.services;

import com.github.curiousoddman.curious_images.ui.FxmlLoader;
import com.github.curiousoddman.curious_images.ui.FxmlView;
import com.github.curiousoddman.curious_images.ui.controller.screen.DuplicatesController;
import com.github.curiousoddman.curious_images.ui.controller.screen.FolderDuplicatesController;
import com.github.curiousoddman.curious_images.ui.controller.screen.PersonDetailController;
import javafx.scene.Node;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.BorderPane;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
@RequiredArgsConstructor
public class LibraryViewManager {
    private final FxmlLoader fxmlLoader;

    private BorderPane                 photoGridView;
    private AnchorPane                 duplicatesContainer;
    private AnchorPane                 folderDuplicatesContainer;
    private AnchorPane                 personDetailContainer;
    private DuplicatesController       duplicatesController;
    private FolderDuplicatesController folderDuplicatesController;

    private List<Node> nodes = List.of();

    public void initialize(BorderPane photoGridView,
                           AnchorPane duplicatesContainer,
                           AnchorPane folderDuplicatesContainer,
                           AnchorPane personDetailContainer,
                           DuplicatesController duplicatesController,
                           FolderDuplicatesController folderDuplicatesController) {
        this.photoGridView = photoGridView;
        this.duplicatesContainer = duplicatesContainer;
        this.folderDuplicatesContainer = folderDuplicatesContainer;
        this.personDetailContainer = personDetailContainer;
        this.duplicatesController = duplicatesController;
        this.folderDuplicatesController = folderDuplicatesController;
        nodes = List.of(photoGridView, duplicatesContainer, folderDuplicatesContainer, personDetailContainer);
    }

    public PersonDetailController showPersonDetail(long personId, PersonDetailController personDetailController) {
        AtomicReference<PersonDetailController> ctrl = new AtomicReference<>();
        if (personDetailController == null) {
            fxmlLoader.loadFxmlAndAttachToParent(personDetailContainer, FxmlView.PERSON_DETAIL, ctrl::set);
            personDetailController = ctrl.get();
        }
        show(personDetailContainer);
        personDetailController.loadPerson(personId);
        return personDetailController;
    }

    public void showPhotoGrid() {
        show(photoGridView);
    }

    public void showDuplicatesView() {
        show(duplicatesContainer);
        duplicatesController.activateDuplicatesView();
    }

    public void showFolderDuplicatesView() {
        show(folderDuplicatesContainer);
        folderDuplicatesController.activateFolderDuplicatesView();
    }

    private void show(Node node) {
        Node toShow = null;
        for (Node iter : nodes) {
            if (iter == node) {
                toShow = iter;
            } else {
                iter.setVisible(false);
                iter.setManaged(false);
            }
        }

        if (toShow != null) {
            toShow.setVisible(true);
            toShow.setManaged(true);
        } else {
            log.error("Nothing to show !!!! {}", node);
        }
    }
}
