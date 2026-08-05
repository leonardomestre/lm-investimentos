package com.investimento.app.dto;

import com.investimento.app.data.model.OperationType;

import java.time.LocalDate;

/**
 * Campos brutos vindos do formulário de registro de transação (ATV-13) —
 * espelha {@code TransactionDTO} sem {@code id} (gerado pelo banco na
 * inserção), mesmo padrão de {@code CreateAssetRequest} (ATV-08). Diferente
 * de {@code Asset}, {@code Transaction} não tem campos derivados — por isso
 * este record é praticamente idêntico a {@code TransactionDTO} menos o
 * {@code id} — mesmo assim é mantido separado (não reaproveitado) para
 * seguir o mesmo contrato de {@code create(...)} usado em {@code
 * AssetService}, e para deixar explícito na assinatura do método que o
 * chamador nunca informa um {@code id} ao criar.
 */
public record CreateTransactionRequest(
        Long assetId,
        OperationType operationType,
        LocalDate date,
        double quantity,
        double unitPrice,
        double fees,
        String notes
) {
}
