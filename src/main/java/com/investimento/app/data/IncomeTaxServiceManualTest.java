package com.investimento.app.data;

import com.investimento.app.data.model.Asset;
import com.investimento.app.data.model.AssetType;
import com.investimento.app.data.model.Category;
import com.investimento.app.data.model.OperationType;
import com.investimento.app.data.model.QuoteSource;
import com.investimento.app.data.model.Transaction;
import com.investimento.app.dto.AnnualIncomeTaxSummary;
import com.investimento.app.dto.MonthlyTaxAssessment;
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
import com.investimento.app.service.IncomeTaxService;
import com.investimento.app.service.IncomeTaxServiceImpl;
import com.investimento.app.service.PositionService;
import com.investimento.app.service.PositionServiceImpl;
import javafx.concurrent.Task;
import com.investimento.app.api.hgbrasil.model.IndicatorPoint;
import com.investimento.app.api.hgbrasil.model.MacroSnapshot;
import com.investimento.app.data.model.Asset.AssetBuilder;
import com.investimento.app.service.MarketService;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/**
 * Teste manual da ATV-11 (Regras de tributação para IR) — roda contra um
 * banco SQLite em memória (mesmo padrão de {@link PositionServiceManualTest}/
 * {@link TransactionServiceManualTest}), sem tocar rede. Não há JUnit no
 * projeto ainda — main() temporário, mesma decisão das atividades anteriores.
 *
 * Cobre explicitamente os 5 cenários do passo 3 da atividade (gatilho binário
 * de isenção por categoria) mais: agregação de {@code assessAnnualSummary},
 * {@code assessYear} omitindo meses sem venda, {@code getAccumulatedLoss}, e
 * a rejeição de {@code FIXED_INCOME}/{@code FOREX} (fora do escopo desta
 * atividade).
 *
 * Executar (dentro de "LM Investimentos/", jars/target sem espaço no caminho
 * - gotcha das ATV-01 a 10/CLAUDE.md; jars do JavaFX necessários porque
 * PositionService/MarketService usam javafx.concurrent.Task na assinatura):
 *   mvn -q compile
 *   java -cp "&lt;target-classes-sem-espaco&gt;;&lt;sqlite-jdbc.jar&gt;;&lt;mapstruct.jar&gt;;&lt;json.jar&gt;;&lt;javafx-jars&gt;" \
 *        com.investimento.app.data.IncomeTaxServiceManualTest
 */
public final class IncomeTaxServiceManualTest {

    private static final double EPSILON = 0.01;

    private IncomeTaxServiceManualTest() {
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
        IncomeTaxService incomeTaxService = new IncomeTaxServiceImpl(assetRepository, positionService);

        // ================= Cenário 1: Ações, vendas 19.000, ganho 3.000 -> ISENTO =================
        System.out.println("=== Cenário 1: Ações, vendas R$19.000/mês, ganho R$3.000 -> isento ===");
        Asset stock1 = newAsset(assetRepository, AssetType.STOCK, Category.STOCKS, "AAAA3");
        buyAndSell(transactionRepository, stock1,
                YearMonth.of(2026, 1), 100, 160.0, 190.0); // totalSold=19000, gain=3000

        MonthlyTaxAssessment assessment1 = incomeTaxService.assessMonth(Category.STOCKS, YearMonth.of(2026, 1));
        check(close(assessment1.totalSold(), 19_000), "totalSold esperado 19000, veio " + assessment1.totalSold());
        check(close(assessment1.grossGain(), 3_000), "grossGain esperado 3000, veio " + assessment1.grossGain());
        check(assessment1.exempt(), "cenário 1 deveria ser isento (exempt=true)");
        check(close(assessment1.taxDue(), 0), "cenário 1 taxDue esperado 0, veio " + assessment1.taxDue());

        // ================= Cenário 2: Ações, vendas 21.000, ganho 3.000 -> TRIBUTADO (mês inteiro, não faixa) =================
        System.out.println();
        System.out.println("=== Cenário 2: Ações, vendas R$21.000/mês, ganho R$3.000 -> tributado, taxDue=450 ===");
        Asset stock2 = newAsset(assetRepository, AssetType.STOCK, Category.STOCKS, "BBBB3");
        buyAndSell(transactionRepository, stock2,
                YearMonth.of(2026, 2), 100, 180.0, 210.0); // totalSold=21000, gain=3000

        MonthlyTaxAssessment assessment2 = incomeTaxService.assessMonth(Category.STOCKS, YearMonth.of(2026, 2));
        check(close(assessment2.totalSold(), 21_000), "totalSold esperado 21000, veio " + assessment2.totalSold());
        check(close(assessment2.grossGain(), 3_000), "grossGain esperado 3000, veio " + assessment2.grossGain());
        check(!assessment2.exempt(), "cenário 2 NÃO deveria ser isento (ultrapassou os 20k)");
        check(close(assessment2.taxDue(), 450), // 15% de 3000 - NAO 15% de (3000 - faixa)
                "cenário 2 taxDue esperado 450 (15% do ganho INTEIRO do mês), veio " + assessment2.taxDue());

        // ================= Cenário 3: FII, vendas 500, ganho 100 -> SEMPRE tributado, mesmo venda pequena =================
        System.out.println();
        System.out.println("=== Cenário 3: FII, vendas R$500/mês, ganho R$100 -> sempre tributado, taxDue=15 ===");
        Asset fii = newAsset(assetRepository, AssetType.FII, Category.FIIS, "FIIX11");
        buyAndSell(transactionRepository, fii,
                YearMonth.of(2026, 3), 10, 40.0, 50.0); // totalSold=500, gain=100

        MonthlyTaxAssessment assessment3 = incomeTaxService.assessMonth(Category.FIIS, YearMonth.of(2026, 3));
        check(close(assessment3.totalSold(), 500), "totalSold esperado 500, veio " + assessment3.totalSold());
        check(close(assessment3.grossGain(), 100), "grossGain esperado 100, veio " + assessment3.grossGain());
        check(!assessment3.exempt(), "FII nunca é isento (exempt deveria ser false mesmo com venda pequena)");
        check(close(assessment3.taxDue(), 15), "cenário 3 taxDue esperado 15 (15% de 100), veio " + assessment3.taxDue());

        // ================= Cenário 4: Cripto, vendas 40.000, ganho 2.000 -> tributado (ultrapassou os 35k) =================
        System.out.println();
        System.out.println("=== Cenário 4: Cripto, vendas R$40.000/mês, ganho R$2.000 -> tributado (teto 35k) ===");
        Asset crypto = newAsset(assetRepository, AssetType.CRYPTO, Category.CRYPTO, "XYZ");
        buyAndSell(transactionRepository, crypto,
                YearMonth.of(2026, 4), 10, 3_800.0, 4_000.0); // totalSold=40000, gain=2000

        MonthlyTaxAssessment assessment4 = incomeTaxService.assessMonth(Category.CRYPTO, YearMonth.of(2026, 4));
        check(close(assessment4.totalSold(), 40_000), "totalSold esperado 40000, veio " + assessment4.totalSold());
        check(close(assessment4.grossGain(), 2_000), "grossGain esperado 2000, veio " + assessment4.grossGain());
        check(!assessment4.exempt(), "cenário 4 NÃO deveria ser isento (ultrapassou os 35k)");
        check(close(assessment4.taxDue(), 300), "cenário 4 taxDue esperado 300 (15% de 2000), veio " + assessment4.taxDue());

        // ================= Cenário 5: grossGain negativo -> taxDue = 0, MESMO em categoria sem isenção (FII) =================
        System.out.println();
        System.out.println("=== Cenário 5: FII com prejuízo no mês -> taxDue=0 (nunca imposto negativo) ===");
        Asset fiiLoss = newAsset(assetRepository, AssetType.FII, Category.FIIS, "FIIY11");
        buyAndSell(transactionRepository, fiiLoss,
                YearMonth.of(2026, 5), 10, 100.0, 50.0); // totalSold=500, gain=-500

        MonthlyTaxAssessment assessment5 = incomeTaxService.assessMonth(Category.FIIS, YearMonth.of(2026, 5));
        check(close(assessment5.grossGain(), -500), "grossGain esperado -500, veio " + assessment5.grossGain());
        check(!assessment5.exempt(), "FII continua taxable=true mesmo com prejuízo (exempt=false)");
        check(close(assessment5.taxDue(), 0), "cenário 5 taxDue esperado 0 (nunca imposto negativo), veio " + assessment5.taxDue());

        // ================= Cenário 6: assessYear omite meses sem venda, inclui só os que tiveram =================
        System.out.println();
        System.out.println("=== Cenário 6: assessYear(STOCKS, 2026) traz só jan e fev (únicos meses com venda) ===");
        List<MonthlyTaxAssessment> stocksYear = incomeTaxService.assessYear(Category.STOCKS, 2026);
        check(stocksYear.size() == 2, "assessYear(STOCKS, 2026) deveria ter 2 meses, veio " + stocksYear.size());
        check(stocksYear.get(0).month().equals(YearMonth.of(2026, 1)), "1º mês deveria ser jan/2026");
        check(stocksYear.get(1).month().equals(YearMonth.of(2026, 2)), "2º mês deveria ser fev/2026");

        // ================= Cenário 7: assessAnnualSummary agrega as 3 categorias =================
        System.out.println();
        System.out.println("=== Cenário 7: assessAnnualSummary(2026) agrega STOCKS/FIIS/CRYPTO ===");
        AnnualIncomeTaxSummary summary = incomeTaxService.assessAnnualSummary(2026);
        check(summary.year() == 2026, "year esperado 2026, veio " + summary.year());
        check(summary.assessmentsByCategory().keySet().equals(
                        java.util.Set.of(Category.STOCKS, Category.FIIS, Category.CRYPTO)),
                "assessmentsByCategory deveria ter exatamente as 3 categorias tributáveis, veio "
                        + summary.assessmentsByCategory().keySet());
        check(!summary.assessmentsByCategory().containsKey(Category.FIXED_INCOME),
                "FIXED_INCOME não deveria aparecer no AnnualIncomeTaxSummary (fora do escopo, ver ATV-15)");
        check(!summary.assessmentsByCategory().containsKey(Category.FOREX),
                "FOREX não deveria aparecer no AnnualIncomeTaxSummary (regra não definida, ver ATV-11)");
        double expectedTotalTaxDue = 0 /* cenário 1, isento */ + 450 /* cenário 2 */
                + 15 /* cenário 3 */ + 0 /* cenário 5, prejuízo */ + 300 /* cenário 4 */;
        check(close(summary.totalTaxDue(), expectedTotalTaxDue),
                "totalTaxDue esperado " + expectedTotalTaxDue + " (450+15+300), veio " + summary.totalTaxDue());

        // ================= Cenário 8: getAccumulatedLoss soma prejuízo de meses ANTERIORES =================
        System.out.println();
        System.out.println("=== Cenário 8: getAccumulatedLoss(FIIS, jun/2026) inclui o prejuízo de mai/2026 ===");
        double accumulatedLoss = incomeTaxService.getAccumulatedLoss(Category.FIIS, YearMonth.of(2026, 6));
        check(close(accumulatedLoss, 500), "getAccumulatedLoss esperado 500 (prejuízo de mai/2026), veio " + accumulatedLoss);
        double accumulatedLossBefore = incomeTaxService.getAccumulatedLoss(Category.FIIS, YearMonth.of(2026, 5));
        check(close(accumulatedLossBefore, 0),
                "getAccumulatedLoss(FIIS, mai/2026) NÃO deveria incluir o próprio mai/2026 (só meses ANTERIORES), veio "
                        + accumulatedLossBefore);

        // ================= Cenário 9: FIXED_INCOME/FOREX fora do escopo -> IllegalArgumentException =================
        System.out.println();
        System.out.println("=== Cenário 9: FIXED_INCOME/FOREX rejeitados (fora do escopo desta atividade) ===");
        check(throwsIllegalArgument(() -> incomeTaxService.assessMonth(Category.FIXED_INCOME, YearMonth.of(2026, 1))),
                "assessMonth(FIXED_INCOME, ...) deveria lançar IllegalArgumentException");
        check(throwsIllegalArgument(() -> incomeTaxService.assessMonth(Category.FOREX, YearMonth.of(2026, 1))),
                "assessMonth(FOREX, ...) deveria lançar IllegalArgumentException");
        check(throwsIllegalArgument(() -> incomeTaxService.assessYear(Category.FIXED_INCOME, 2026)),
                "assessYear(FIXED_INCOME, ...) deveria lançar IllegalArgumentException");

        conn.close();
        System.out.println();
        System.out.println("IncomeTaxServiceManualTest: TODOS OS CENARIOS PASSARAM.");
    }

    // ---- Helpers ----

    private static Asset newAsset(AssetRepository assetRepository, AssetType type, Category category, String ticker) {
        AssetBuilder builder = Asset.builder()
                .type(type).category(category).ticker(ticker)
                .displayName(ticker).currency("BRL")
                .quoteSource(category == Category.CRYPTO ? QuoteSource.COINGECKO : QuoteSource.BRAPI)
                .sourceIdentifier(ticker)
                .active(true);
        return assetRepository.insert(builder.build());
    }

    /** Compra e vende {@code quantity} unidades no mesmo mês, sem taxas (fees=0), para isolar totalSold/grossGain. */
    private static void buyAndSell(TransactionRepository transactionRepository, Asset asset,
                                    YearMonth month, double quantity, double buyPrice, double sellPrice) {
        transactionRepository.insert(Transaction.builder()
                .assetId(asset.getId()).operationType(OperationType.BUY)
                .date(month.atDay(1)).quantity(quantity).unitPrice(buyPrice).fees(0).build());
        transactionRepository.insert(Transaction.builder()
                .assetId(asset.getId()).operationType(OperationType.SELL)
                .date(month.atDay(15)).quantity(quantity).unitPrice(sellPrice).fees(0).build());
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

    private static boolean throwsIllegalArgument(Runnable action) {
        try {
            action.run();
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }

    /**
     * Stub de {@link MarketService} - IncomeTaxService/PositionService neste
     * teste não precisam de dado de câmbio real (nenhum ativo FOREX é
     * exercitado), mas PositionServiceImpl exige a dependência no construtor.
     */
    private static class FakeMarketService implements MarketService {
        @Override
        public MacroSnapshot getMacroSnapshot() {
            return new MacroSnapshot(Map.of(), Map.of(), null);
        }

        @Override
        public Map<String, List<IndicatorPoint>> getIndicators() {
            return Map.of();
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
    }
}
