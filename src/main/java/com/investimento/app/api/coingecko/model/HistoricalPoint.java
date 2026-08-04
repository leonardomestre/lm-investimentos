package com.investimento.app.api.coingecko.model;

import java.time.LocalDate;

/**
 * Ponto de histórico de preço, conforme item de {@code prices} de
 * {@code GET /coins/{id}/market_chart?vs_currency=&days=}.
 *
 * <p>{@code date} já vem convertido de Unix timestamp em
 * <strong>milissegundos</strong> para {@link LocalDate} dentro do parsing —
 * diferente de {@link CryptoPrice#updatedAt()}, cuja fonte
 * ({@code /simple/price}) usa segundos.
 *
 * @param date  data do ponto (convertida de Unix milissegundos)
 * @param price preço em reais no ponto
 */
public record HistoricalPoint(
        LocalDate date,
        Double price
) {
}
