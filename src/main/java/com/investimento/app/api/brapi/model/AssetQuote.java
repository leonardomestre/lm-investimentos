package com.investimento.app.api.brapi.model;

import java.time.LocalDateTime;

/**
 * Cotação atual de 1 ticker B3, conforme item de {@code results} de
 * {@code GET /v2/stocks/quote?symbols=}.
 *
 * <p>{@code symbol} é o ticker como foi pedido pelo chamador (não lido do
 * corpo — nunca do campo {@code requestedSymbol}, ver
 * {@code brapi-api/SKILL.md} seção 7); {@code resolvedSymbol} é sempre lido
 * do campo {@code symbol} da resposta, o ticker resolvido/atual segundo a
 * API. Se a API renomeou o ticker (ex.: {@code BIDI11} → {@code INBR32}),
 * {@code symbol != resolvedSymbol} e {@code changed = true} — quem cadastra
 * o ativo (ATV-08) decide se avisa o usuário.
 *
 * @param symbol         ticker exatamente como solicitado pelo chamador
 * @param resolvedSymbol ticker resolvido pela API (campo {@code symbol} da resposta) — usar este ao persistir
 * @param changed         {@code true} se {@code resolvedSymbol} difere do ticker pedido
 * @param currentPrice    {@code data.regularMarketPrice} — preço atual
 * @param changePercent   {@code data.regularMarketChangePercent} — variação % no dia
 * @param changeAmount    {@code data.regularMarketChange} — variação absoluta no dia
 * @param volume          {@code data.regularMarketVolume} — volume negociado no dia
 * @param marketCap       {@code data.marketCap} — valor de mercado, {@code null} em FIIs
 * @param updatedAt       {@code data.regularMarketTime} — timestamp do último preço
 */
public record AssetQuote(
        String symbol,
        String resolvedSymbol,
        boolean changed,
        Double currentPrice,
        Double changePercent,
        Double changeAmount,
        Long volume,
        Double marketCap,
        LocalDateTime updatedAt
) {
}
