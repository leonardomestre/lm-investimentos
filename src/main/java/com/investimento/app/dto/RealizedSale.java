package com.investimento.app.dto;

import java.time.LocalDate;

/**
 * Ganho/perda realizado de UMA transação de venda ({@code SELL}) — insumo da
 * ATV-11 (RF07, ganho de capital para IR), que reaproveita
 * {@link com.investimento.app.service.PositionService#calculateRealizedSales(long)}
 * em vez de duplicar a lógica de custo médio. Sem entidade correspondente
 * (mesma regra de {@link Position}, CONVENCOES.md seção 2-3).
 *
 * @param transactionId       id da {@code Transaction} de venda de origem
 * @param asset                DTO do ativo (nunca a entidade Lombok)
 * @param date                 data da venda
 * @param quantity             quantidade vendida
 * @param salePrice            preço unitário de venda
 * @param averagePriceAtSale   preço médio vigente NO MOMENTO desta venda
 *                             (custo médio acumulado até então, não o preço
 *                             médio atual da posição)
 * @param fees                 taxas/corretagem da venda
 * @param realizedGainAmount   {@code (salePrice - averagePriceAtSale) * quantity - fees}
 */
public record RealizedSale(
        long transactionId,
        AssetDTO asset,
        LocalDate date,
        double quantity,
        double salePrice,
        double averagePriceAtSale,
        double fees,
        double realizedGainAmount
) {
}
