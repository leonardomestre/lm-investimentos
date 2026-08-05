package com.investimento.app.ui.screens;

import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/**
 * Stub da tela Dashboard (ATV-07) - conteudo real entra na ATV-12.
 */
public class DashboardView implements ScreenView {

    private final VBox root;

    public DashboardView() {
        root = ScreenViewStubs.buildStub("Dashboard");
    }

    @Override
    public Parent getRoot() {
        return root;
    }
}
