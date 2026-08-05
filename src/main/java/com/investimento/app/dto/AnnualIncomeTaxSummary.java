package com.investimento.app.dto;

import com.investimento.app.data.model.Category;

import java.util.List;
import java.util.Map;

/**
 * Resumo anual de imposto de renda (RF07/ATV-11), agregando as 3 categorias
 * tributáveis por este serviço ({@code STOCKS}, {@code FIIS}, {@code
 * CRYPTO}) — resultado de cálculo, sem entidade correspondente (CONVENCOES.md
 * seção 2-3). Renda fixa (projeção líquida na ATV-15) e câmbio (regra não
 * definida, ver ATV-11) nunca aparecem aqui.
 *
 * @param year                    ano apurado
 * @param assessmentsByCategory   {@link MonthlyTaxAssessment} de cada mês com
 *                                 venda realizada naquele ano, por categoria —
 *                                 meses sem nenhuma venda não geram entrada
 * @param totalTaxDue              soma de {@code taxDue} de todos os meses de
 *                                 todas as categorias
 */
public record AnnualIncomeTaxSummary(
        int year,
        Map<Category, List<MonthlyTaxAssessment>> assessmentsByCategory,
        double totalTaxDue
) {
}
