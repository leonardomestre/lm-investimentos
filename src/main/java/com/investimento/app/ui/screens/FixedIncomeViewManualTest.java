package com.investimento.app.ui.screens;

import com.investimento.app.api.hgbrasil.model.Currency;
import com.investimento.app.api.hgbrasil.model.IndicatorPoint;
import com.investimento.app.api.hgbrasil.model.MacroSnapshot;
import com.investimento.app.data.model.Asset;
import com.investimento.app.data.model.AssetType;
import com.investimento.app.data.model.Benchmark;
import com.investimento.app.data.model.Category;
import com.investimento.app.data.model.OperationType;
import com.investimento.app.data.model.QuoteSource;
import com.investimento.app.data.model.Transaction;
import com.investimento.app.dto.FixedIncomeProjectionPoint;
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
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.NumberFormat;
import java.text.ParseException;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Teste automatizado (sem JUnit, mesmo padrão de {@code *ManualTest} das
 * atividades anteriores) da tela Renda Fixa (ATV-15).
 *
 * <p><strong>Escopo desta execução</strong>: por instrução explícita, nenhuma
 * etapa de validação visual/manual ({@code mvn javafx:run} + observação
 * humana) foi executada — só os cenários abaixo, verificáveis inteiramente
 * por código, cobrindo os 3 itens do critério de aceite da atividade:</p>
 * <ol>
 *   <li>valor líquido sempre {@code <=} valor bruto projetado;</li>
 *   <li>alíquota aplicada bate com o prazo decorrido, testado em 2 faixas
 *       diferentes (90 dias e 800 dias);</li>
 *   <li>gráfico de projeção termina exatamente na {@code maturityDate}, não
 *       continua além dela.</li>
 * </ol>
 *
 * <p>Roda contra um SQLite em memória isolado, sem tocar rede nenhuma — o
 * cálculo de renda fixa ({@code PositionServiceImpl}) não depende de
 * {@code MarketService} (lê {@code RateHistoryRepository}/{@code
 * IndicatorHistoryRepository} diretamente), então um {@code FakeMarketService}
 * mínimo (mesmo padrão do {@code PositionServiceManualTest}/{@code
 * StocksFiisViewManualTest}) é suficiente.</p>
 */
public final class FixedIncomeViewManualTest {

    private static final Locale PT_BR = new Locale("pt", "BR");

    private FixedIncomeViewManualTest() {
    }

    public static void main(String[] args) throws Exception {
        Platform.startup(() -> {
        });

        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            runAllScenarios();
        } catch (Throwable t) {
            failure.set(t);
            t.printStackTrace();
        }

        Platform.exit();

        if (failure.get() != null) {
            System.out.println("FALHOU: " + failure.get().getMessage());
            System.exit(1);
        } else {
            System.out.println("TODOS OS CENARIOS PASSARAM (ATV-15)");
        }
    }

    private static void runAllScenarios() throws Exception {
        Connection connection = openInMemoryDatabase();
        AssetRepository assetRepository = new AssetRepositoryImpl(connection);
        TransactionRepository transactionRepository = new TransactionRepositoryImpl(connection);
        QuoteHistoryRepository quoteHistoryRepository = new QuoteHistoryRepositoryImpl(connection);
        RateHistoryRepository rateHistoryRepository = new RateHistoryRepositoryImpl(connection);
        IndicatorHistoryRepository indicatorHistoryRepository = new IndicatorHistoryRepositoryImpl(connection);

        MarketService marketService = new FakeMarketService();
        PositionService positionService = new PositionServiceImpl(assetRepository, transactionRepository,
                marketService, quoteHistoryRepository, rateHistoryRepository, indicatorHistoryRepository);

        // ---- Massa de dados ----
        // CDB_A: aplicado ha 90 dias -> faixa "ate 180 dias" (22,5%).
        Asset cdbA = assetRepository.insert(Asset.builder()
                .type(AssetType.FIXED_INCOME).category(Category.FIXED_INCOME).displayName("CDB Banco Azul")
                .currency("BRL").quoteSource(QuoteSource.NONE).benchmark(Benchmark.FIXED_RATE)
                .contractedRatePct(12.0).financialInstitution("Banco Azul")
                .investmentDate(LocalDate.now().minusDays(90)).maturityDate(LocalDate.now().plusYears(2))
                .active(true).build());
        transactionRepository.insert(Transaction.builder()
                .assetId(cdbA.getId()).operationType(OperationType.BUY)
                .date(LocalDate.now().minusDays(90)).quantity(1).unitPrice(10000.0).fees(0).build());

        // CDB_B: aplicado ha 800 dias -> faixa "acima de 720 dias" (15%).
        Asset cdbB = assetRepository.insert(Asset.builder()
                .type(AssetType.FIXED_INCOME).category(Category.FIXED_INCOME).displayName("CDB Banco Rubro")
                .currency("BRL").quoteSource(QuoteSource.NONE).benchmark(Benchmark.FIXED_RATE)
                .contractedRatePct(10.0).financialInstitution("Banco Rubro")
                .investmentDate(LocalDate.now().minusDays(800)).maturityDate(LocalDate.now().plusYears(1))
                .active(true).build());
        transactionRepository.insert(Transaction.builder()
                .assetId(cdbB.getId()).operationType(OperationType.BUY)
                .date(LocalDate.now().minusDays(800)).quantity(1).unitPrice(5000.0).fees(0).build());

        // CDB_C: janela curta e fixa (7 meses) para testar que o grafico de
        // projecao termina exatamente na maturityDate.
        LocalDate start = LocalDate.of(2026, 1, 10);
        LocalDate end = LocalDate.of(2026, 8, 10);
        Asset cdbC = assetRepository.insert(Asset.builder()
                .type(AssetType.FIXED_INCOME).category(Category.FIXED_INCOME).displayName("CDB Banco Verde")
                .currency("BRL").quoteSource(QuoteSource.NONE).benchmark(Benchmark.FIXED_RATE)
                .contractedRatePct(8.0).financialInstitution("Banco Verde")
                .investmentDate(start).maturityDate(end)
                .active(true).build());
        transactionRepository.insert(Transaction.builder()
                .assetId(cdbC.getId()).operationType(OperationType.BUY)
                .date(start).quantity(1).unitPrice(2000.0).fees(0).build());

        // Ativo fora de escopo (STOCK) - so para confirmar que os metodos novos
        // de PositionService rejeitam categoria que nao seja FIXED_INCOME.
        Asset petr4 = assetRepository.insert(Asset.builder()
                .type(AssetType.STOCK).category(Category.STOCKS).ticker("PETR4").displayName("Petrobras PN")
                .currency("BRL").quoteSource(QuoteSource.BRAPI).sourceIdentifier("PETR4").active(true).build());

        scenario1_netValueNeverExceedsGross(positionService, cdbA, cdbB, 10000.0, 5000.0);
        scenario2_taxRateMatchesElapsedDays(positionService, cdbA, cdbB, 10000.0, 5000.0);
        scenario3_projectionEndsExactlyAtMaturity(positionService, cdbC, start, end, 2000.0);
        scenario4_rejectsNonFixedIncomeAsset(positionService, petr4);

        AtomicReference<FixedIncomeView> viewRef = new AtomicReference<>();
        AtomicReference<Parent> rootRef = new AtomicReference<>();
        runOnFxAndWait(() -> {
            FixedIncomeView view = new FixedIncomeView(positionService, marketService);
            Parent viewRoot = view.getRoot();
            viewRoot.applyCss();
            viewRoot.layout();
            view.onShow();
            viewRoot.applyCss();
            viewRoot.layout();
            viewRef.set(view);
            rootRef.set(viewRoot);
        });
        FixedIncomeView view = viewRef.get();
        Parent root = rootRef.get();

        scenario5_tableRendersAllTitlesWithNetLessOrEqualGross(root, cdbA, cdbB, cdbC);
        scenario6_selectingRowUpdatesProjectionAndIrCard(view, root, cdbA, cdbB);

        System.out.println("[INFO] ativos de apoio no teste: CDB_A(90d)=" + cdbA.getId()
                + " CDB_B(800d)=" + cdbB.getId() + " CDB_C(janela curta)=" + cdbC.getId()
                + " PETR4(fora de escopo)=" + petr4.getId());
    }

    // =====================================================================
    // Cenario 1 — valor liquido nunca maior que o bruto
    // =====================================================================

    private static void scenario1_netValueNeverExceedsGross(PositionService positionService, Asset cdbA, Asset cdbB,
                                                              double investedA, double investedB) {
        double grossA = positionService.calculatePosition(cdbA.getId()).currentValue();
        double netA = positionService.calculateFixedIncomeNetValue(cdbA.getId());
        assertTrue(netA <= grossA + 0.005, "CDB_A: liquido (" + netA + ") nao deveria exceder o bruto (" + grossA + ")");
        assertTrue(netA > investedA, "CDB_A: liquido deveria ser maior que o valor aplicado (ha rendimento positivo)");

        double grossB = positionService.calculatePosition(cdbB.getId()).currentValue();
        double netB = positionService.calculateFixedIncomeNetValue(cdbB.getId());
        assertTrue(netB <= grossB + 0.005, "CDB_B: liquido (" + netB + ") nao deveria exceder o bruto (" + grossB + ")");
        assertTrue(netB > investedB, "CDB_B: liquido deveria ser maior que o valor aplicado (ha rendimento positivo)");

        System.out.println("[OK] cenario 1 (liquido <= bruto): CDB_A bruto=" + round2(grossA) + " liquido=" + round2(netA)
                + " | CDB_B bruto=" + round2(grossB) + " liquido=" + round2(netB));
    }

    // =====================================================================
    // Cenario 2 — aliquota bate com o prazo decorrido (2 faixas diferentes)
    // =====================================================================

    private static void scenario2_taxRateMatchesElapsedDays(PositionService positionService, Asset cdbA, Asset cdbB,
                                                              double investedA, double investedB) {
        // CDB_A: 90 dias decorridos -> faixa "ate 180 dias", aliquota 22,5%.
        double grossA = positionService.calculatePosition(cdbA.getId()).currentValue();
        double netA = positionService.calculateFixedIncomeNetValue(cdbA.getId());
        double expectedNetA = investedA + (grossA - investedA) * (1 - 0.225);
        assertClose(expectedNetA, netA, 0.01, "CDB_A (90 dias) deveria usar aliquota 22,5%");

        // CDB_B: 800 dias decorridos -> faixa "acima de 720 dias", aliquota 15%.
        double grossB = positionService.calculatePosition(cdbB.getId()).currentValue();
        double netB = positionService.calculateFixedIncomeNetValue(cdbB.getId());
        double expectedNetB = investedB + (grossB - investedB) * (1 - 0.15);
        assertClose(expectedNetB, netB, 0.01, "CDB_B (800 dias) deveria usar aliquota 15%");

        // Confirma que as 2 faixas realmente sao diferentes (nao e coincidencia
        // as 2 contas baterem com qualquer aliquota fixa).
        double taxRateA = 1 - (netA - investedA) / (grossA - investedA);
        double taxRateB = 1 - (netB - investedB) / (grossB - investedB);
        assertTrue(Math.abs(taxRateA - taxRateB) > 0.05, "as 2 faixas de prazo deveriam resultar em aliquotas diferentes");

        System.out.println("[OK] cenario 2 (aliquota bate com o prazo): CDB_A(90d) aliquota~" + round2(taxRateA * 100)
                + "% | CDB_B(800d) aliquota~" + round2(taxRateB * 100) + "%");
    }

    // =====================================================================
    // Cenario 3 — grafico de projecao termina exatamente na maturityDate
    // =====================================================================

    private static void scenario3_projectionEndsExactlyAtMaturity(PositionService positionService, Asset cdbC,
                                                                    LocalDate start, LocalDate end, double invested) {
        List<FixedIncomeProjectionPoint> points = positionService.calculateFixedIncomeProjection(cdbC.getId());
        assertTrue(points.size() >= 2, "deveria haver pelo menos 2 pontos de projecao (inicio e vencimento)");

        FixedIncomeProjectionPoint first = points.get(0);
        FixedIncomeProjectionPoint last = points.get(points.size() - 1);
        assertEquals(start, first.date(), "primeiro ponto deveria ser a investmentDate");
        assertEquals(end, last.date(), "ultimo ponto deveria ser exatamente a maturityDate");
        assertClose(invested, first.grossValue(), 0.01, "primeiro ponto (dia 0) deveria valer o valor aplicado");

        for (FixedIncomeProjectionPoint p : points) {
            assertTrue(!p.date().isAfter(end), "nenhum ponto deveria ultrapassar a maturityDate: " + p.date());
            assertTrue(!p.date().isBefore(start), "nenhum ponto deveria ser anterior a investmentDate: " + p.date());
            assertTrue(p.netValue() <= p.grossValue() + 0.005, "liquido nao deveria exceder o bruto em nenhum ponto");
        }

        System.out.println("[OK] cenario 3 (projecao termina na maturityDate): " + points.size()
                + " pontos, de " + first.date() + " a " + last.date());
    }

    // =====================================================================
    // Cenario 4 — metodos novos rejeitam ativo que nao seja FIXED_INCOME
    // =====================================================================

    private static void scenario4_rejectsNonFixedIncomeAsset(PositionService positionService, Asset petr4) {
        try {
            positionService.calculateFixedIncomeNetValue(petr4.getId());
            throw new AssertionError("deveria ter rejeitado calculateFixedIncomeNetValue para um ativo STOCK");
        } catch (IllegalArgumentException e) {
            // esperado
        }
        try {
            positionService.calculateFixedIncomeProjection(petr4.getId());
            throw new AssertionError("deveria ter rejeitado calculateFixedIncomeProjection para um ativo STOCK");
        } catch (IllegalArgumentException e) {
            // esperado
        }
        System.out.println("[OK] cenario 4 (rejeita ativo que nao e FIXED_INCOME) para ambos os metodos novos");
    }

    // =====================================================================
    // Cenario 5 — tabela renderiza os 3 titulos, liquido <= bruto em cada linha
    // =====================================================================

    private static void scenario5_tableRendersAllTitlesWithNetLessOrEqualGross(Parent root, Asset cdbA, Asset cdbB,
                                                                                 Asset cdbC) {
        GridPane grid = (GridPane) findNode(root, "#positionsGrid");
        assertTrue(grid != null, "deveria existir uma tabela de posicoes renderizada");

        int maxRow = grid.getChildren().stream()
                .mapToInt(n -> Optional.ofNullable(GridPane.getRowIndex(n)).orElse(0))
                .max().orElse(0);
        assertEquals(3, maxRow, "deveria haver exatamente 3 titulos de renda fixa na tabela");

        for (int row = 1; row <= maxRow; row++) {
            double gross = parsePtBr(cellText(grid, 5, row));
            double net = parsePtBr(cellText(grid, 6, row));
            assertTrue(net <= gross + 0.01, "linha " + row + ": liquido (" + net + ") nao deveria exceder bruto (" + gross + ")");
        }

        System.out.println("[OK] cenario 5 (tabela com 3 titulos, liquido <= bruto em todas as linhas)");
    }

    // =====================================================================
    // Cenario 6 — selecionar linha atualiza grafico de projecao e card de IR
    // =====================================================================

    private static void scenario6_selectingRowUpdatesProjectionAndIrCard(FixedIncomeView view, Parent root,
                                                                           Asset cdbA, Asset cdbB) throws InterruptedException {
        runOnFxAndWait(() -> view.selectAssetForTest(cdbA.getId()));
        Label projectionTitle1 = (Label) findNode(root, "#projectionTitleLabel");
        assertTrue(projectionTitle1.getText().contains("CDB Banco Azul"), "titulo do grafico deveria mostrar CDB Banco Azul");
        assertTrue(findNode(root, "#projectionChartCanvas") instanceof Canvas, "deveria haver um Canvas de projecao renderizado");

        GridPane bracketsGridA = (GridPane) findNode(root, "#irBracketsGrid");
        assertTrue(cellText(bracketsGridA, 1, 0).contains("faixa atual"),
                "CDB_A (90 dias) deveria destacar a faixa 'ate 180 dias' (linha 0) como atual");
        assertTrue(!cellText(bracketsGridA, 1, 3).contains("faixa atual"),
                "CDB_A nao deveria destacar a faixa 'acima de 720 dias' (linha 3)");

        runOnFxAndWait(() -> view.selectAssetForTest(cdbB.getId()));
        Label projectionTitle2 = (Label) findNode(root, "#projectionTitleLabel");
        assertTrue(projectionTitle2.getText().contains("CDB Banco Rubro"), "titulo do grafico deveria mudar para CDB Banco Rubro");

        GridPane bracketsGridB = (GridPane) findNode(root, "#irBracketsGrid");
        assertTrue(cellText(bracketsGridB, 1, 3).contains("faixa atual"),
                "CDB_B (800 dias) deveria destacar a faixa 'acima de 720 dias' (linha 3) como atual");
        assertTrue(!cellText(bracketsGridB, 1, 0).contains("faixa atual"),
                "CDB_B nao deveria destacar a faixa 'ate 180 dias' (linha 0)");

        System.out.println("[OK] cenario 6 (selecionar linha atualiza grafico + card de IR): "
                + "CDB_A destaca faixa 22,5%, CDB_B destaca faixa 15%");
    }

    // =====================================================================
    // Infra de teste
    // =====================================================================

    private static String cellText(GridPane grid, int col, int row) {
        for (Node child : grid.getChildrenUnmodifiable()) {
            int c = Optional.ofNullable(GridPane.getColumnIndex(child)).orElse(0);
            int r = Optional.ofNullable(GridPane.getRowIndex(child)).orElse(0);
            if (c == col && r == row) {
                if (child instanceof Label label) {
                    return label.getText();
                }
                if (child instanceof VBox vbox && !vbox.getChildren().isEmpty()
                        && vbox.getChildren().get(0) instanceof Label label) {
                    return label.getText();
                }
                if (child instanceof HBox hbox && !hbox.getChildren().isEmpty()
                        && hbox.getChildren().get(0) instanceof Label label) {
                    return label.getText();
                }
                return null;
            }
        }
        return null;
    }

    private static double parsePtBr(String text) {
        try {
            return NumberFormat.getNumberInstance(PT_BR).parse(text).doubleValue();
        } catch (ParseException e) {
            throw new RuntimeException("Nao consegui parsear numero pt-BR: " + text, e);
        }
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static void runOnFxAndWait(Runnable action) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<RuntimeException> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (RuntimeException e) {
                error.set(e);
            } finally {
                latch.countDown();
            }
        });
        latch.await();
        if (error.get() != null) {
            throw error.get();
        }
    }

    /**
     * Mesma técnica das ATV-13/14 ({@code RegistrationViewManualTest}/{@code
     * StocksFiisViewManualTest}): desce manualmente por {@code
     * ScrollPane.getContent()} em vez de depender de {@code Node.lookup}, que
     * só encontra descendentes de um {@code ScrollPane} depois que o {@code
     * Skin} é materializado (só acontece com um {@code Stage} visível).
     */
    private static Node findNode(Node node, String selector) {
        String id = selector.startsWith("#") ? selector.substring(1) : selector;
        if (id.equals(node.getId())) {
            return node;
        }
        if (node instanceof ScrollPane scrollPane) {
            Node found = scrollPane.getContent() != null ? findNode(scrollPane.getContent(), id) : null;
            if (found != null) {
                return found;
            }
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                Node found = findNode(child, id);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static Connection openInMemoryDatabase() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        String sql = readResource("/schema.sql");
        try (Statement st = connection.createStatement()) {
            for (String statement : sql.split(";")) {
                String trimmed = statement.trim();
                if (!trimmed.isEmpty()) {
                    st.execute(trimmed);
                }
            }
        }
        return connection;
    }

    private static String readResource(String resourcePath) {
        try (InputStream in = FixedIncomeViewManualTest.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("Recurso nao encontrado no classpath: " + resourcePath);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(message + " (esperado=" + expected + ", encontrado=" + actual + ")");
        }
    }

    private static void assertClose(double expected, double actual, double tolerance, String message) {
        if (Math.abs(expected - actual) > tolerance) {
            throw new AssertionError(message + " (esperado~=" + expected + ", encontrado=" + actual + ")");
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    /**
     * Mesmo padrão do {@code FakeMarketService} do {@code
     * PositionServiceManualTest}/{@code StocksFiisViewManualTest} — sem rede.
     * O cálculo de renda fixa não usa {@code MarketService} (lê {@code
     * RateHistoryRepository}/{@code IndicatorHistoryRepository} diretamente),
     * então este fake só precisa satisfazer a interface, nunca é exercitado
     * de fato pelos cenários de renda fixa deste teste.
     */
    private static class FakeMarketService implements MarketService {
        @Override
        public MacroSnapshot getMacroSnapshot() {
            return new MacroSnapshot(Map.of("USD", new Currency("USD", "Dollar", 5.30, null, 0.0)), Map.of(), null);
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
