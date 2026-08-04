package com.investimento.app.api.coingecko.model;

import java.time.LocalDateTime;

/**
 * Preço atual de 1 criptomoeda, conforme item de
 * {@code GET /simple/price?ids=...&vs_currencies=brl}.
 *
 * <p>{@code updatedAt} já vem convertido de Unix timestamp em
 * <strong>segundos</strong> (campo {@code last_updated_at}) para
 * {@link LocalDateTime} dentro do parsing — diferente de
 * {@link HistoricalPoint}, cuja fonte ({@code market_chart}) usa
 * milissegundos (ver {@code coingecko-api/references/campos.md}).
 *
 * @param symbol       símbolo pedido pelo chamador (ex.: {@code BTC}) — nunca
 *                      o {@code id} da CoinGecko
 * @param priceBrl      preço em reais
 * @param change24hPct variação percentual nas últimas 24h
 * @param updatedAt    momento da última atualização do preço, segundo a API
 */
public record CryptoPrice(
        String symbol,
        Double priceBrl,
        Double change24hPct,
        LocalDateTime updatedAt
) {
}
