package com.github.curiousoddman.curious_images.ui.controller.screen;

import com.github.curiousoddman.curious_images.dbobj.tables.records.PhotoRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.ThumbnailRecord;
import com.github.curiousoddman.curious_images.model.bundle.SlideshowBundle;
import com.github.curiousoddman.curious_images.persistence.ThumbnailRepository;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.File;
import java.net.URL;
import java.util.List;
import java.util.ResourceBundle;

import static com.sun.javafx.util.Utils.runOnFxThread;

/**
 * Controller for the slideshow overlay.
 * <p>
 * Responsibilities:
 * <ul>
 *   <li>Show the thumbnail immediately, then cross-fade to the full-res image once loaded.</li>
 *   <li>Show a warning badge when the source file is not found on disk.</li>
 *   <li>Left/right navigation (mouse buttons + keyboard arrows).</li>
 *   <li>Scroll-to-zoom around the cursor, drag-to-pan.</li>
 *   <li>Auto-hide navigation controls after 2 s of mouse inactivity.</li>
 *   <li>Close on Esc or the ✕ button.</li>
 * </ul>
 * <p>
 * Must be prototype-scoped because each call to {@code FxmlLoader.load()} should
 * produce a fresh controller instance with its own state.
 */
@Lazy
@Slf4j
@Component
@Scope("prototype")
@RequiredArgsConstructor
public class SlideshowController implements Initializable {

    // ── Dependencies ────────────────────────────────────────────────────────
    private final ThumbnailRepository thumbnailRepository;
    @FXML
    public        StackPane           imagesPane;

    // ── FXML nodes ──────────────────────────────────────────────────────────
    @FXML
    private StackPane rootPane;
    @FXML
    private ImageView thumbnailImageView;
    @FXML
    private ImageView fullImageView;
    @FXML
    private Label     fileNotFoundPathLabel;

    @FXML
    private Button prevButton;
    @FXML
    private Button nextButton;
    @FXML
    private Label  photoNameLabel;
    @FXML
    private Label  counterLabel;
    @FXML
    private Label  zoomLabel;
    @FXML
    private Button closeButton;

    // ── Slideshow state ─────────────────────────────────────────────────────
    private List<PhotoRecord> photos;
    private int               currentIndex;

    // ── Zoom / pan state ────────────────────────────────────────────────────
    private static final double ZOOM_MIN  = 1.0;
    private static final double ZOOM_MAX  = 10.0;
    private static final double ZOOM_STEP = 0.12; // fraction per scroll tick

    private double zoomFactor = 1.0;
    private double translateX = 0.0;
    private double translateY = 0.0;
    private double dragStartX;
    private double dragStartY;
    private double dragOriginX;
    private double dragOriginY;


    private final PauseTransition zoomLabelTimer = new PauseTransition(Duration.seconds(2));

    // ── Placeholder for missing thumbnail ───────────────────────────────────
    private Image noImageAvailable;

    // ────────────────────────────────────────────────────────────────────────
    // Initializable
    // ────────────────────────────────────────────────────────────────────────

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        noImageAvailable = new Image(getClass().getResourceAsStream("/img/noimage.png"));

        setupZoomAndPan();
        setupKeyboard();
        setupButtons();
    }

    // ────────────────────────────────────────────────────────────────────────
    // Public API — called by LibraryController immediately after FXML load
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Seeds the slideshow with the photo list and opens the image at {@code startIndex}.
     * Must be called on the FX thread after {@link #initialize} has run.
     */
    public void initSlideshow(SlideshowBundle bundle) {
        this.photos = bundle.getPhotos();
        this.currentIndex = bundle.getStartIndex();
        showPhoto(currentIndex);
        rootPane.requestFocus();
    }

    // ────────────────────────────────────────────────────────────────────────
    // Navigation
    // ────────────────────────────────────────────────────────────────────────

    private void showPhoto(int index) {
        if (photos == null || photos.isEmpty()) {
            return;
        }
        fileNotFoundPathLabel.setVisible(false);

        // Clamp
        index = Math.max(0, Math.min(index, photos.size() - 1));
        currentIndex = index;

        PhotoRecord photo = photos.get(index);

        // Reset zoom/pan for each new photo
        resetZoomPan();

        // Update UI labels
        photoNameLabel.setText(photo.getFilename());
        counterLabel.setText((index + 1) + " / " + photos.size());
        prevButton.setDisable(index == 0);
        nextButton.setDisable(index == photos.size() - 1);

        // 1. Load and show thumbnail immediately
        ThumbnailRecord thumbnailRecord = thumbnailRepository.findByPhotoId(photo.getId())
                                                             .orElse(null);
        Image thumb = loadThumbnailImage(thumbnailRecord);
        thumbnailImageView.setImage(thumb);
        thumbnailImageView.setOpacity(1.0);
        fullImageView.setOpacity(0.0);
        fullImageView.setImage(null);

        // 2. Try to load full-res original in background
        String absolutePath = photo.getAbsolutePath();
        File   sourceFile   = absolutePath != null ? new File(absolutePath) : null;

        if (sourceFile != null && sourceFile.isFile()) {

            // Background-loading: JavaFX decodes off the FX thread
            Image fullImage = new Image(sourceFile.toURI()
                                                  .toString(), 0, 0, true, true, true);
            fullImage.progressProperty()
                     .addListener((obs, oldVal, newVal) -> {
                         if (newVal.doubleValue() >= 1.0 && !fullImage.isError()) {
                             // Only apply if the user hasn't navigated away
                             if (photos.get(currentIndex) == photo) {
                                 fullImageView.setImage(fullImage);
                                 crossFadeToFull();
                                 runOnFxThread(() -> rootPane.requestFocus());
                             }
                         }
                     });
            fullImage.errorProperty()
                     .addListener((obs, was, isError) -> {
                         if (isError) {
                             log.warn("Failed to decode full-res image: {}", absolutePath);
                             // thumbnail stays visible; no warning — file exists but is unreadable
                         }
                     });
        } else {
            // File missing from disk — keep thumbnail, show warning badge
            fileNotFoundPathLabel.setText(absolutePath != null ? absolutePath : "(path unknown)");
            fileNotFoundPathLabel.setVisible(true);
        }
    }

    private void crossFadeToFull() {
        FadeTransition fadeOut = new FadeTransition(Duration.millis(250), thumbnailImageView);
        fadeOut.setToValue(0.0);

        FadeTransition fadeIn = new FadeTransition(Duration.millis(250), fullImageView);
        fadeIn.setToValue(1.0);

        fadeOut.play();
        fadeIn.play();
    }

    private void navigate(int delta) {
        log.info("Navigate {}", delta);
        int next = currentIndex + delta;
        if (next >= 0 && next < photos.size()) {
            showPhoto(next);
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Zoom / pan
    // ────────────────────────────────────────────────────────────────────────

    private void setupZoomAndPan() {
        // Scroll to zoom around cursor
        imagesPane.setOnScroll(e -> {
            log.info("Scroll detected {}", e.getDeltaY());
            if (e.getDeltaY() == 0) {
                return;
            }
            double oldZoom = zoomFactor;
            double delta   = e.getDeltaY() > 0 ? (1 + ZOOM_STEP) : (1 / (1 + ZOOM_STEP));
            double newZoom = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, oldZoom * delta));
            if (newZoom == oldZoom) {
                return;
            }

            double mouseX = e.getX() - imagesPane.getWidth() / 2.0;
            double mouseY = e.getY() - imagesPane.getHeight() / 2.0;

            double scaleRatio = newZoom / oldZoom;

            translateX = scaleRatio * translateX + (1 - scaleRatio) * mouseX;
            translateY = scaleRatio * translateY + (1 - scaleRatio) * mouseY;

            zoomFactor = newZoom;
            applyTransform(true);
            log.info("zoom={} tx={} ty={}", zoomFactor, translateX, translateY);
            e.consume();
        });

        // Drag to pan (only when zoomed in)
        imagesPane.setOnMousePressed(e -> {
            log.info("Mouse pressed {}", e);
            if (e.isPrimaryButtonDown()) {
                dragStartX = e.getSceneX();
                dragStartY = e.getSceneY();
                dragOriginX = translateX;
                dragOriginY = translateY;
            }
        });

        imagesPane.setOnMouseDragged(e -> {
            log.info("Mouse dragged {}", e);
            if (zoomFactor <= 1.0) {
                return; // nothing to pan
            }
            translateX = dragOriginX + (e.getSceneX() - dragStartX);
            translateY = dragOriginY + (e.getSceneY() - dragStartY);
            applyTransform(false);
        });
    }

    private void applyTransform(boolean isZoomChanged) {
        double fitWidth = fullImageView.getLayoutBounds()
                                       .getWidth();
        double tx = (zoomFactor <= 1.0) ? 0 : clampTranslate(translateX, zoomFactor, imagesPane.getWidth(), fitWidth);
        double fitHeight = fullImageView.getLayoutBounds()
                                        .getHeight();
        double ty = (zoomFactor <= 1.0) ? 0 : clampTranslate(translateY, zoomFactor, imagesPane.getHeight(), fitHeight);

        for (ImageView iv : new ImageView[]{thumbnailImageView, fullImageView}) {
            iv.setScaleX(zoomFactor);
            iv.setScaleY(zoomFactor);
            iv.setTranslateX(tx);
            iv.setTranslateY(ty);
        }

        if (isZoomChanged) {
            int pct = (int) Math.round(zoomFactor * 100);
            zoomLabel.setText(pct + "%  ");
            zoomLabel.setVisible(true);

            zoomLabelTimer.stop();
            zoomLabelTimer.setOnFinished(e -> zoomLabel.setVisible(false));
            zoomLabelTimer.playFromStart();
        }
    }

    /**
     * Clamps translation so the image never shows empty space when zoomed in past the pane edges.
     */
    private static double clampTranslate(double translate, double zoom, double paneSize, double imageSize) {
        double scaledSize = imageSize * zoom;
        if (scaledSize <= paneSize) {
            return 0; // image fits inside pane; centre it
        }
        double maxShift = (scaledSize - paneSize) / 2.0;
        return Math.max(-maxShift, Math.min(maxShift, translate));
    }

    private void resetZoomPan() {
        zoomFactor = 1.0;
        translateX = 0.0;
        translateY = 0.0;
        applyTransform(false);
    }

    // ────────────────────────────────────────────────────────────────────────
    // Keyboard
    // ────────────────────────────────────────────────────────────────────────

    private void setupKeyboard() {
        rootPane.sceneProperty()
                .addListener((obs, oldScene, newScene) -> {
                    if (newScene != null) {
                        newScene.setOnKeyPressed(e -> {
                            if (e.getCode() == KeyCode.LEFT || e.getCode() == KeyCode.KP_LEFT) {
                                navigate(-1);
                                e.consume();
                            } else if (e.getCode() == KeyCode.RIGHT || e.getCode() == KeyCode.KP_RIGHT) {
                                navigate(1);
                                e.consume();
                            } else if (e.getCode() == KeyCode.ESCAPE) {
                                closeSlideshow();
                                e.consume();
                            }
                        });
                    }
                });
        rootPane.requestFocus();
    }

    // ────────────────────────────────────────────────────────────────────────
    // Buttons
    // ────────────────────────────────────────────────────────────────────────

    private void setupButtons() {
        prevButton.setOnAction(e -> navigate(-1));
        nextButton.setOnAction(e -> navigate(1));
        closeButton.setOnAction(e -> closeSlideshow());
    }

    private void closeSlideshow() {
        // The slideshow is shown as a modal dialog stage — close its window.
        rootPane.getScene()
                .getWindow()
                .hide();
    }

    // ────────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────────

    private Image loadThumbnailImage(ThumbnailRecord thumbnail) {
        return getImage(thumbnail, noImageAvailable);
    }

    static Image getImage(ThumbnailRecord thumbnail, Image noImageAvailable) {
        if (thumbnail != null && thumbnail.getCachePath() != null) {
            File file = new File(thumbnail.getCachePath());
            if (file.isFile()) {
                return new Image(file.toURI()
                                     .toString(), 0, 0, true, true, true);
            }
        }
        return noImageAvailable;
    }
}
