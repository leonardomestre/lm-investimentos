package com.investimento.app.dto;

import java.time.LocalDate;

/**
 * Um ponto (mensal) da projeção de valor de um ativo de renda fixa entre a
 * data de aplicação e a data de vencimento — resultado de cálculo (ATV-15),
 * sem entidade/tabela correspondente, então **não** tem {@code Mapper}
 * (CONVENCOES.md seção 2-3, mesma regra de {@link Position}/{@link
 * RealizedSale}). Montado por {@link
 * com.investimento.app.service.PositionService#calculateFixedIncomeProjection(long)}.
 *
 * @param date       data do ponto (o último ponto da lista é sempre igual a
 *                    {@code Asset.maturityDate}, nunca ultrapassa)
 * @param grossValue valor bruto projetado em R$ (juros compostos pela taxa
 *                    contratada/indexador, mesma fórmula da ATV-10)
 * @param netValue   valor líquido projetado em R$ (bruto menos IR regressivo
 *                    sobre o rendimento até aquela data — "se fosse resgatado
 *                    naquela data")
 */
public record FixedIncomeProjectionPoint(LocalDate date, double grossValue, double netValue) {
}
