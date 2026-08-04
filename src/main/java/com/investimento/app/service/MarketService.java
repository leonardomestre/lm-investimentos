package com.investimento.app.service;

import com.investimento.app.api.hgbrasil.model.IndicatorPoint;
import com.investimento.app.api.hgbrasil.model.MacroSnapshot;
import com.investimento.app.data.model.Asset;
import javafx.concurrent.Task;

import java.util.List;
import java.util.Map;

/**
 * Camada intermediária entre as telas e os 3 clientes de API (ATV-03/04/05) —
 * decide "busco de novo ou uso o que já está no banco/memória", persiste toda
 * resposta de rede localmente (RF03) e roda a atualização periódica fora da
 * UI thread (RT06).
 *
 * <p><strong>Nenhuma tela deve chamar {@code HgBrasilClient}/{@code
 * BrapiClient}/{@code CoinGeckoClient} diretamente</strong> — toda tela fala
 * só com este serviço.
 */
public interface MarketService {

    /**
     * Cache em memória (TTL 5 min) do snapshot HG Brasil (moedas, índices,
     * CDI/SELIC do dia). CDI/SELIC do dia também é gravado em
     * {@code rate_history} via upsert a cada busca bem-sucedida.
     *
     * <p>Se a chamada de rede falhar e não houver nada em cache ainda, pode
     * devolver {@code null} — quem chama (tela) decide o placeholder.
     */
    MacroSnapshot getMacroSnapshot();

    /**
     * Cache em memória (TTL 6h) de IPCA + meta SELIC (séries completas).
     * Grava a série completa em {@code indicator_history} via upsert a cada
     * busca bem-sucedida.
     *
     * <p>Se a chamada de rede falhar e não houver nada em cache ainda,
     * devolve um mapa vazio.
     */
    Map<String, List<IndicatorPoint>> getIndicators();

    /**
     * Roda em background ({@link javafx.concurrent.Task}) — atualiza a
     * cotação de cada ativo da lista, roteando para o cliente certo conforme
     * {@code asset.getQuoteSource()}, respeitando o TTL de cada fonte, e
     * grava em {@code quote_history} (upsert). Agrupa por fonte antes de
     * rotear: todos os ativos {@code COINGECKO} viram 1 chamada só; ativos
     * {@code BRAPI} viram N chamadas (1 por ticker, limite do plano).
     */
    Task<Void> updateQuotes(List<Asset> assets);

    /**
     * Chamado 1 vez, no cadastro de um ativo novo (ATV-08) — busca histórico
     * inicial e grava tudo de uma vez em {@code quote_history}. brapi.dev:
     * {@code range=max}. CoinGecko: {@code days=365}. HG Brasil (câmbio): não
     * há histórico gratuito, não faz nada para {@code quoteSource ==
     * HGBRASIL}.
     */
    Task<Void> seedInitialHistory(Asset asset);

    /**
     * Variação % do dia por ativo — vem direto do último payload da API
     * ({@code regularMarketChangePercent} na brapi, {@code *_24h_change} na
     * CoinGecko), populada em memória durante {@link #updateQuotes}. Usado
     * pela ATV-12 (Dashboard, "maiores variações do dia").
     */
    Map<Long, Double> getDailyChanges(List<Asset> assets);
}
