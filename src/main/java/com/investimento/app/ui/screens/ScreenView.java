package com.investimento.app.ui.screens;

import javafx.scene.Parent;

/**
 * Contrato minimo de uma tela do shell. Cada tela real (ATV-12+) monta o seu
 * {@link Parent} uma unica vez e reutiliza a mesma instancia entre trocas de
 * navegacao (ver {@code Shell} - nao recria a view a cada clique).
 */
public interface ScreenView {

    Parent getRoot();

    /**
     * Chamado toda vez que a tela volta a ficar visivel - usar para disparar
     * atualizacao de dados (ATV-12+ conectam isso ao MarketService).
     */
    default void onShow() {
    }
}
