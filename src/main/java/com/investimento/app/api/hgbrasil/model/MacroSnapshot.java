package com.investimento.app.api.hgbrasil.model;

import java.util.Map;

/**
 * Painel macro completo obtido em uma única chamada a {@code GET /finance}:
 * moedas, índices e a taxa (CDI/SELIC) do dia.
 *
 * @param currencies  moedas por código ISO (ex.: {@code USD}, {@code EUR}, {@code BTC})
 * @param indices     índices por nome (ex.: {@code IBOVESPA}, {@code IFIX})
 * @param todayRate   taxas (CDI/SELIC) do dia vigente
 */
public record MacroSnapshot(
        Map<String, Currency> currencies,
        Map<String, MarketIndex> indices,
        DailyRate todayRate
) {
}
