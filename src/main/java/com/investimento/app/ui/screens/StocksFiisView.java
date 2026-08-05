package com.investimento.app.ui.screens;

import javafx.scene.Parent;
import javafx.scene.layout.VBox;

/**
 * Stub da tela Ações e FIIs (ATV-07) - conteudo real entra na ATV-14.
 */
public class StocksFiisView implements ScreenView {

    private final VBox root;

    public StocksFiisView() {
        root = ScreenViewStubs.buildStub("Ações e FIIs");
    }

    @Override
    public Parent getRoot() {
        return root;
    }
}
