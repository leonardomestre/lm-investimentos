package com.investimento.app.ui.screens;

import com.investimento.app.api.brapi.BrapiClient;
import com.investimento.app.api.brapi.BrapiException;
import com.investimento.app.api.brapi.model.SearchResult;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.control.TextField;
import javafx.util.Duration;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Autocomplete de ticker B3 (RF01, ATV-13) — liga um {@link TextField} a
 * {@link BrapiClient#searchTickers(String)} com <strong>debounce</strong> de
 * 300ms: a busca só é disparada depois que o usuário para de digitar por esse
 * período, nunca 1 requisição por tecla (armadilha documentada na atividade,
 * causa mais comum de estourar a cota da brapi.dev nesta tela).
 *
 * <p>Também protege contra respostas fora de ordem: cada busca efetivamente
 * despachada carrega um número de geração ({@code searchSeq}, incrementado a
 * cada mudança de texto); se a resposta chegar depois que uma geração mais
 * nova já foi iniciada (usuário digitou mais enquanto a rede respondia), ela
 * é descartada em vez de sobrescrever o autocomplete com um resultado
 * desatualizado.</p>
 *
 * <p>A chamada de rede roda numa {@code Thread} separada (RT06) — nunca
 * bloqueia a FX Application Thread enquanto espera a brapi.dev responder; o
 * callback de resultado é sempre despachado de volta via
 * {@link Platform#runLater(Runnable)}.</p>
 */
class TickerAutocomplete {

    static final Duration DEBOUNCE_DELAY = Duration.millis(300);

    private final BrapiClient brapiClient;
    private final Consumer<List<SearchResult>> onResults;
    private final Runnable onEmptyQuery;
    private final Consumer<BrapiException> onError;
    private final PauseTransition debounce;
    private final AtomicLong searchSeq = new AtomicLong(0);

    TickerAutocomplete(TextField field, BrapiClient brapiClient,
                        Consumer<List<SearchResult>> onResults,
                        Runnable onEmptyQuery,
                        Consumer<BrapiException> onError) {
        this.brapiClient = brapiClient;
        this.onResults = onResults;
        this.onEmptyQuery = onEmptyQuery;
        this.onError = onError;
        this.debounce = new PauseTransition(DEBOUNCE_DELAY);

        field.textProperty().addListener((obs, oldValue, newValue) -> onTextChanged(newValue));
    }

    private void onTextChanged(String text) {
        // Cancela o timer de debounce pendente (se o usuario continuar
        // digitando dentro dos 300ms, nenhuma busca chega a ser despachada)
        // e invalida qualquer resposta de rede ainda em voo de uma geracao
        // anterior - cobre tanto o caso de "apagou o campo" quanto o de
        // "digitou de novo enquanto uma busca respondia".
        debounce.stop();
        long generation = searchSeq.incrementAndGet();

        if (text == null || text.trim().isEmpty()) {
            onEmptyQuery.run();
            return;
        }

        String query = text.trim();
        debounce.setOnFinished(e -> dispatchSearch(query, generation));
        debounce.playFromStart();
    }

    private void dispatchSearch(String query, long generation) {
        Thread thread = new Thread(() -> {
            try {
                List<SearchResult> results = brapiClient.searchTickers(query);
                Platform.runLater(() -> {
                    if (generation == searchSeq.get()) {
                        onResults.accept(results);
                    }
                });
            } catch (BrapiException e) {
                Platform.runLater(() -> {
                    if (generation == searchSeq.get()) {
                        onError.accept(e);
                    }
                });
            }
        }, "ticker-autocomplete-search");
        thread.setDaemon(true);
        thread.start();
    }
}
