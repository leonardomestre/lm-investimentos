package com.investimento.app.dto;

import java.time.Instant;

/**
 * Registro de uma tentativa de sincronização de cotações por fonte (brapi.dev
 * / CoinGecko / HG Brasil), mantido só em memória por {@code MarketService}
 * (não persistido — reinicia a cada abertura do app). Alimenta a tabela
 * "Últimas sincronizações" da tela de Configurações.
 */
public record SyncEvent(Instant timestamp, String source, int assetCount, boolean success) {
}
