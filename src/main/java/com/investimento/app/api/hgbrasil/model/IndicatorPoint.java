package com.investimento.app.api.hgbrasil.model;

/**
 * Um ponto da série histórica de um indicador (IPCA mensal ou meta SELIC
 * por reunião do COPOM), conforme {@code results[].series[]} de
 * {@code GET /v2/finance/indicators}.
 *
 * @param period  {@code yyyy-mm} se mensal (IPCA); {@code yyyy-mm-dd} se diário (meta SELIC)
 * @param value   valor percentual do período
 */
public record IndicatorPoint(
        String period,
        double value
) {
}
