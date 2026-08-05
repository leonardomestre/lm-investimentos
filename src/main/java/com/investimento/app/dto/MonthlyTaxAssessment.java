package com.investimento.app.dto;

import com.investimento.app.data.model.Category;

import java.time.YearMonth;

/**
 * Apuração de ganho de capital de UMA {@link Category} tributável em UM
 * mês (RF07/ATV-11) — resultado de cálculo, sem entidade correspondente
 * (CONVENCOES.md seção 2-3, mesma regra de {@link Position}/{@link
 * RealizedSale}). Montado por {@link
 * com.investimento.app.service.IncomeTaxService}, que agrega as {@link
 * RealizedSale} (ATV-10) do mês/categoria.
 *
 * @param category    categoria apurada — só {@code STOCKS}/{@code FIIS}/
 *                     {@code CRYPTO} (renda fixa e câmbio não são apuradas
 *                     por este serviço, ver ATV-11)
 * @param month        ano-mês apurado
 * @param totalSold    soma de {@code quantity * salePrice} de todas as
 *                      vendas da categoria naquele mês — é o valor que
 *                      compara com o teto de isenção (R$20k ações / R$35k
 *                      cripto), NÃO o ganho
 * @param grossGain    soma de {@code realizedGainAmount} de todas as vendas
 *                      da categoria naquele mês (pode ser negativo)
 * @param exempt        {@code true} se o mês inteiro está isento (não
 *                      ultrapassou o teto de venda) — sempre {@code false}
 *                      para {@code FIIS}, que nunca tem isenção
 * @param taxDue        {@code grossGain * 0.15} se {@code !exempt} e {@code
 *                      grossGain > 0}; {@code 0} caso contrário (nunca
 *                      imposto negativo, mesmo com prejuízo no mês)
 */
public record MonthlyTaxAssessment(
        Category category,
        YearMonth month,
        double totalSold,
        double grossGain,
        boolean exempt,
        double taxDue
) {
}
