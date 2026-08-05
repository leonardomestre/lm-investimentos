package com.investimento.app.ui.screens;

import javafx.scene.Parent;
import javafx.scene.layout.VBox;

/**
 * Stub da tela Renda Fixa (ATV-07) - conteudo real entra na ATV-15.
 */
public class FixedIncomeView implements ScreenView {

    private final VBox root;

    public FixedIncomeView() {
        root = ScreenViewStubs.buildStub("Renda Fixa");
    }

    @Override
    public Parent getRoot() {
        return root;
    }
}
