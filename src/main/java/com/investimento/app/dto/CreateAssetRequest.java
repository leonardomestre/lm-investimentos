package com.investimento.app.dto;

import com.investimento.app.data.model.AssetType;
import com.investimento.app.data.model.Benchmark;

import java.time.LocalDate;

/**
 * Campos brutos vindos do formulário de cadastro (ATV-13) — <strong>não</strong>
 * passa pelo {@code AssetMapper} (CONVENCOES.md seção 3): não tem {@code id}
 * nem os campos derivados de {@code Asset} ({@code category}, {@code
 * quoteSource}, {@code sourceIdentifier}), que {@code AssetServiceImpl.create()}
 * calcula, não copia 1:1.
 *
 * <p>Uso de cada campo por {@link AssetType} (ver ATV-08, tabela de
 * validações):
 * <ul>
 *   <li>{@code type}/{@code displayName}: sempre obrigatórios.</li>
 *   <li>{@code ticker}: obrigatório para {@code STOCK}/{@code FII}/{@code
 *   ETF}/{@code BDR} (ticker B3) e para {@code CRYPTO} (símbolo, ex.:
 *   {@code "BTC"}) — mesma coluna {@code assets.ticker}, ver comentário do
 *   {@code schema.sql}.</li>
 *   <li>{@code currency}: obrigatório para {@code FOREIGN_CURRENCY} (código
 *   ISO de 3 letras, ex.: {@code "USD"}); ignorado nos demais tipos (moeda
 *   assumida {@code "BRL"}).</li>
 *   <li>{@code benchmark}/{@code contractedRatePct}/{@code
 *   financialInstitution}/{@code investmentDate}/{@code maturityDate}:
 *   obrigatórios só para {@code FIXED_INCOME}.</li>
 * </ul>
 */
public record CreateAssetRequest(
        AssetType type,
        String ticker,
        String displayName,
        String currency,
        Benchmark benchmark,
        Double contractedRatePct,
        String financialInstitution,
        LocalDate investmentDate,
        LocalDate maturityDate
) {
}
