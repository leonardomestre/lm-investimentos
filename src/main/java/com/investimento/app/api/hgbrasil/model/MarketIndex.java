package com.investimento.app.api.hgbrasil.model;

/**
 * Índice de mercado, conforme {@code results.stocks.[INDEX]} de
 * {@code GET /finance} (chaves fixas: {@code IBOVESPA}, {@code IFIX},
 * {@code NASDAQ}, {@code DOWJONES}, {@code CAC}, {@code NIKKEI}).
 *
 * @param name           nome do índice (ex.: {@code BM&F BOVESPA})
 * @param location       praça (ex.: {@code Sao Paulo, Brazil})
 * @param points         pontuação atual
 * @param changePercent  variação percentual no dia
 */
public record MarketIndex(
        String name,
        String location,
        double points,
        double changePercent
) {
}
