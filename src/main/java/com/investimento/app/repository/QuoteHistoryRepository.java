package com.investimento.app.repository;

import com.investimento.app.data.model.QuoteHistory;

import java.util.List;
import java.util.Optional;

public interface QuoteHistoryRepository {

    Optional<QuoteHistory> findById(long id);

    List<QuoteHistory> listByAsset(long assetId);

    /**
     * Cotacao mais recente do ativo, sem carregar o historico inteiro. O seed
     * inicial grava a serie completa da brapi ({@code range=max}, milhares de
     * linhas por ativo); quem so precisa do preco de hoje — o caso de
     * {@code PositionService}, chamado por ativo a cada refresh de tela — deve
     * usar este metodo, nao {@link #listByAsset(long)}.
     */
    Optional<QuoteHistory> findLatestByAsset(long assetId);

    /**
     * Insere ou sobrescreve a cotacao do dia (chave asset_id+date) — usado
     * tanto pelo seed inicial (ATV-06) quanto pela atualizacao periodica.
     */
    QuoteHistory upsert(QuoteHistory quoteHistory);

    /**
     * Mesmo efeito de chamar {@link #upsert} para cada item, porem numa unica
     * transacao e sem reler cada linha gravada. Existe para o seed inicial:
     * em autocommit, milhares de upserts viram milhares de transacoes (cada
     * uma com fsync no WAL) segurando a conexao unica do app, o que congela a
     * UI enquanto o seed roda em background.
     *
     * @return quantidade de linhas gravadas
     */
    int upsertAll(List<QuoteHistory> quotes);

    void delete(long id);
}
