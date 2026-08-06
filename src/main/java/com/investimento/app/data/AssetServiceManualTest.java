package com.investimento.app.data;

import com.investimento.app.api.brapi.BrapiClientImpl;
import com.investimento.app.api.brapi.BrapiException;
import com.investimento.app.api.coingecko.CoinGeckoClientImpl;
import com.investimento.app.api.hgbrasil.HgBrasilClientImpl;
import com.investimento.app.data.model.AssetType;
import com.investimento.app.data.model.Benchmark;
import com.investimento.app.data.model.Category;
import com.investimento.app.data.model.QuoteSource;
import com.investimento.app.dto.AssetDTO;
import com.investimento.app.dto.CreateAssetRequest;
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
import com.investimento.app.service.AssetService;
import com.investimento.app.service.AssetServiceImpl;
import com.investimento.app.service.MarketService;
import com.investimento.app.service.MarketServiceImpl;
import com.investimento.app.service.ValidationException;
import javafx.application.Platform;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;

/**
 * Teste manual da ATV-08 (CRUD de Ativos, regra de negócio) — roda contra um
 * banco SQLite em memória (mesmo padrão de {@link RepositoryManualTest}/
 * {@link MarketServiceManualTest}) e contra as APIs reais (brapi.dev,
 * CoinGecko), cobrindo os cenários do passo 3 e o critério de aceite da
 * atividade. Não há JUnit no projeto ainda — main() temporário, mesma decisão
 * das atividades anteriores.
 *
 * Executar (dentro de "LM Investimentos/", jars/target sem espaço no caminho
 * — gotcha das ATV-01 a 06/CLAUDE.md):
 *   mvn -q compile
 *   java -cp "&lt;target-classes-sem-espaco&gt;;&lt;sqlite-jdbc.jar&gt;;&lt;json.jar&gt;;&lt;javafx-jars&gt;" \
 *        --module-path &lt;javafx-sdk-lib&gt; --add-modules javafx.controls \
 *        com.investimento.app.data.AssetServiceManualTest
 */
public final class AssetServiceManualTest {

    private AssetServiceManualTest() {
    }

    public static void main(String[] args) throws Exception {
        // MarketServiceImpl.seedInitialHistory() usa Task.updateProgress()/
        // updateMessage(), que despacham via Platform.runLater - precisa do
        // toolkit JavaFX inicializado (mesmo gotcha da ATV-06).
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

        BrapiClientImpl brapiClient = new BrapiClientImpl();
        CoinGeckoClientImpl coinGeckoClient = new CoinGeckoClientImpl();
        HgBrasilClientImpl hgBrasilClient = new HgBrasilClientImpl();

        MarketService marketService = new MarketServiceImpl(
                hgBrasilClient, brapiClient, coinGeckoClient,
                rateHistoryRepository, indicatorHistoryRepository, quoteHistoryRepository, settingRepository);

        AssetService assetService = new AssetServiceImpl(assetRepository, brapiClient, coinGeckoClient, marketService);

        // ---- Cenario 1: acao valida (PETR4) -> STOCKS/BRAPI/"PETR4" ----
        System.out.println("=== Cenario 1: cadastrar STOCK valido (PETR4) ===");
        AssetDTO petr4 = assetService.create(new CreateAssetRequest(
                AssetType.STOCK, "PETR4", "Petrobras PN", null,
                null, null, null, null, null));
        check(petr4.id() != null, "PETR4 deveria ter id gerado");
        check(petr4.category() == Category.STOCKS, "PETR4 deveria cair em Category.STOCKS");
        check(petr4.quoteSource() == QuoteSource.BRAPI, "PETR4 deveria usar QuoteSource.BRAPI");
        check("PETR4".equals(petr4.sourceIdentifier()), "PETR4 deveria ter sourceIdentifier = 'PETR4'");
        check("PETR4".equals(petr4.ticker()), "PETR4 deveria manter o ticker resolvido 'PETR4'");

        // Confirma que seedInitialHistory foi disparado em BACKGROUND (nao
        // bloqueou create()): logo apos o retorno, ainda pode nao haver
        // historico gravado; espera um pouco e confirma que aparece depois.
        int rowsRightAfterCreate = quoteHistoryRepository.listByAsset(petr4.id()).size();
        System.out.println("Linhas em quote_history logo apos create() (nao deveria ja ter o historico completo): "
                + rowsRightAfterCreate);
        Thread.sleep(4000);
        int rowsAfterWait = quoteHistoryRepository.listByAsset(petr4.id()).size();
        System.out.println("Linhas em quote_history apos aguardar o seed em background: " + rowsAfterWait);
        check(rowsAfterWait > rowsRightAfterCreate, "seedInitialHistory deveria gravar historico em background, sem bloquear create()");

        // ---- Cenario 2: ticker inexistente -> ValidationException, sem NPE ----
        System.out.println();
        System.out.println("=== Cenario 2: STOCK com ticker inexistente (ZZZZ99) ===");
        try {
            assetService.create(new CreateAssetRequest(
                    AssetType.STOCK, "ZZZZ99", "Ativo Inexistente", null,
                    null, null, null, null, null));
            throw new AssertionError("FALHOU: deveria ter lancado excecao para ticker inexistente");
        } catch (ValidationException | BrapiException e) {
            System.out.println("OK: ticker inexistente rejeitado com " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
        }
        check(assetRepository.listAssets(true).stream().noneMatch(a -> "ZZZZ99".equals(a.getTicker())),
                "Ticker inexistente NAO deveria ter deixado lixo na tabela assets");

        // ---- Cenario 3: cripto valida (BTC) -> CRYPTO/COINGECKO ----
        System.out.println();
        System.out.println("=== Cenario 3: cadastrar CRYPTO valido (BTC) ===");
        AssetDTO btc = assetService.create(new CreateAssetRequest(
                AssetType.CRYPTO, "BTC", "Bitcoin", null,
                null, null, null, null, null));
        check(btc.category() == Category.CRYPTO, "BTC deveria cair em Category.CRYPTO");
        check(btc.quoteSource() == QuoteSource.COINGECKO, "BTC deveria usar QuoteSource.COINGECKO");
        check("BTC".equals(btc.sourceIdentifier()), "BTC deveria ter sourceIdentifier = 'BTC' (simbolo, nao id da CoinGecko)");

        // ---- Cenario 3b: cripto fora do mapa fixo -> ValidationException ----
        try {
            assetService.create(new CreateAssetRequest(
                    AssetType.CRYPTO, "MOEDA_INVENTADA", "Moeda Inventada", null,
                    null, null, null, null, null));
            throw new AssertionError("FALHOU: deveria ter rejeitado simbolo cripto fora do ID_MAP");
        } catch (ValidationException e) {
            System.out.println("OK: simbolo cripto fora do ID_MAP rejeitado: " + e.getMessage());
        }

        // ---- Cenario 4: renda fixa sem maturityDate -> ValidationException ----
        System.out.println();
        System.out.println("=== Cenario 4: FIXED_INCOME sem maturityDate ===");
        try {
            assetService.create(new CreateAssetRequest(
                    AssetType.FIXED_INCOME, null, "CDB Banco XP", null,
                    Benchmark.CDI, 110.0, "Banco XP", LocalDate.of(2026, 1, 1), null));
            throw new AssertionError("FALHOU: deveria ter rejeitado FIXED_INCOME sem maturityDate");
        } catch (ValidationException e) {
            System.out.println("OK: FIXED_INCOME sem maturityDate rejeitado: " + e.getMessage());
        }

        // Renda fixa valida, so para confirmar a 5a combinacao (Category.FIXED_INCOME/QuoteSource.NONE).
        AssetDTO cdb = assetService.create(new CreateAssetRequest(
                AssetType.FIXED_INCOME, null, "CDB Banco XP", null,
                Benchmark.CDI, 110.0, "Banco XP", LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1)));
        check(cdb.category() == Category.FIXED_INCOME, "CDB deveria cair em Category.FIXED_INCOME");
        check(cdb.quoteSource() == QuoteSource.NONE, "CDB deveria usar QuoteSource.NONE");
        check(cdb.sourceIdentifier() == null, "CDB nao deveria ter sourceIdentifier");

        // ---- Cenario 5 (extra, criterio de aceite): FII -> FIIS/BRAPI ----
        System.out.println();
        System.out.println("=== Cenario 5: cadastrar FII valido (MXRF11) ===");
        AssetDTO mxrf11 = assetService.create(new CreateAssetRequest(
                AssetType.FII, "MXRF11", "Maxi Renda FII", null,
                null, null, null, null, null));
        check(mxrf11.category() == Category.FIIS, "MXRF11 deveria cair em Category.FIIS");
        check(mxrf11.quoteSource() == QuoteSource.BRAPI, "MXRF11 deveria usar QuoteSource.BRAPI");

        // ---- Cenario 6 (extra, criterio de aceite): FOREIGN_CURRENCY -> FOREX/HGBRASIL ----
        System.out.println();
        System.out.println("=== Cenario 6: cadastrar FOREIGN_CURRENCY valido (USD) ===");
        AssetDTO usd = assetService.create(new CreateAssetRequest(
                AssetType.FOREIGN_CURRENCY, null, "Dolar Americano", "usd",
                null, null, null, null, null));
        check(usd.category() == Category.FOREX, "USD deveria cair em Category.FOREX");
        check(usd.quoteSource() == QuoteSource.HGBRASIL, "USD deveria usar QuoteSource.HGBRASIL");
        check("FOREX:USDBRL".equals(usd.sourceIdentifier()), "USD deveria ter sourceIdentifier = 'FOREX:USDBRL'");

        // Moeda fora do conjunto de 8 codigos suportados -> ValidationException.
        try {
            assetService.create(new CreateAssetRequest(
                    AssetType.FOREIGN_CURRENCY, null, "Peso Mexicano", "MXN",
                    null, null, null, null, null));
            throw new AssertionError("FALHOU: deveria ter rejeitado moeda MXN (fora dos 8 codigos suportados)");
        } catch (ValidationException e) {
            System.out.println("OK: moeda fora do conjunto suportado rejeitada: " + e.getMessage());
        }

        // ---- Cenario 7: remover um ativo (soft delete) ----
        System.out.println();
        System.out.println("=== Cenario 7: remover ativo (soft delete) ===");
        assetService.remove(petr4.id());
        List<AssetDTO> activeOnly = assetService.listAssets(false);
        List<AssetDTO> includingInactive = assetService.listAssets(true);
        check(activeOnly.stream().noneMatch(a -> a.id().equals(petr4.id())),
                "listAssets(false) NAO deveria trazer o ativo removido");
        check(includingInactive.stream().anyMatch(a -> a.id().equals(petr4.id())),
                "listAssets(true) deveria trazer o ativo removido (soft delete, nao apaga a linha)");
        check(assetService.findById(petr4.id()).isPresent(),
                "findById deveria continuar encontrando o ativo removido (nao foi um DELETE fisico)");

        conn.close();
        System.out.println();
        System.out.println("AssetServiceManualTest: TODOS OS CENARIOS PASSARAM.");
        Platform.exit();
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("FALHOU: " + message);
        }
        System.out.println("OK: " + message);
    }
}
