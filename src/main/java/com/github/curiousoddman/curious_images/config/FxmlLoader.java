package com.github.curiousoddman.curious_images.config;

import com.github.curiousoddman.curious_images.model.LoadedFxml;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ResourceBundle;

@Slf4j
@Component
@RequiredArgsConstructor
public class FxmlLoader {
    private final ApplicationContext context;

    @SneakyThrows
    public <T> LoadedFxml<T> load(FxmlView<T> fxmlPath, ResourceBundle resourceBundle) {
        log.info("Loading {} ...", fxmlPath.getFxmlPath());
        FXMLLoader loader = new FXMLLoader();
        loader.setControllerFactory(context::getBean);
        InputStream fxmlStream = getClass().getResourceAsStream(fxmlPath.getFxmlPath());
        if (fxmlStream == null) {
            throw new IllegalStateException("Cannot find FXML: " + fxmlPath.getFxmlPath());
        }
        loader.setResources(resourceBundle);
        loader.setClassLoader(context.getClassLoader());
        Parent parent = loader.load(fxmlStream);
        return new LoadedFxml<>(
                parent,
                loader.getController()
        );
    }
}
