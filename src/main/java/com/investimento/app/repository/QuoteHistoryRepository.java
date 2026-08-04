package com.investimento.app.repository;

import com.investimento.app.data.model.QuoteHistory;

import java.util.List;
import java.util.Optional;

public interface QuoteHistoryRepository {

    Optional<QuoteHistory> findById(long id);

    List<QuoteHistory> listByAsset(long assetId);

    /**
     * Insere ou sobrescreve a cotacao do dia (chave asset_id+date) — usado
     * tanto pelo seed inicial (ATV-06) quanto pela atualizacao periodica.
     */
    QuoteHistory upsert(QuoteHistory quoteHistory);

    void delete(long id);
}
