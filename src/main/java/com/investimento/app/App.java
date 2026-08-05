package com.investimento.app;

import com.investimento.app.data.Database;
import com.investimento.app.ui.Shell;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.text.Font;
import javafx.stage.Stage;

import java.io.InputStream;

/**
 * Ponto de entrada do app: carrega fontes, monta o {@link Shell} (sidebar +
 * area de conteudo navegavel - ATV-07) e aplica o theme.css.
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
        // Abre/cria o banco SQLite local e garante o schema (ATV-02) -
        // precisa acontecer cedo, antes de qualquer tela real existir.
        Database.getConnection();

        loadFonts();

        Scene scene = new Scene(new Shell(), 1440, 900);
        scene.getStylesheets().add(getClass().getResource("/theme.css").toExternalForm());

        stage.setTitle("Carteira — LM Investimentos");
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

    @Override
    public void stop() {
        Database.close();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
