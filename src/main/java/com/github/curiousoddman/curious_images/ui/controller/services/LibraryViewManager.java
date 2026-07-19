package com.github.curiousoddman.curious_images.ui.controller.services;

import com.github.curiousoddman.curious_images.event.model.ThumbnailsReadyEvent;
import com.github.curiousoddman.curious_images.model.UiElement;
import com.github.curiousoddman.curious_images.ui.FxmlLoader;
import com.github.curiousoddman.curious_images.ui.FxmlView;
import com.github.curiousoddman.curious_images.ui.controller.custom.PhotoGridController;
import com.github.curiousoddman.curious_images.ui.controller.screen.DuplicatesController;
import com.github.curiousoddman.curious_images.ui.controller.screen.FolderDuplicatesController;
import com.github.curiousoddman.curious_images.ui.controller.screen.PersonDetailController;
import javafx.scene.Node;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static com.github.curiousoddman.curious_images.ui.util.UiUtils.fxManage;
import static com.github.curiousoddman.curious_images.ui.util.UiUtils.fxUnmanage;

@Slf4j
@Component
@RequiredArgsConstructor
public class LibraryViewManager {
    private final FxmlLoader fxmlLoader;

    private final List<UiElement<?>> uiElements = new ArrayList<>();

    private UiElement<PhotoGridController>        photoGrid;
    private UiElement<DuplicatesController>       duplicates;
    private UiElement<FolderDuplicatesController> folderDuplicates;
    private UiElement<PersonDetailController>     personDetails;

    public void initialize(UiElement<PhotoGridController> photoGridView,
                           UiElement<DuplicatesController> duplicatesContainer,
                           UiElement<FolderDuplicatesController> folderDuplicatesContainer,
                           UiElement<PersonDetailController> personDetailContainer) {
        this.photoGrid = photoGridView;
        this.duplicates = duplicatesContainer;
        this.folderDuplicates = folderDuplicatesContainer;
        this.personDetails = personDetailContainer;

        uiElements.add(photoGridView);
        uiElements.add(personDetailContainer);
        uiElements.add(duplicatesContainer);
        uiElements.add(folderDuplicatesContainer);
    }

    public PersonDetailController showPersonDetail(long personId, UiElement<PersonDetailController> personDetailsElement) {
        PersonDetailController controller = personDetailsElement.controller();
        if (personDetailsElement.controller() == null) {
            controller = fxmlLoader.loadFxmlAndAttachToParent(personDetails.pane(), FxmlView.PERSON_DETAIL)
                                   .controller();

            if (uiElements.get(1)
                          .controller() != null) {
                // This should only be called once when the controller for person details does not exist yet
                throw new IllegalStateException("oops, something went wrong");
            }
            UiElement<PersonDetailController> element = new UiElement<>(personDetailsElement.pane(), controller);
            personDetailsElement = element;
            uiElements.set(1, element);
        }

        show(personDetailsElement);
        controller.loadPerson(personId);
        return controller;
    }

    public void showPhotoGrid() {
        show(photoGrid);
    }

    public void showDuplicatesView() {
        show(duplicates);
        duplicates.controller()
                  .activateDuplicatesView();
    }

    public void showFolderDuplicatesView() {
        show(folderDuplicates);
        folderDuplicates.controller()
                        .activateFolderDuplicatesView();
    }

    @EventListener
    public void onThumbnailReady(ThumbnailsReadyEvent event) {
        for (UiElement<?> iter : uiElements) {
            if (iter.controller() instanceof ThumbnailReadyEventListener listener) {
                if (iter.pane()
                        .isVisible()) {
                    listener.onThumbnailReady(event);
                }
            }
        }
    }

    private void show(UiElement<?> uiElement) {
        Node toShow = null;
        for (UiElement<?> iter : uiElements) {
            Node parent = iter.pane();
            if (parent == uiElement.pane()) {
                toShow = parent;
            } else {
                fxUnmanage(parent);
                parent.setVisible(false);
                parent.setManaged(false);
            }
        }

        if (toShow != null) {
            fxManage(toShow);
        } else {
            log.error("Nothing to show !!!! {}", uiElement);
        }
    }
}
