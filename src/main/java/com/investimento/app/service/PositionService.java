package com.investimento.app.service;

import com.investimento.app.dto.FixedIncomeProjectionPoint;
import com.investimento.app.dto.PortfolioSummary;
import com.investimento.app.dto.Position;
import com.investimento.app.dto.RealizedSale;

import java.util.List;

/**
 * Motor de cálculo de ganho/perda (RF06) — para cada ativo e para a carteira
 * inteira, calcula quantidade em carteira, preço médio (custo médio, método
 * exigido pela Receita Federal para pessoa física em ações/FIIs), valor
 * investido, valor atual e ganho/perda em R$ e em %. Usado por praticamente
 * todas as telas (Dashboard, Ações/FIIs, Renda Fixa, Moeda/Cripto — ATV-12 em
 * diante) e, para {@link #calculateRealizedSales(long)}, pela ATV-11 (RF07,
 * ganho de capital para IR).
 *
 * <p>Recebe sempre {@code assetId} (nunca a entidade {@code Asset}) — quem
 * chama (UI ou outro service) não precisa segurar uma entidade Lombok
 * (CONVENCOES.md seção 2).
 */
public interface PositionService {

    /** Posição atual de um único ativo. */
    Position calculatePosition(long assetId);

    /**
     * Posição de todos os ativos ativos ({@code is_active = 1}).
     *
     * @param includeZeroed se {@code false}, omite ativos com {@code
     *                      currentQuantity == 0} (usuário vendeu tudo) — as
     *                      transações continuam existindo para o relatório de
     *                      IR (ATV-17), só não aparecem como posição atual da
     *                      carteira.
     */
    List<Position> calculateAllPositions(boolean includeZeroed);

    /**
     * Agrega todas as {@link Position} (excluindo zeradas) num resumo de
     * carteira: total investido, total atual, ganho/perda e comparação com o
     * CDI do período.
     */
    PortfolioSummary calculatePortfolioSummary();

    /**
     * Ganho/perda realizado de cada venda ({@code SELL}) de um ativo, na
     * ordem cronológica, com o preço médio vigente no momento de cada venda.
     * Reaproveita o MESMO caminho de custo médio de {@link
     * #calculatePosition(long)} em vez de duplicar a lógica — usado pela
     * ATV-11 (RF07, ganho de capital) sem recalcular preço médio por conta
     * própria.
     */
    List<RealizedSale> calculateRealizedSales(long assetId);

    /**
     * Valor líquido projetado de um ativo de renda fixa (categoria {@link
     * com.investimento.app.data.model.Category#FIXED_INCOME}), aplicando IR
     * regressivo (ATV-15) sobre o rendimento (valor atual projetado − valor
     * investido) — nunca sobre o valor total. O prazo usado para escolher a
     * alíquota é {@code hoje - investmentDate} (não {@code maturityDate -
     * investmentDate}): o IR real só é cobrado no resgate, então este número é
     * sempre uma estimativa "se eu resgatasse hoje", conforme a armadilha
     * documentada na atividade. Nunca devolve um valor maior que o valor
     * bruto ({@link Position#currentValue()}) — se o rendimento for negativo
     * ou nulo, não há imposto a aplicar.
     *
     * @throws IllegalArgumentException se o ativo não existir ou não for
     *                                   {@code FIXED_INCOME}
     */
    double calculateFixedIncomeNetValue(long assetId);

    /**
     * Projeção mensal (bruto e líquido) de um ativo de renda fixa, do primeiro
     * dia após {@code investmentDate} até {@code maturityDate} (inclusive) —
     * usada pelo gráfico de projeção da ATV-15. A mesma fórmula de juros
     * compostos da ATV-10 ({@code initialInvestedAmount * (1 +
     * equivalentAnnualRate)^years}) é aplicada em cada ponto; o líquido de
     * cada ponto usa a alíquota regressiva referente ao prazo decorrido
     * *naquele* ponto (não ao prazo de hoje). O último ponto da lista é
     * sempre exatamente {@code maturityDate} (nunca um ponto além dela, nem
     * um mês antes por arredondamento).
     *
     * @return lista vazia se o ativo não tiver {@code investmentDate}/{@code
     *         maturityDate} definidos, ou se {@code maturityDate} não for
     *         posterior a {@code investmentDate}
     * @throws IllegalArgumentException se o ativo não existir ou não for
     *                                   {@code FIXED_INCOME}
     */
    List<FixedIncomeProjectionPoint> calculateFixedIncomeProjection(long assetId);
}
