package com.investimento.app.dto;

import com.investimento.app.data.model.AssetType;
import com.investimento.app.data.model.Benchmark;
import com.investimento.app.data.model.Category;
import com.investimento.app.data.model.QuoteSource;

import java.time.LocalDate;

/**
 * Espelha a entidade {@code Asset} campo a campo (exceto {@code createdAt},
 * que não interessa à UI) — é o que {@code AssetService} devolve para quem
 * chama (nunca a entidade Lombok, ver CONVENCOES.md seção 2-3). Convertido de/
 * para {@code Asset} via {@link com.investimento.app.mapper.AssetMapper}.
 */
public record AssetDTO(
        Long id,
        AssetType type,
        Category category,
        String ticker,
        String displayName,
        String currency,
        QuoteSource quoteSource,
        String sourceIdentifier,
        Benchmark benchmark,
        Double contractedRatePct,
        String financialInstitution,
        LocalDate investmentDate,
        LocalDate maturityDate,
        boolean active
) {
}
