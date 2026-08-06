package com.investimento.app.service;

import com.investimento.app.api.hgbrasil.model.IndicatorPoint;
import com.investimento.app.api.hgbrasil.model.MacroSnapshot;
import com.investimento.app.data.model.Asset;
import com.investimento.app.dto.SyncEvent;
import javafx.concurrent.Task;

import java.util.List;
import java.util.Map;
import java.util.Optional;

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
     * Igual a {@link #getMacroSnapshot()}, porém <strong>nunca toca a
     * rede</strong>: devolve só o que já está em memória ({@code null} se
     * nada foi buscado com sucesso ainda), mesmo que o TTL já tenha vencido.
     *
     * <p>É esta a versão que a UI thread deve usar. {@link #getMacroSnapshot()}
     * bloqueia a thread chamadora pelo tempo da requisição HTTP quando o cache
     * está vencido — na FX thread isso trava a janela. O padrão correto numa
     * tela é: desenhar com o valor cacheado, disparar {@link #getMacroSnapshot()}
     * dentro de um {@link Task} e redesenhar em {@code setOnSucceeded}.</p>
     */
    MacroSnapshot getCachedMacroSnapshot();

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
     * Igual a {@link #getIndicators()}, porém <strong>nunca toca a rede</strong>
     * — devolve o cache atual (mapa vazio se nada foi buscado ainda). Ver a
     * nota de uso em {@link #getCachedMacroSnapshot()}.
     */
    Map<String, List<IndicatorPoint>> getCachedIndicators();

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

    /**
     * Mensagem da última falha de rede registrada (qualquer uma das 3 APIs),
     * ou vazio se nenhuma falha ocorreu desde a última busca bem-sucedida da
     * mesma operação — usado pelo aviso "API falhou" do Dashboard (tela de
     * Configurações, toggle "Avisar quando a API falhar"). Não persiste entre
     * aberturas do app (reinicia junto com {@code MarketServiceImpl}).
     */
    Optional<String> getLastFailure();

    /**
     * Últimas tentativas de sincronização por fonte (mais recente primeiro),
     * só em memória (não persiste entre aberturas do app) — alimenta a tabela
     * "Últimas sincronizações" da tela de Configurações. Só registra uma
     * entrada quando a fonte tinha pelo menos 1 ativo a atualizar (mesma
     * lógica de "nada a fazer" já usada em cada {@code updateXxxAssets}).
     */
    List<SyncEvent> getRecentSyncs();
}
