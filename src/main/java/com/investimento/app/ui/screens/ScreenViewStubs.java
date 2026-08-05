package com.investimento.app.ui.screens;

import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/**
 * Helper package-private para montar o conteudo comum dos 7 stubs de tela
 * desta atividade (ATV-07) - so um {@code Label} com o nome da tela dentro
 * de um {@code .content-card}, estilizado com {@code theme.css}, para
 * confirmar visualmente que a navegacao troca de conteudo. Cada tela real
 * (ATV-12 a ATV-18) substitui o conteudo do seu proprio {@code *View}, sem
 * depender mais deste helper.
 */
final class ScreenViewStubs {

    private ScreenViewStubs() {
    }

    static VBox buildStub(String screenLabel) {
        Label title = new Label(screenLabel);
        title.getStyleClass().add("header-title");

        Label subtitle = new Label("Conteudo desta tela sera implementado em atividade futura.");
        subtitle.getStyleClass().add("header-subtitle");

        VBox card = new VBox(10, title, subtitle);
        card.getStyleClass().add("content-card");

        VBox root = new VBox(card);
        root.setPadding(new Insets(28));
        root.setStyle("-fx-background-color: -fx-color-bg-page;");
        return root;
    }
}
