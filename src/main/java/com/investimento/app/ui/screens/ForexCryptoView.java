package com.investimento.app.ui.screens;

import javafx.scene.Parent;
import javafx.scene.layout.VBox;

/**
 * Stub da tela Câmbio e Cripto (ATV-07) - conteudo real entra na ATV-16.
 */
public class ForexCryptoView implements ScreenView {

    private final VBox root;

    public ForexCryptoView() {
        root = ScreenViewStubs.buildStub("Câmbio e Cripto");
    }

    @Override
    public Parent getRoot() {
        return root;
    }
}
