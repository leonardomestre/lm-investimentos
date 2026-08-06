package com.investimento.app.data;

import com.investimento.app.api.hgbrasil.model.Currency;
import com.investimento.app.api.hgbrasil.model.IndicatorPoint;
import com.investimento.app.api.hgbrasil.model.MacroSnapshot;
import com.investimento.app.data.model.Asset;
import com.investimento.app.data.model.AssetType;
import com.investimento.app.data.model.Benchmark;
import com.investimento.app.data.model.Category;
import com.investimento.app.data.model.OperationType;
import com.investimento.app.data.model.QuoteHistory;
import com.investimento.app.data.model.QuoteSource;
import com.investimento.app.data.model.Transaction;
import com.investimento.app.dto.PortfolioSummary;
import com.investimento.app.dto.Position;
import com.investimento.app.dto.RealizedSale;
import com.investimento.app.repository.AssetRepository;
import com.investimento.app.repository.AssetRepositoryImpl;
import com.investimento.app.repository.IndicatorHistoryRepository;
import com.investimento.app.repository.IndicatorHistoryRepositoryImpl;
import com.investimento.app.repository.QuoteHistoryRepository;
import com.investimento.app.repository.QuoteHistoryRepositoryImpl;
import com.investimento.app.repository.RateHistoryRepository;
import com.investimento.app.repository.RateHistoryRepositoryImpl;
import com.investimento.app.repository.TransactionRepository;
import com.investimento.app.repository.TransactionRepositoryImpl;
import com.investimento.app.service.MarketService;
import com.investimento.app.service.PositionService;
import com.investimento.app.service.PositionServiceImpl;
import javafx.concurrent.Task;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Teste manual da ATV-10 (Cálculo de ganho/perda) — roda contra um banco
 * SQLite em memória (mesmo padrão de {@link RepositoryManualTest}/{@link
 * AssetServiceManualTest}/{@link TransactionServiceManualTest}), sem tocar
 * rede: {@link MarketService} é um stub fixo aqui (taxa de câmbio USD/BRL
 * fixa em 5,30), já que {@code PositionService} só precisa de {@code
 * getMacroSnapshot()} para converter posições em moeda estrangeira - não há
 * necessidade de bater na API real da HG Brasil para validar o algoritmo de
 * custo médio/valor atual desta atividade. Não há JUnit no projeto ainda -
 * main() temporário, mesma decisão das atividades anteriores.
 *
 * Executar (dentro de "LM Investimentos/", jars/target sem espaço no caminho
 * - gotcha das ATV-01 a 09/CLAUDE.md; jars do JavaFX (base/graphics/controls
 * + variantes -win) necessários porque MarketService/PositionService usam
 * javafx.concurrent.Task na assinatura):
 *   mvn -q compile
 *   java -cp "&lt;target-classes-sem-espaco&gt;;&lt;sqlite-jdbc.jar&gt;;&lt;mapstruct.jar&gt;;&lt;json.jar&gt;;&lt;javafx-jars&gt;" \
 *        com.investimento.app.data.PositionServiceManualTest
 */
public final class PositionServiceManualTest {

    private static final double EPSILON = 0.01;

    private PositionServiceManualTest() {
    }

    public static void main(String[] args) throws Exception {
        Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON;");
        }
        Database.bootstrapSchema(conn);

        AssetRepository assetRepository = new AssetRepositoryImpl(conn);
        TransactionRepository transactionRepository = new TransactionRepositoryImpl(conn);
        QuoteHistoryRepository quoteHistoryRepository = new QuoteHistoryRepositoryImpl(conn);
        RateHistoryRepository rateHistoryRepository = new RateHistoryRepositoryImpl(conn);
        IndicatorHistoryRepository indicatorHistoryRepository = new IndicatorHistoryRepositoryImpl(conn);
        MarketService marketService = new FakeMarketService();

        PositionService positionService = new PositionServiceImpl(
                assetRepository, transactionRepository, marketService,
                quoteHistoryRepository, rateHistoryRepository, indicatorHistoryRepository);

        // ================= Cenário 1: STOCK, 2 compras + 1 venda parcial =================
        System.out.println("=== Cenário 1: preço médio com 2 compras + 1 venda parcial ===");
        Asset petr4 = assetRepository.insert(Asset.builder()
                .type(AssetType.STOCK).category(Category.STOCKS).ticker("PETR4")
                .displayName("Petrobras PN").currency("BRL")
                .quoteSource(QuoteSource.BRAPI).sourceIdentifier("PETR4")
                .active(true).build());

        transactionRepository.insert(Transaction.builder()
                .assetId(petr4.getId()).operationType(OperationType.BUY)
                .date(LocalDate.of(2026, 1, 10)).quantity(100).unitPrice(30.0).fees(5.0).build());
        transactionRepository.insert(Transaction.builder()
                .assetId(petr4.getId()).operationType(OperationType.BUY)
                .date(LocalDate.of(2026, 2, 10)).quantity(50).unitPrice(32.0).fees(3.0).build());
        Transaction sell = transactionRepository.insert(Transaction.builder()
                .assetId(petr4.getId()).operationType(OperationType.SELL)
                .date(LocalDate.of(2026, 3, 10)).quantity(40).unitPrice(35.0).fees(5.0).build());

        quoteHistoryRepository.upsert(QuoteHistory.builder()
                .assetId(petr4.getId()).date(LocalDate.now()).price(40.0).source(QuoteSource.BRAPI).build());

        // Cálculo manual: accumulatedCost = (100*30+5) + (50*32+3) = 3005 + 1603 = 4608, qty=150
        // venda de 40 a preço médio 4608/150=30.72 -> accumulatedCost -= 30.72*40=1228.8 -> 3379.2, qty=110
        double expectedAveragePrice = 4608.0 / 150.0; // = 30.72 (preço médio não muda numa venda)
        double expectedInvestedValue = 4608.0 - expectedAveragePrice * 40;
        double expectedCurrentQuantity = 110;
        double expectedCurrentValue = 40.0 * 110; // preço de mercado * quantidade
        double expectedGainLossAmount = expectedCurrentValue - expectedInvestedValue;
        double expectedGainLossPercent = expectedGainLossAmount / expectedInvestedValue * 100;

        Position petr4Position = positionService.calculatePosition(petr4.getId());
        check(close(petr4Position.currentQuantity(), expectedCurrentQuantity),
                "currentQuantity esperado " + expectedCurrentQuantity + ", veio " + petr4Position.currentQuantity());
        check(close(petr4Position.averagePrice(), expectedAveragePrice),
                "averagePrice esperado " + expectedAveragePrice + ", veio " + petr4Position.averagePrice());
        check(close(petr4Position.investedValue(), expectedInvestedValue),
                "investedValue esperado " + expectedInvestedValue + ", veio " + petr4Position.investedValue());
        check(close(petr4Position.currentValue(), expectedCurrentValue),
                "currentValue esperado " + expectedCurrentValue + ", veio " + petr4Position.currentValue());
        check(close(petr4Position.gainLossAmount(), expectedGainLossAmount),
                "gainLossAmount esperado " + expectedGainLossAmount + ", veio " + petr4Position.gainLossAmount());
        check(close(petr4Position.gainLossPercent(), expectedGainLossPercent),
                "gainLossPercent esperado " + expectedGainLossPercent + ", veio " + petr4Position.gainLossPercent());

        // ================= Cenário 1b: só compras (preço médio simples) =================
        System.out.println();
        System.out.println("=== Cenário 1b: preço médio só com compras ===");
        Asset vale3 = assetRepository.insert(Asset.builder()
                .type(AssetType.STOCK).category(Category.STOCKS).ticker("VALE3")
                .displayName("Vale ON").currency("BRL")
                .quoteSource(QuoteSource.BRAPI).sourceIdentifier("VALE3")
                .active(true).build());
        transactionRepository.insert(Transaction.builder()
                .assetId(vale3.getId()).operationType(OperationType.BUY)
                .date(LocalDate.of(2026, 1, 5)).quantity(10).unitPrice(60.0).fees(2.0).build());
        transactionRepository.insert(Transaction.builder()
                .assetId(vale3.getId()).operationType(OperationType.BUY)
                .date(LocalDate.of(2026, 2, 5)).quantity(10).unitPrice(64.0).fees(2.0).build());
        quoteHistoryRepository.upsert(QuoteHistory.builder()
                .assetId(vale3.getId()).date(LocalDate.now()).price(70.0).source(QuoteSource.BRAPI).build());
        double expectedValeAverage = ((10 * 60.0 + 2.0) + (10 * 64.0 + 2.0)) / 20.0;
        Position valePosition = positionService.calculatePosition(vale3.getId());
        check(close(valePosition.averagePrice(), expectedValeAverage),
                "VALE3 averagePrice esperado " + expectedValeAverage + ", veio " + valePosition.averagePrice());
        check(close(valePosition.currentQuantity(), 20),
                "VALE3 currentQuantity esperado 20, veio " + valePosition.currentQuantity());

        // ================= Cenário 2: renda fixa (FIXED_RATE, 12% a.a., 6 meses) =================
        System.out.println();
        System.out.println("=== Cenário 2: renda fixa FIXED_RATE 12% a.a., aplicação há 6 meses ===");
        LocalDate investmentDate = LocalDate.now().minusMonths(6);
        Asset cdb = assetRepository.insert(Asset.builder()
                .type(AssetType.FIXED_INCOME).category(Category.FIXED_INCOME)
                .displayName("CDB Banco X").currency("BRL")
                .quoteSource(QuoteSource.NONE)
                .benchmark(Benchmark.FIXED_RATE).contractedRatePct(12.0)
                .financialInstitution("Banco X")
                .investmentDate(investmentDate).maturityDate(investmentDate.plusYears(2))
                .active(true).build());
        transactionRepository.insert(Transaction.builder()
                .assetId(cdb.getId()).operationType(OperationType.BUY)
                .date(investmentDate).quantity(1).unitPrice(10000.0).fees(0).build());

        long elapsedDays = java.time.temporal.ChronoUnit.DAYS.between(investmentDate, LocalDate.now());
        double elapsedYears = elapsedDays / 365.0;
        double expectedCdbValue = 10000.0 * Math.pow(1.12, elapsedYears);

        Position cdbPosition = positionService.calculatePosition(cdb.getId());
        check(close(cdbPosition.investedValue(), 10000.0),
                "CDB investedValue esperado 10000, veio " + cdbPosition.investedValue());
        check(cdbPosition.currentValue() > cdbPosition.investedValue(),
                "CDB currentValue deveria ser maior que investedValue (taxa positiva, tempo decorrido positivo) - "
                        + "currentValue=" + cdbPosition.currentValue() + " investedValue=" + cdbPosition.investedValue());
        check(close(cdbPosition.currentValue(), expectedCdbValue),
                "CDB currentValue esperado (juros compostos, calculado à mão) " + expectedCdbValue
                        + ", veio " + cdbPosition.currentValue());

        // ================= Cenário 3: currentQuantity == 0 (vendeu tudo) - sem NaN/exceção =================
        System.out.println();
        System.out.println("=== Cenário 3: currentQuantity == 0 (vendeu tudo), sem divisão por zero ===");
        Asset mglu3 = assetRepository.insert(Asset.builder()
                .type(AssetType.STOCK).category(Category.STOCKS).ticker("MGLU3")
                .displayName("Magazine Luiza ON").currency("BRL")
                .quoteSource(QuoteSource.BRAPI).sourceIdentifier("MGLU3")
                .active(true).build());
        transactionRepository.insert(Transaction.builder()
                .assetId(mglu3.getId()).operationType(OperationType.BUY)
                .date(LocalDate.of(2026, 1, 1)).quantity(100).unitPrice(5.0).fees(1.0).build());
        transactionRepository.insert(Transaction.builder()
                .assetId(mglu3.getId()).operationType(OperationType.SELL)
                .date(LocalDate.of(2026, 2, 1)).quantity(100).unitPrice(6.0).fees(1.0).build());
        // Sem quote_history nenhuma para este ativo - também não deve gerar NaN.
        Position mgluPosition = positionService.calculatePosition(mglu3.getId());
        check(mgluPosition.currentQuantity() == 0, "MGLU3 currentQuantity deveria ser 0, veio " + mgluPosition.currentQuantity());
        check(mgluPosition.averagePrice() == 0, "MGLU3 averagePrice deveria ser 0 (sem NaN), veio " + mgluPosition.averagePrice());
        check(close(mgluPosition.investedValue(), 0),
                "MGLU3 investedValue deveria ser ~0, veio " + mgluPosition.investedValue());
        check(!Double.isNaN(mgluPosition.gainLossAmount()) && !Double.isNaN(mgluPosition.gainLossPercent()),
                "MGLU3 gainLossAmount/gainLossPercent nao deveriam ser NaN");
        check(mgluPosition.gainLossPercent() == 0,
                "MGLU3 gainLossPercent deveria ser 0 (investedValue ~0), veio " + mgluPosition.gainLossPercent());

        // calculateAllPositions(false) deve omitir MGLU3 (currentQuantity == 0); (true) deve incluir.
        List<Position> withoutZeroed = positionService.calculateAllPositions(false);
        List<Position> withZeroed = positionService.calculateAllPositions(true);
        check(withoutZeroed.stream().noneMatch(p -> "MGLU3".equals(p.asset().ticker())),
                "calculateAllPositions(false) nao deveria incluir MGLU3 (currentQuantity == 0)");
        check(withZeroed.stream().anyMatch(p -> "MGLU3".equals(p.asset().ticker())),
                "calculateAllPositions(true) deveria incluir MGLU3");

        // ================= Cenário 4: câmbio (FOREX) - moeda diferente na carteira =================
        System.out.println();
        System.out.println("=== Cenário 4: câmbio (FOREX, USD) ===");
        Asset usd = assetRepository.insert(Asset.builder()
                .type(AssetType.FOREIGN_CURRENCY).category(Category.FOREX)
                .displayName("Dólar").currency("USD")
                .quoteSource(QuoteSource.HGBRASIL).sourceIdentifier("FOREX:USDBRL")
                .active(true).build());
        transactionRepository.insert(Transaction.builder()
                .assetId(usd.getId()).operationType(OperationType.BUY)
                .date(LocalDate.of(2026, 1, 1)).quantity(1000).unitPrice(5.20).fees(10.0).build());
        Position usdPosition = positionService.calculatePosition(usd.getId());
        double expectedUsdInvested = 1000 * 5.20 + 10.0;
        double expectedUsdCurrent = 1000 * 5.30; // taxa fixa do FakeMarketService
        check(close(usdPosition.investedValue(), expectedUsdInvested),
                "USD investedValue esperado " + expectedUsdInvested + ", veio " + usdPosition.investedValue());
        check(close(usdPosition.currentValue(), expectedUsdCurrent),
                "USD currentValue esperado " + expectedUsdCurrent + " (quantidade * taxa de compra), veio "
                        + usdPosition.currentValue());

        // ================= Cenário 4b: ativo cotado em moeda estrangeira (ex.: cripto em USD) =================
        System.out.println();
        System.out.println("=== Cenário 4b: ativo não-FOREX com currency=USD, currentValue convertido para BRL ===");
        Asset cryptoUsd = assetRepository.insert(Asset.builder()
                .type(AssetType.CRYPTO).category(Category.CRYPTO)
                .ticker("XBT").displayName("Bitcoin (cotado em USD, hipotético)").currency("USD")
                .quoteSource(QuoteSource.COINGECKO).sourceIdentifier("BTC")
                .active(true).build());
        transactionRepository.insert(Transaction.builder()
                .assetId(cryptoUsd.getId()).operationType(OperationType.BUY)
                .date(LocalDate.of(2026, 1, 1)).quantity(2).unitPrice(90.0).fees(0).build());
        quoteHistoryRepository.upsert(QuoteHistory.builder()
                .assetId(cryptoUsd.getId()).date(LocalDate.now()).price(100.0).source(QuoteSource.COINGECKO).build());
        Position cryptoUsdPosition = positionService.calculatePosition(cryptoUsd.getId());
        double expectedCryptoUsdCurrent = 2 * 100.0 * 5.30; // quantidade * preço nativo * taxa de câmbio
        check(close(cryptoUsdPosition.currentValue(), expectedCryptoUsdCurrent),
                "ativo currency=USD: currentValue esperado " + expectedCryptoUsdCurrent + " (convertido p/ BRL), veio "
                        + cryptoUsdPosition.currentValue());

        // ================= Cenário 5: PortfolioSummary soma categorias/moedas diferentes =================
        System.out.println();
        System.out.println("=== Cenário 5: PortfolioSummary soma posições de categorias/moedas diferentes ===");
        PortfolioSummary summary = positionService.calculatePortfolioSummary();
        List<Position> allPositions = positionService.calculateAllPositions(false);
        double expectedTotalInvested = allPositions.stream().mapToDouble(Position::investedValue).sum();
        double expectedTotalCurrent = allPositions.stream().mapToDouble(Position::currentValue).sum();
        check(close(summary.totalInvestedValue(), expectedTotalInvested),
                "totalInvestedValue esperado " + expectedTotalInvested + ", veio " + summary.totalInvestedValue());
        check(close(summary.totalCurrentValue(), expectedTotalCurrent),
                "totalCurrentValue esperado " + expectedTotalCurrent + ", veio " + summary.totalCurrentValue());
        // Confirma que a soma NAO é feita ingenuamente sem converter (ex.: se USD/cryptoUsd nao fossem
        // convertidos, o total ficaria bem menor que o esperado aqui).
        check(summary.totalCurrentValue() > (petr4Position.currentValue() + valePosition.currentValue() + cdbPosition.currentValue()),
                "totalCurrentValue deveria incluir tambem as posicoes convertidas de USD (FOREX + cripto-USD)");
        System.out.println("PortfolioSummary: totalInvested=" + summary.totalInvestedValue()
                + " totalCurrent=" + summary.totalCurrentValue()
                + " gainLossAmount=" + summary.gainLossAmount()
                + " gainLossPercent=" + summary.gainLossPercent()
                + " cdiReturnPct=" + summary.cdiReturnPct() + " (rate_history vazio -> esperado 0)");
        check(summary.cdiReturnPct() == 0,
                "cdiReturnPct deveria ser 0 (rate_history vazio, sem lancar excecao), veio " + summary.cdiReturnPct());

        // ================= Cenário 6: calculateRealizedSales =================
        System.out.println();
        System.out.println("=== Cenário 6: calculateRealizedSales (mesmo cenário do PETR4) ===");
        List<RealizedSale> realizedSales = positionService.calculateRealizedSales(petr4.getId());
        check(realizedSales.size() == 1, "PETR4 deveria ter 1 venda realizada, veio " + realizedSales.size());
        RealizedSale realizedSale = realizedSales.get(0);
        double expectedAveragePriceAtSale = 4608.0 / 150.0;
        double expectedRealizedGain = (35.0 - expectedAveragePriceAtSale) * 40 - 5.0;
        check(realizedSale.transactionId() == sell.getId(),
                "transactionId esperado " + sell.getId() + ", veio " + realizedSale.transactionId());
        check(realizedSale.asset().ticker().equals("PETR4"), "asset da venda deveria ser PETR4");
        check(close(realizedSale.quantity(), 40), "quantity esperado 40, veio " + realizedSale.quantity());
        check(close(realizedSale.salePrice(), 35.0), "salePrice esperado 35.0, veio " + realizedSale.salePrice());
        check(close(realizedSale.averagePriceAtSale(), expectedAveragePriceAtSale),
                "averagePriceAtSale esperado " + expectedAveragePriceAtSale + ", veio " + realizedSale.averagePriceAtSale());
        check(close(realizedSale.fees(), 5.0), "fees esperado 5.0, veio " + realizedSale.fees());
        check(close(realizedSale.realizedGainAmount(), expectedRealizedGain),
                "realizedGainAmount esperado " + expectedRealizedGain + " (calculado à mão), veio "
                        + realizedSale.realizedGainAmount());

        // ================= Cenário 7: IPCA sem indicator_history (tabela vazia) - sem exceção =================
        System.out.println();
        System.out.println("=== Cenário 7: renda fixa IPCA sem indicator_history - trata como 0% + spread, sem exceção ===");
        Asset tesouroIpca = assetRepository.insert(Asset.builder()
                .type(AssetType.FIXED_INCOME).category(Category.FIXED_INCOME)
                .displayName("Tesouro IPCA+").currency("BRL")
                .quoteSource(QuoteSource.NONE)
                .benchmark(Benchmark.IPCA).contractedRatePct(6.0)
                .financialInstitution("Tesouro Direto")
                .investmentDate(LocalDate.now().minusMonths(3)).maturityDate(LocalDate.now().plusYears(5))
                .active(true).build());
        transactionRepository.insert(Transaction.builder()
                .assetId(tesouroIpca.getId()).operationType(OperationType.BUY)
                .date(LocalDate.now().minusMonths(3)).quantity(1).unitPrice(1000.0).fees(0).build());
        Position tesouroPosition = positionService.calculatePosition(tesouroIpca.getId());
        check(!Double.isNaN(tesouroPosition.currentValue()) && tesouroPosition.currentValue() > 0,
                "Tesouro IPCA+ nao deveria lancar excecao/NaN mesmo com indicator_history vazio, currentValue="
                        + tesouroPosition.currentValue());
        check(tesouroPosition.currentValue() > tesouroPosition.investedValue(),
                "Tesouro IPCA+ deveria valorizar so com o spread de 6% mesmo com IPCA tratado como 0%");

        conn.close();
        System.out.println();
        System.out.println("PositionServiceManualTest: TODOS OS CENARIOS PASSARAM.");
    }

    private static boolean close(double actual, double expected) {
        return Math.abs(actual - expected) < EPSILON;
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("FALHOU: " + message);
        }
        System.out.println("OK: " + message);
    }

    /**
     * Stub de {@link MarketService} - só {@code getMacroSnapshot()} é
     * exercitado por {@code PositionServiceImpl} (conversão de câmbio); os
     * demais métodos não são chamados nesta atividade, ficam com
     * implementação trivial só para satisfazer a interface.
     */
    private static class FakeMarketService implements MarketService {
        private final MacroSnapshot fixedSnapshot = new MacroSnapshot(
                Map.of("USD", new Currency("USD", "Dollar", 5.30, null, 0.0)),
                Map.of(),
                null
        );

        @Override
        public MacroSnapshot getMacroSnapshot() {
            return fixedSnapshot;
        }

        @Override
        public Map<String, List<IndicatorPoint>> getIndicators() {
            return Map.of();
        }

        // Fake sem cache nem rede: a versao cacheada e a mesma resposta fixa.
        @Override
        public MacroSnapshot getCachedMacroSnapshot() {
            return getMacroSnapshot();
        }

        @Override
        public Map<String, List<IndicatorPoint>> getCachedIndicators() {
            return getIndicators();
        }

        @Override
        public Task<Void> updateQuotes(List<Asset> assets) {
            return new Task<>() {
                @Override
                protected Void call() {
                    return null;
                }
            };
        }

        @Override
        public Task<Void> seedInitialHistory(Asset asset) {
            return new Task<>() {
                @Override
                protected Void call() {
                    return null;
                }
            };
        }

        @Override
        public Map<Long, Double> getDailyChanges(List<Asset> assets) {
            return Map.of();
        }

        @Override
        public java.util.Optional<String> getLastFailure() {
            return java.util.Optional.empty();
        }

        @Override
        public List<com.investimento.app.dto.SyncEvent> getRecentSyncs() {
            return List.of();
        }
    }
}
