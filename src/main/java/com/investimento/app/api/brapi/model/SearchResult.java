package com.investimento.app.api.brapi.model;

/**
 * Item de autocomplete/busca de ticker, conforme item de {@code results} de
 * {@code GET /v2/tickers?search=} (RF01, tela 5.2). Já traz o preço atual
 * ({@code quote.lastPrice}), evitando uma segunda chamada só para exibir o
 * preço no autocomplete.
 *
 * @param symbol       ticker (ex.: {@code PETR4})
 * @param name         razão social
 * @param assetType    {@code stock}, {@code bdr}, {@code fund} (FII) etc.
 * @param sector       classificação setorial (em inglês)
 * @param currentPrice {@code quote.lastPrice}
 * @param logoUrl      URL do logo do ativo
 */
public record SearchResult(
        String symbol,
        String name,
        String assetType,
        String sector,
        Double currentPrice,
        String logoUrl
) {
}
