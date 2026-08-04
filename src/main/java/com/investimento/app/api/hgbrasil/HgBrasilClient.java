package com.investimento.app.api.hgbrasil;

import com.investimento.app.api.hgbrasil.model.IndicatorPoint;
import com.investimento.app.api.hgbrasil.model.MacroSnapshot;

import java.util.List;
import java.util.Map;

/**
 * Cliente stateless para {@code api.hgbrasil.com} — cobre só o que o app
 * usa: índices, câmbio, CDI/SELIC (via {@code /finance}) e IPCA/meta SELIC
 * (via {@code /v2/finance/indicators}). Não sabe nada de cache/SQLite — essa
 * decisão é da ATV-06 ({@code MarketService}).
 */
public interface HgBrasilClient {

    /**
     * {@code GET /finance} — 1 chamada, cobre moedas + índices + taxas.
     */
    MacroSnapshot getSnapshot() throws HgBrasilException;

    /**
     * {@code GET /v2/finance/indicators?tickers=...} — aceita 1 ou vários
     * tickers numa única chamada (batching). Devolve a série histórica
     * completa de cada ticker pedido.
     */
    Map<String, List<IndicatorPoint>> getIndicators(String... tickers) throws HgBrasilException;

    /**
     * Valor vigente hoje de um único indicador ({@code days_ago=0}) — forma
     * correta de pegar "a SELIC/meta atual" em 1 item, sem precisar filtrar
     * a série inteira (útil para o Dashboard, ATV-12).
     */
    IndicatorPoint getCurrentIndicator(String ticker) throws HgBrasilException;
}
