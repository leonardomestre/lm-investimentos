package com.investimento.app.ui.screens;

import javafx.scene.Parent;
import javafx.scene.layout.VBox;

/**
 * Stub da tela Configurações (ATV-07) - conteudo real entra na ATV-18.
 */
public class SettingsView implements ScreenView {

    private final VBox root;

    public SettingsView() {
        root = ScreenViewStubs.buildStub("Configurações");
    }

    @Override
    public Parent getRoot() {
        return root;
    }
}
