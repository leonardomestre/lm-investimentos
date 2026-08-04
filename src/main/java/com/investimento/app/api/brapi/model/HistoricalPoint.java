package com.investimento.app.api.brapi.model;

import java.time.LocalDate;

/**
 * Ponto de histórico de preço, conforme item de
 * {@code data.historicalDataPrice} de {@code GET /quote/{ticker}?range=&interval=}.
 *
 * <p>{@code date} já vem convertido de Unix timestamp (segundos) para
 * {@link LocalDate} dentro do parsing — o {@code long} cru da resposta não
 * vaza para fora do cliente. Prefira {@code adjustedClose} sobre
 * {@code close} para gráfico de rentabilidade (ajustado por
 * proventos/desdobramentos); {@code close} continua disponível para quem
 * precisar do preço nominal.
 *
 * @param date          data do ponto (convertida de Unix segundos)
 * @param open          preço de abertura
 * @param high          preço máximo do período
 * @param low           preço mínimo do período
 * @param close         preço de fechamento nominal
 * @param volume        volume negociado
 * @param adjustedClose fechamento ajustado — preferir para gráfico de rentabilidade
 */
public record HistoricalPoint(
        LocalDate date,
        Double open,
        Double high,
        Double low,
        Double close,
        Long volume,
        Double adjustedClose
) {
}
