package com.investimento.app.data;

import com.investimento.app.api.brapi.BrapiClientImpl;
import com.investimento.app.api.coingecko.CoinGeckoClientImpl;
import com.investimento.app.api.hgbrasil.HgBrasilClientImpl;
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

/**
 * Teste manual (main(), sem JUnit — mesma decisão das ATV-01 a 19) do
 * critério de aceite da ATV-18 sobre o intervalo de atualização configurável:
 * "mudar o intervalo de atualização reflete no comportamento real do
 * MarketService (testar com um valor bem baixo... e confirmar que uma
 * segunda leitura antes do intervalo ainda usa cache, e depois do intervalo
 * busca de novo)".
 *
 * <p><b>Desvio deliberado e documentado do "1 minuto" citado literalmente
 * pela atividade</b>: para manter este teste automatizado rápido (sem exigir
 * um {@code Thread.sleep} de 60+ segundos), o intervalo configurado é uma
 * fração de minuto (frações são aceitas por {@code
 * MarketServiceImpl.configuredQuoteTtl}, mesmo a UI só oferecendo minutos
 * inteiros — ver Javadoc do método) — o comportamento testado é o mesmo:
 * "antes do intervalo configurado, usa cache; depois dele, busca de novo".
 * Roda contra a API real da brapi.dev (1 ticker, PETR4).</p>
 */
public final class MarketServiceUpdateIntervalManualTest {

    private MarketServiceUpdateIntervalManualTest() {
    }

    public static void main(String[] args) throws Exception {
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
        MarketService marketService = new MarketServiceImpl(
                new HgBrasilClientImpl(), brapiClient, new CoinGeckoClientImpl(),
                rateHistoryRepository, indicatorHistoryRepository, quoteHistoryRepository, settingRepository);

        Asset petr4 = assetRepository.insert(Asset.builder()
                .type(AssetType.STOCK)
                .category(Category.STOCKS)
                .ticker("PETR4")
                .displayName("Petrobras PN")
                .currency("BRL")
                .quoteSource(QuoteSource.BRAPI)
                .sourceIdentifier("PETR4")
                .active(true)
                .build());
        List<Asset> assets = List.of(petr4);

        // Configura um intervalo bem baixo (3 segundos = 0.05 min) — settings.updateIntervalMinutes,
        // exatamente a chave que a tela de Configuracoes (SettingsView) grava.
        double intervalMinutes = 0.05; // 3 segundos
        settingRepository.save("updateIntervalMinutes", String.valueOf(intervalMinutes));

        System.out.println("=== Cenario: updateIntervalMinutes=" + intervalMinutes + " (3s) sobrescreve o TTL padrao (15 min) da BRAPI ===");

        int before1 = brapiClient.getRequestCount();
        runTaskOnThreadAndWait(marketService.updateQuotes(assets));
        int after1 = brapiClient.getRequestCount();
        check(after1 - before1 == 1, "1a chamada a updateQuotes deveria gerar exatamente 1 requisicao brapi");

        // 2a leitura, imediatamente depois - ainda dentro dos 3s configurados -> NAO deveria gerar requisicao nova.
        runTaskOnThreadAndWait(marketService.updateQuotes(assets));
        int after2 = brapiClient.getRequestCount();
        check(after2 == after1, "2a chamada dentro do intervalo configurado (3s) NAO deveria gerar requisicao nova, veio "
                + (after2 - after1) + " requisicao(oes) a mais");

        // Espera passar do intervalo configurado (3s) e tenta de novo -> DEVE buscar de novo.
        System.out.println("Aguardando " + (intervalMinutes * 60_000 + 500) + "ms para passar do intervalo configurado...");
        Thread.sleep((long) (intervalMinutes * 60_000) + 500);
        runTaskOnThreadAndWait(marketService.updateQuotes(assets));
        int after3 = brapiClient.getRequestCount();
        check(after3 - after2 == 1, "3a chamada, apos o intervalo configurado expirar, deveria gerar 1 nova requisicao brapi, veio "
                + (after3 - after2));

        conn.close();
        System.out.println();
        System.out.println("MarketServiceUpdateIntervalManualTest: TODOS OS CENARIOS PASSARAM.");
        Platform.exit();
    }

    private static void runTaskOnThreadAndWait(Task<Void> task) throws Exception {
        Thread thread = new Thread(task, "market-service-interval-task");
        thread.setDaemon(true);
        thread.start();
        task.get();
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("FALHOU: " + message);
        }
        System.out.println("OK: " + message);
    }
}
