package com.github.curiousoddman.curious_images.ui.controller.screen;

import com.github.curiousoddman.curious_images.dbobj.tables.records.FaceRecord;
import com.github.curiousoddman.curious_images.model.LoadedFxml;
import com.github.curiousoddman.curious_images.ui.FxmlLoader;
import com.github.curiousoddman.curious_images.ui.FxmlView;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.layout.FlowPane;
import javafx.stage.Stage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.util.List;
import java.util.ResourceBundle;

/**
 * Controller for {@code face_picker.fxml} — a modal grid of every face tagged for a person, used
 * so the user can jump straight to a new cover face instead of stepping through them one at a
 * time with prev/next arrows. Opened from {@code PersonDetailController#onBrowseFaces}.
 * <p>
 * Usage:
 * <pre>
 *     LoadedFxml&lt;FacePickerController&gt; loaded = fxmlLoader.load(FxmlView.FACE_PICKER, null);
 *     FacePickerController controller = loaded.controller();
 *     controller.init(allFaces, currentPerson.getCoverFaceId());
 *
 *     Stage stage = new Stage();
 *     stage.initModality(Modality.APPLICATION_MODAL);
 *     stage.initOwner(ownerWindow);
 *     stage.setScene(new Scene(loaded.parent()));
 *     controller.setStage(stage);
 *     stage.showAndWait();
 *
 *     FaceRecord chosen = controller.getSelectedFace(); // null if cancelled
 * </pre>
 */
@Lazy
@Slf4j
@Component
@RequiredArgsConstructor
public class FacePickerController implements Initializable {

    private final FxmlLoader fxmlLoader;

    @FXML
    public FlowPane faceFlowPane;

    private Stage      stage;
    private FaceRecord selectedFace;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        // Grid contents depend on which person is being edited, so they're populated in init()
        // rather than here.
    }

    /**
     * Must be called by the owner ({@code stage.setScene(...)} etc. happens outside this
     * controller) so {@link #onCancel} and a face click can close the dialog.
     */
    public void setStage(Stage stage) {
        this.stage = stage;
    }

    /**
     * The face the user clicked, or {@code null} if they cancelled/closed the dialog without
     * choosing one. Only meaningful after {@code stage.showAndWait()} has returned.
     */
    public FaceRecord getSelectedFace() {
        return selectedFace;
    }

    /**
     * Populates the grid with one cell per face, badging whichever face's id matches
     * {@code currentCoverFaceId} as the current cover.
     */
    public void init(List<FaceRecord> faces, Long currentCoverFaceId) {
        faceFlowPane.getChildren()
                    .clear();
        for (FaceRecord face : faces) {
            LoadedFxml<FacePickerCellController> loaded  = fxmlLoader.load(FxmlView.FACE_PICKER_CELL, null);
            FacePickerCellController             cell    = loaded.controller();
            boolean                              isCover = currentCoverFaceId != null && currentCoverFaceId.equals(face.getId());
            cell.bind(face, isCover, this::onFaceChosen);
            faceFlowPane.getChildren()
                        .add(loaded.parent());
        }
    }

    private void onFaceChosen(FaceRecord face) {
        selectedFace = face;
        close();
    }

    @FXML
    public void onCancel() {
        selectedFace = null;
        close();
    }

    private void close() {
        if (stage != null) {
            stage.close();
        }
    }
}
