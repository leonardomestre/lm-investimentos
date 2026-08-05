package com.investimento.app.dto;

/**
 * Posição atual de um ativo na carteira — resultado de cálculo (RF06), não
 * espelha nenhuma tabela do banco, então **não** tem entidade correspondente
 * nem {@code PositionMapper} (CONVENCOES.md seção 2-3). Montado por {@link
 * com.investimento.app.service.PositionService}, que agrega as {@code
 * transactions} do ativo (ATV-09) e o preço atual (ATV-06).
 *
 * @param asset             DTO do ativo (nunca a entidade Lombok — ver
 *                          CONVENCOES.md seção 2)
 * @param currentQuantity   quantidade em carteira hoje (0 se o usuário vendeu
 *                          tudo — não gera divisão por zero em nenhum outro
 *                          campo)
 * @param averagePrice      preço médio ponderado (custo médio, método exigido
 *                          pela Receita Federal para pessoa física)
 * @param investedValue     valor investido, em R$ (= {@code accumulatedCost})
 * @param currentValue      valor atual, em R$ (já convertido de moeda
 *                          estrangeira quando aplicável)
 * @param gainLossAmount    ganho/perda em R$ ({@code currentValue - investedValue})
 * @param gainLossPercent   ganho/perda em % sobre o valor investido
 */
public record Position(
        AssetDTO asset,
        double currentQuantity,
        double averagePrice,
        double investedValue,
        double currentValue,
        double gainLossAmount,
        double gainLossPercent
) {
}
