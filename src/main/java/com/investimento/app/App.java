package com.investimento.app;

import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.stage.Stage;

import java.io.InputStream;

/**
 * Smoke test do esqueleto do projeto: confirma que o JavaFX inicia, que as
 * fontes (Manrope/IBM Plex Mono) carregam e que o theme.css (paleta da skill
 * design-system) se aplica corretamente. Nao e uma tela real do app.
 */
public class App extends Application {

    private static final String[] FONT_FILES = {
        "/fonts/Manrope-Regular.ttf",
        "/fonts/Manrope-Medium.ttf",
        "/fonts/Manrope-SemiBold.ttf",
        "/fonts/Manrope-Bold.ttf",
        "/fonts/Manrope-ExtraBold.ttf",
        "/fonts/IBMPlexMono-Regular.ttf",
        "/fonts/IBMPlexMono-Medium.ttf",
        "/fonts/IBMPlexMono-SemiBold.ttf",
    };

    @Override
    public void start(Stage stage) {
        loadFonts();

        Label label = new Label("SELIC");
        label.getStyleClass().add("kpi-label");

        Label value = new Label("15,00% a.a.");
        value.getStyleClass().add("kpi-value");

        VBox kpiCard = new VBox(9, label, value);
        kpiCard.getStyleClass().add("kpi-card");

        StackPane root = new StackPane(kpiCard);
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(40));
        root.setStyle("-fx-background-color: -fx-color-bg-page;");

        Scene scene = new Scene(root, 480, 320);
        scene.getStylesheets().add(getClass().getResource("/theme.css").toExternalForm());

        stage.setTitle("Investimento — smoke test do design system");
        stage.setScene(scene);
        stage.show();
    }

    private void loadFonts() {
        for (String path : FONT_FILES) {
            try (InputStream is = getClass().getResourceAsStream(path)) {
                if (is == null) {
                    System.err.println("Fonte nao encontrada no classpath: " + path);
                    continue;
                }
                Font.loadFont(is, 0);
            } catch (Exception e) {
                System.err.println("Falha ao carregar fonte " + path + ": " + e.getMessage());
            }
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
