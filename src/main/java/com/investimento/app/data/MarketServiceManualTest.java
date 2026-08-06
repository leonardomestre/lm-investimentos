package com.investimento.app.data;

import com.investimento.app.api.brapi.BrapiClientImpl;
import com.investimento.app.api.coingecko.CoinGeckoClientImpl;
import com.investimento.app.api.hgbrasil.HgBrasilClientImpl;
import com.investimento.app.api.hgbrasil.model.MacroSnapshot;
import com.investimento.app.data.model.Asset;
import com.investimento.app.data.model.AssetType;
import com.investimento.app.data.model.Category;
import com.investimento.app.data.model.QuoteSource;
import com.investimento.app.repository.AssetRepository;
import com.investimento.app.repository.AssetRepositoryImpl;
import com.investimento.app.repository.IndicatorHistoryRepository;
import com.investimento.app.repository.IndicatorHistoryRepositoryImpl;
import com.investimento.app.repository.QuoteHistoryRepository;
import com.investimento.app.repository.QuoteHistoryRepositoryImpl;
import com.investimento.app.repository.RateHistoryRepository;
import com.investimento.app.repository.RateHistoryRepositoryImpl;
import com.investimento.app.repository.SettingRepository;
import com.investimento.app.repository.SettingRepositoryImpl;
import com.investimento.app.service.MarketService;
import com.investimento.app.service.MarketServiceImpl;
import javafx.application.Platform;
import javafx.concurrent.Task;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

/**
 * Teste manual da ATV-06 (cache local unificado). Roda contra um banco
 * SQLite em memoria (isolado do banco real do app, mesmo padrao do
 * {@link RepositoryManualTest} da ATV-02) e contra as 3 APIs reais (ATV-03/
 * 04/05), usando os contadores de requisicao ja expostos por cada
 * *ClientImpl para provar as regras de cache/agrupamento por contagem, nao
 * so "parece certo" (exigencia explicita do criterio de aceite).
 *
 * <p>Nao ha JUnit no projeto ainda (mesma decisao das atividades
 * anteriores) - este e um main() temporario.
 *
 * Executar (dentro de "LM Investimentos/", jars/target sem espaco no
 * caminho - gotcha da ATV-04/CLAUDE.md):
 *   mvn -q compile
 *   java -cp "&lt;target-classes-sem-espaco&gt;;&lt;sqlite-jdbc.jar&gt;;&lt;json.jar&gt;;&lt;javafx-jars&gt;" \
 *        --module-path &lt;javafx-sdk-lib&gt; --add-modules javafx.controls \
 *        com.investimento.app.data.MarketServiceManualTest
 */
public final class MarketServiceManualTest {

    private MarketServiceManualTest() {
    }

    public static void main(String[] args) throws Exception {
        // Task.updateProgress()/updateMessage() (usados dentro de
        // MarketServiceImpl) despacham via Platform.runLater - precisa do
        // toolkit JavaFX inicializado, que normalmente so existe porque o
        // app real chama Application.launch() (ATV-00/App.java). Este teste
        // de console nunca abre uma janela, entao inicializa o toolkit
        // "headless" com Platform.startup() antes de rodar qualquer Task.
        Platform.startup(() -> {
        });

        Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON;");
        }
        Database.bootstrapSchema(conn);

        AssetRepository assetRepository = new AssetRepositoryImpl(conn);
        QuoteHistoryRepository quoteHistoryRepository = new QuoteHistoryRepositoryImpl(conn);
        RateHistoryRepository rateHistoryRepository = new RateHistoryRepositoryImpl(conn);
        IndicatorHistoryRepository indicatorHistoryRepository = new IndicatorHistoryRepositoryImpl(conn);
        SettingRepository settingRepository = new SettingRepositoryImpl(conn);

        HgBrasilClientImpl hgBrasilClient = new HgBrasilClientImpl();
        BrapiClientImpl brapiClient = new BrapiClientImpl();
        CoinGeckoClientImpl coinGeckoClient = new CoinGeckoClientImpl();

        MarketService marketService = new MarketServiceImpl(
                hgBrasilClient, brapiClient, coinGeckoClient,
                rateHistoryRepository, indicatorHistoryRepository, quoteHistoryRepository, settingRepository);

        // ---- Cenario 2: getMacroSnapshot() duas vezes seguidas -> 2a nao gera requisicao nova ----
        System.out.println("=== Cenario 2: cache de getMacroSnapshot() (TTL 5 min) ===");
        int hgBefore = hgBrasilClient.getRequestCount();
        MacroSnapshot snap1 = marketService.getMacroSnapshot();
        int hgAfterFirst = hgBrasilClient.getRequestCount();
        check(snap1 != null, "1a chamada deveria retornar snapshot nao-nulo (API real disponivel)");
        check(hgAfterFirst - hgBefore == 1, "1a chamada deveria gerar exatamente 1 requisicao HG Brasil");

        MacroSnapshot snap2 = marketService.getMacroSnapshot();
        int hgAfterSecond = hgBrasilClient.getRequestCount();
        check(hgAfterSecond == hgAfterFirst, "2a chamada dentro do TTL NAO deveria gerar requisicao nova");
        check(snap1 == snap2, "2a chamada deveria devolver a mesma instancia cacheada");

        // Confirma que o CDI/SELIC do dia foi persistido em rate_history.
        check(rateHistoryRepository.findMostRecent().isPresent(),
                "getMacroSnapshot() deveria ter persistido o CDI/SELIC do dia em rate_history");

        System.out.println();
        System.out.println("=== Cenario 2b: cache de getIndicators() (TTL 6h) ===");
        int hgBeforeInd = hgBrasilClient.getRequestCount();
        Map<String, ?> ind1 = marketService.getIndicators();
        int hgAfterIndFirst = hgBrasilClient.getRequestCount();
        check(!ind1.isEmpty(), "1a chamada de getIndicators() deveria retornar series nao vazias");
        check(hgAfterIndFirst - hgBeforeInd == 1, "1a chamada de getIndicators() deveria gerar exatamente 1 requisicao");

        Map<String, ?> ind2 = marketService.getIndicators();
        int hgAfterIndSecond = hgBrasilClient.getRequestCount();
        check(hgAfterIndSecond == hgAfterIndFirst, "2a chamada de getIndicators() dentro do TTL NAO deveria gerar requisicao nova");
        check(ind1 == ind2, "2a chamada de getIndicators() deveria devolver a mesma instancia cacheada");
        check(!indicatorHistoryRepository.listByTicker("IBGE:IPCA").isEmpty(),
                "getIndicators() deveria ter persistido a serie do IPCA em indicator_history");

        // ---- Cenario 3: updateQuotes com lista mista (1 brapi + 2 cripto) ----
        System.out.println();
        System.out.println("=== Cenario 3: updateQuotes([1 BRAPI, 2 COINGECKO]) - agrupamento por fonte ===");
        Asset stockAsset = assetRepository.insert(Asset.builder()
                .type(AssetType.STOCK)
                .category(Category.STOCKS)
                .ticker("PETR4")
                .displayName("Petrobras PN")
                .currency("BRL")
                .quoteSource(QuoteSource.BRAPI)
                .sourceIdentifier("PETR4")
                .active(true)
                .build());
        Asset btcAsset = assetRepository.insert(Asset.builder()
                .type(AssetType.CRYPTO)
                .category(Category.CRYPTO)
                .ticker("BTC")
                .displayName("Bitcoin")
                .currency("BRL")
                .quoteSource(QuoteSource.COINGECKO)
                .sourceIdentifier("BTC")
                .active(true)
                .build());
        Asset ethAsset = assetRepository.insert(Asset.builder()
                .type(AssetType.CRYPTO)
                .category(Category.CRYPTO)
                .ticker("ETH")
                .displayName("Ethereum")
                .currency("BRL")
                .quoteSource(QuoteSource.COINGECKO)
                .sourceIdentifier("ETH")
                .active(true)
                .build());

        int brapiBefore = brapiClient.getRequestCount();
        int cgBefore = coinGeckoClient.getRequestCount();

        List<Asset> mixed = List.of(stockAsset, btcAsset, ethAsset);
        Task<Void> updateTask = marketService.updateQuotes(mixed);
        runTaskOnThreadAndWait(updateTask);

        int brapiUsed = brapiClient.getRequestCount() - brapiBefore;
        int cgUsed = coinGeckoClient.getRequestCount() - cgBefore;
        System.out.println("Requisicoes brapi usadas (esperado 1, 1 ticker): " + brapiUsed);
        System.out.println("Requisicoes CoinGecko usadas (esperado 1, para os 2 cripto juntos): " + cgUsed);
        check(brapiUsed == 1, "updateQuotes deveria gerar exatamente 1 requisicao brapi (1 ticker BRAPI)");
        check(cgUsed == 1, "updateQuotes deveria agrupar os 2 ativos COINGECKO numa unica requisicao");

        check(!quoteHistoryRepository.listByAsset(stockAsset.getId()).isEmpty(),
                "quote_history deveria ter 1 linha nova para o ativo BRAPI");
        check(!quoteHistoryRepository.listByAsset(btcAsset.getId()).isEmpty(),
                "quote_history deveria ter 1 linha nova para o ativo BTC");
        check(!quoteHistoryRepository.listByAsset(ethAsset.getId()).isEmpty(),
                "quote_history deveria ter 1 linha nova para o ativo ETH");

        Map<Long, Double> dailyChanges = marketService.getDailyChanges(mixed);
        check(dailyChanges.containsKey(stockAsset.getId()) || dailyChanges.containsKey(btcAsset.getId()),
                "getDailyChanges() deveria trazer ao menos a variacao de algum ativo atualizado");
        System.out.println("Variacoes do dia capturadas em memoria: " + dailyChanges);

        // ---- Cenario 4: seedInitialHistory com ativo brapi ----
        System.out.println();
        System.out.println("=== Cenario 4: seedInitialHistory(ativo BRAPI) - historico completo de uma vez ===");
        Task<Void> seedTask = marketService.seedInitialHistory(stockAsset);
        runTaskOnThreadAndWait(seedTask);
        int rows = quoteHistoryRepository.listByAsset(stockAsset.getId()).size();
        System.out.println("Linhas em quote_history para PETR4 apos seed (esperado > 1): " + rows);
        check(rows > 1, "seedInitialHistory deveria gravar multiplas linhas de historico (range=max)");

        // ---- Resiliencia: chave HG Brasil invalida nao deve travar/lancar para quem chama ----
        System.out.println();
        System.out.println("=== Resiliencia: chave HG Brasil invalida nao quebra a chamada ===");
        MarketService brokenMarketService = new MarketServiceImpl(
                new HgBrasilClientImpl("CHAVE_INVALIDA_DE_PROPOSITO"),
                brapiClient, coinGeckoClient,
                rateHistoryRepository, indicatorHistoryRepository, quoteHistoryRepository, settingRepository);
        MacroSnapshot brokenSnapshot = brokenMarketService.getMacroSnapshot();
        System.out.println("getMacroSnapshot() com chave invalida devolveu: " + brokenSnapshot
                + " (esperado null - nunca teve cache valido, nao lancou excecao)");
        check(brokenSnapshot == null, "getMacroSnapshot() com chave invalida e sem cache previo deveria devolver null, sem lancar excecao");

        conn.close();
        System.out.println();
        System.out.println("MarketServiceManualTest: TODOS OS CENARIOS PASSARAM.");
        Platform.exit(); // encerra a thread do toolkit JavaFX para o processo terminar.
    }

    /**
     * {@link Task} precisa rodar fora da thread principal para provar que
     * {@code updateQuotes}/{@code seedInitialHistory} nao bloqueiam quem
     * chamou (RT06) - aqui simulamos isso com uma {@link Thread} simples
     * (mesma decisao permitida pela atividade: quem instancia/executa o
     * Task decide o mecanismo). Usa {@code Task.get()} (herdado de
     * {@code java.util.concurrent.FutureTask}) em vez de
     * {@code Task.getException()}/{@code getState()} porque estes ultimos
     * sao propriedades JavaFX que só podem ser lidas na FX Application
     * Thread — inexistente neste teste de console, que nunca chama
     * {@code Application.launch()}.
     */
    private static void runTaskOnThreadAndWait(Task<Void> task) throws Exception {
        Thread thread = new Thread(task, "market-service-task");
        thread.setDaemon(true);
        thread.start();
        task.get(); // bloqueia so o teste (nao a "UI") ate o Task terminar; propaga excecao de call(), se houver.
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("FALHOU: " + message);
        }
        System.out.println("OK: " + message);
    }
}
