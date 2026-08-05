package com.investimento.app.ui.screens;

import javafx.scene.Parent;
import javafx.scene.layout.VBox;

/**
 * Stub da tela Cadastro e transações (ATV-07) - conteudo real entra na
 * ATV-13.
 */
public class RegistrationView implements ScreenView {

    private final VBox root;

    public RegistrationView() {
        root = ScreenViewStubs.buildStub("Cadastro e transações");
    }

    @Override
    public Parent getRoot() {
        return root;
    }
}
