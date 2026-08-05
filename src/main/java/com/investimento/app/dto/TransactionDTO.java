package com.investimento.app.dto;

import com.investimento.app.data.model.OperationType;

import java.time.LocalDate;

/**
 * Espelha a entidade {@code Transaction} campo a campo (exceto {@code
 * createdAt}, que não interessa à UI — mesma decisão de {@code AssetDTO},
 * ATV-08) — é o que {@code TransactionService} devolve para quem chama
 * (nunca a entidade Lombok, ver CONVENCOES.md seção 2-3). Convertido de/para
 * {@code Transaction} via {@link com.investimento.app.mapper.TransactionMapper}.
 */
public record TransactionDTO(
        Long id,
        Long assetId,
        OperationType operationType,
        LocalDate date,
        double quantity,
        double unitPrice,
        double fees,
        String notes
) {
}
