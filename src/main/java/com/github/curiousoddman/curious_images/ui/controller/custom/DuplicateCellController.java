package com.github.curiousoddman.curious_images.ui.controller.custom;

import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import org.kordamp.ikonli.javafx.FontIcon;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.Collection;

import static com.github.curiousoddman.curious_images.ui.styles.CssClasses.ERROR_TEXT;
import static com.github.curiousoddman.curious_images.ui.styles.CssClasses.GRID_CELL_UNDERLINE;

@Component
@Scope("prototype")
public class DuplicateCellController {
    @FXML
    public  Label     placeholderLabel;
    @FXML
    public  Rectangle placeholderRect;
    @FXML
    public  GridPane  gridPane;
    @FXML
    private VBox      cellRoot;
    @FXML
    private ImageView imageView;
    @FXML
    private CheckBox  checkBox;

    public void setThumbnail(Image image) {
        imageView.setVisible(image != null);
        placeholderLabel.setVisible(image == null);
        placeholderRect.setVisible(image == null);
        if (image != null) {
            imageView.setImage(image);
        }
    }

    private static final Color DIFFERENT = Color.RED;

    public void setInfoText(Collection<DetailRow> rows) {
        int rowIndx = 0;
        for (DetailRow detailRow : rows) {
            FontIcon icon = new FontIcon(detailRow.getIcon());
            icon.setIconSize(16);
            icon.getStyleClass().add(GRID_CELL_UNDERLINE);
            gridPane.add(icon, 0, rowIndx);
            Label labelLine = applyStyle(new Label(detailRow.getLabel()), detailRow);
            labelLine.getStyleClass().add(GRID_CELL_UNDERLINE);
            gridPane.add(labelLine, 1, rowIndx);
            Label valueLine = applyStyle(new Label(detailRow.getValue()), detailRow);
            valueLine.getStyleClass().add(GRID_CELL_UNDERLINE);
            gridPane.add(valueLine, 2, rowIndx);
            rowIndx++;
        }
    }

    private static Label applyStyle(Label line, DetailRow detailRow) {
        line.setFont(Font.font(
                line.getFont()
                    .getFamily(),
                FontWeight.NORMAL,
                16
        ));
        if (detailRow.isDifferent()) {
            line.getStyleClass().add(ERROR_TEXT);
            line.setFont(Font.font(
                    line.getFont()
                        .getFamily(),
                    FontWeight.BOLD,
                    line.getFont()
                        .getSize()
            ));
        }
        return line;
    }

    public CheckBox checkBox() {
        return checkBox;
    }

    public VBox container() {
        return cellRoot;
    }
}
