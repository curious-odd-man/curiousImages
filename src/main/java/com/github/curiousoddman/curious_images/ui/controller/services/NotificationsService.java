package com.github.curiousoddman.curious_images.ui.controller.services;

import com.github.curiousoddman.curious_images.event.model.UserNotificationEvent;
import com.github.curiousoddman.curious_images.event.payload.NotificationLevel;
import com.github.curiousoddman.curious_images.event.payload.UserNotificationPayload;
import com.github.curiousoddman.curious_images.model.LoadedFxml;
import com.github.curiousoddman.curious_images.model.bundle.NotificationMenuItemBundle;
import com.github.curiousoddman.curious_images.ui.FxmlLoader;
import com.github.curiousoddman.curious_images.ui.FxmlView;
import com.github.curiousoddman.curious_images.ui.controller.custom.NotificationMenuItemController;
import com.github.curiousoddman.curious_images.ui.styles.CssClasses;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Menu;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.kordamp.ikonli.javafx.FontIcon;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import static com.sun.javafx.util.Utils.runOnFxThread;
import static org.kordamp.ikonli.bootstrapicons.BootstrapIcons.EXCLAMATION_CIRCLE_FILL;
import static org.kordamp.ikonli.bootstrapicons.BootstrapIcons.EXCLAMATION_TRIANGLE_FILL;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationsService {
    private final List<UserNotificationPayload> payloads = new ArrayList<>();

    private final FxmlLoader fxmlLoader;

    private Menu notificationsMenu;

    public void initialize(Menu notificationsMenu) {
        this.notificationsMenu = notificationsMenu;
    }

    @EventListener
    public void onUserNotificationEvent(UserNotificationEvent event) {
        payloads.add(event.getPayload());

        updateNotification();
    }

    private void updateNotification() {
        runOnFxThread(() -> {
            notificationsMenu.getItems()
                             .clear();
            notificationsMenu.setVisible(!payloads.isEmpty());
            if (!payloads.isEmpty()) {
                NotificationLevel notificationLevel = getNotificationLevel();
                FontIcon          graphic           = getGraphic(notificationLevel);
                graphic.getStyleClass()
                       .add(getCssStyle(notificationLevel));
                notificationsMenu.setGraphic(graphic);
                notificationsMenu.setText(payloads.size() + "");
            }

            for (UserNotificationPayload payload : payloads) {
                LoadedFxml<NotificationMenuItemController> loaded = fxmlLoader.load(
                        FxmlView.NOTIFICATIONS_MENU_ITEM,
                        new NotificationMenuItemBundle(
                                getGraphic(payload.getNotificationLevel()),
                                payload.getTitle(),
                                payload.getDescription(),
                                () -> {
                                    payloads.remove(payload);
                                    updateNotification();
                                }
                        )
                );
                CustomMenuItem customMenuItem = new CustomMenuItem(loaded.parent());
                customMenuItem.setHideOnClick(false);
                notificationsMenu.getItems()
                                 .add(customMenuItem);
            }
        });
    }

    private String getCssStyle(NotificationLevel notificationLevel) {
        return switch (notificationLevel) {
            case WARNING -> CssClasses.WARNING_NOTIFICATION_ICON;
            case ERROR -> CssClasses.ERROR_NOTIFICATION_ICON;
        };
    }

    private NotificationLevel getNotificationLevel() {
        OptionalInt max = payloads.stream()
                                  .map(UserNotificationPayload::getNotificationLevel)
                                  .mapToInt(Enum::ordinal)
                                  .max();
        if (max.isEmpty()) {
            return null;
        }

        return NotificationLevel.values()[max.getAsInt()];
    }

    private FontIcon getGraphic(NotificationLevel notificationLevel) {
        FontIcon icon = switch (notificationLevel) {
            case WARNING -> new FontIcon(EXCLAMATION_TRIANGLE_FILL);
            case ERROR -> new FontIcon(EXCLAMATION_CIRCLE_FILL);
        };
        icon.getStyleClass()
            .add(getCssStyle(notificationLevel));
        return icon;
    }
}
