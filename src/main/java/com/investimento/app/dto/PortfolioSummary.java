package com.investimento.app.dto;

/**
 * Resumo agregado de toda a carteira (RF04/5.1, RF06) — soma todas as
 * {@link Position} (já convertidas para BRL, ver
 * {@code PositionServiceImpl}), sem entidade correspondente (mesma regra de
 * {@link Position}, CONVENCOES.md seção 2-3).
 *
 * @param totalInvestedValue soma de {@code investedValue} de todas as posições
 * @param totalCurrentValue  soma de {@code currentValue} de todas as posições
 * @param gainLossAmount     {@code totalCurrentValue - totalInvestedValue}, em R$
 * @param gainLossPercent    ganho/perda em % sobre o total investido
 * @param cdiReturnPct       CDI acumulado desde a menor {@code investmentDate}/
 *                           {@code date} de transação entre todos os ativos —
 *                           aproximação razoável de "período" (RF04, "comparação
 *                           com CDI do período"); comparar com
 *                           {@code gainLossPercent} é responsabilidade da UI
 *                           (ATV-12)
 */
public record PortfolioSummary(
        double totalInvestedValue,
        double totalCurrentValue,
        double gainLossAmount,
        double gainLossPercent,
        double cdiReturnPct
) {
}
