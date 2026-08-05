package com.investimento.app.service;

import com.investimento.app.data.model.Category;
import com.investimento.app.dto.AnnualIncomeTaxSummary;
import com.investimento.app.dto.MonthlyTaxAssessment;

import java.time.YearMonth;
import java.util.List;

/**
 * Apura o ganho de capital tributável (RF07) por mês e por categoria,
 * aplicando as regras de isenção corretas por tipo de ativo — reaproveita
 * {@link PositionService#calculateRealizedSales(long)} (ATV-10) em vez de
 * recalcular preço médio/venda por conta própria.
 *
 * <p>Só apura {@code STOCKS}, {@code FIIS} e {@code CRYPTO}. {@code
 * FIXED_INCOME} é tratado à parte (projeção de valor líquido, ATV-15) e
 * {@code FOREX} não tem regra de tributação definida no planejamento (ver
 * ATV-11) — os 3 métodos abaixo lançam {@link IllegalArgumentException}
 * para essas 2 categorias em vez de inventar um número.
 */
public interface IncomeTaxService {

    /** Apuração de UMA categoria tributável em UM mês específico. */
    MonthlyTaxAssessment assessMonth(Category category, YearMonth month);

    /**
     * Apuração de UMA categoria tributável, um {@link MonthlyTaxAssessment}
     * por mês daquele ano em que houve pelo menos uma venda realizada — meses
     * sem venda nenhuma não geram entrada na lista.
     */
    List<MonthlyTaxAssessment> assessYear(Category category, int year);

    /** Agrega as 3 categorias tributáveis ({@code STOCKS}/{@code FIIS}/{@code CRYPTO}) de um ano. */
    AnnualIncomeTaxSummary assessAnnualSummary(int year);

    /**
     * Soma dos {@code grossGain} negativos ("prejuízo a compensar") de todos
     * os meses ANTERIORES a {@code until} daquela categoria — implementação
     * mínima de exposição de prejuízo acumulado (ATV-11, seção "Compensação
     * de prejuízo"), NÃO é abatimento automático mês a mês: nenhum outro
     * método deste serviço usa este valor para reduzir {@code taxDue}. Devolve
     * um número positivo (magnitude do prejuízo), nunca negativo.
     */
    double getAccumulatedLoss(Category category, YearMonth until);
}
