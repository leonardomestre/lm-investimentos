package com.investimento.app.api.hgbrasil.model;

/**
 * Cotação de uma moeda estrangeira em relação ao BRL, conforme
 * {@code results.currencies.[ISO]} de {@code GET /finance}.
 *
 * @param iso            código ISO da moeda (ex.: {@code USD}, {@code EUR}, {@code BTC})
 * @param name           nome em inglês (ex.: {@code Dollar})
 * @param buy            valor de compra em BRL
 * @param sell           valor de venda em BRL — {@code null} na maioria das moedas
 * @param changePercent  variação percentual desde a última hora útil
 */
public record Currency(
        String iso,
        String name,
        double buy,
        Double sell,
        double changePercent
) {
}
