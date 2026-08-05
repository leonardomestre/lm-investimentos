package com.investimento.app.ui.screens;

import javafx.scene.Parent;
import javafx.scene.layout.VBox;

/**
 * Stub da tela Histórico e IR (ATV-07) - conteudo real entra na ATV-17.
 */
public class TaxHistoryView implements ScreenView {

    private final VBox root;

    public TaxHistoryView() {
        root = ScreenViewStubs.buildStub("Histórico e IR");
    }

    @Override
    public Parent getRoot() {
        return root;
    }
}
