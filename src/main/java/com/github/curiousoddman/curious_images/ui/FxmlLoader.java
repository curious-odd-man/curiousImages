package com.github.curiousoddman.curious_images.ui;

import com.github.curiousoddman.curious_images.model.LoadedFxml;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.Pane;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ResourceBundle;
import java.util.function.Consumer;

@Slf4j
@Component
@RequiredArgsConstructor
public class FxmlLoader {
    private final ApplicationContext context;

    @SneakyThrows
    public <T> LoadedFxml<T> load(FxmlView<T> fxmlPath, ResourceBundle resourceBundle) {
        log.info("Loading {} ...", fxmlPath.fxmlPath());
        FXMLLoader loader = new FXMLLoader();
        loader.setControllerFactory(context::getBean);
        InputStream fxmlStream = getClass().getResourceAsStream(fxmlPath.fxmlPath());
        if (fxmlStream == null) {
            throw new IllegalStateException("Cannot find FXML: " + fxmlPath.fxmlPath());
        }
        loader.setResources(resourceBundle);
        loader.setClassLoader(context.getClassLoader());
        Parent parent = loader.load(fxmlStream);
        return new LoadedFxml<>(
                parent,
                loader.getController()
        );
    }

    public <T, P extends Pane> P loadFxmlAndAttachToParent(P parent, FxmlView<T> view, Consumer<T> controllerConsumer) {
        LoadedFxml<T> loadedFolderDuplicates = load(view, null);
        controllerConsumer.accept(loadedFolderDuplicates.controller());
        Parent viewPane = loadedFolderDuplicates.parent();
        AnchorPane.setTopAnchor(viewPane, 0.0);
        AnchorPane.setBottomAnchor(viewPane, 0.0);
        AnchorPane.setLeftAnchor(viewPane, 0.0);
        AnchorPane.setRightAnchor(viewPane, 0.0);
        parent.getChildren()
              .setAll(viewPane);
        return parent;
    }
}
