package com.investimento.app.service;

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
}
