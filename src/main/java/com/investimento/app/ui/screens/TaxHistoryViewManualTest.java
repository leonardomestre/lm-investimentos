package com.investimento.app.ui.screens;

import com.investimento.app.data.model.Asset;
import com.investimento.app.data.model.AssetType;
import com.investimento.app.data.model.Category;
import com.investimento.app.data.model.OperationType;
import com.investimento.app.data.model.QuoteSource;
import com.investimento.app.data.model.Transaction;
import com.investimento.app.dto.TransactionDTO;
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
import com.investimento.app.service.AssetService;
import com.investimento.app.service.AssetServiceImpl;
import com.investimento.app.service.IncomeTaxService;
import com.investimento.app.service.IncomeTaxServiceImpl;
import com.investimento.app.service.PositionService;
import com.investimento.app.service.PositionServiceImpl;
import com.investimento.app.service.TransactionService;
import com.investimento.app.service.TransactionServiceImpl;
import com.investimento.app.api.hgbrasil.model.IndicatorPoint;
import com.investimento.app.api.hgbrasil.model.MacroSnapshot;
import com.investimento.app.service.MarketService;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Teste manual (sem JUnit, mesmo padrão de {@code *ManualTest} das
 * atividades anteriores) da tela Histórico e IR (ATV-17).
 *
 * <p><strong>Escopo desta execução</strong>: por instrução explícita do
 * usuário, nenhuma etapa de validação visual/manual ({@code mvn javafx:run} +
 * observação humana) foi executada — só os cenários abaixo, verificáveis
 * inteiramente por código, cobrindo os 3 itens do critério de aceite da
 * atividade: (1) filtro por ano/mês/ativo refletindo na tabela e no resumo,
 * (2) formato exato do CSV exportado, (3) resumo por categoria batendo com
 * os 5 cenários de teste da ATV-11 ({@code IncomeTaxServiceManualTest}).</p>
 *
 * <p>Roda contra um SQLite em memória isolado, sem tocar rede — {@code
 * IncomeTaxService}/{@code PositionService} não dependem de rede para
 * ativos {@code STOCKS}/{@code FIIS}/{@code CRYPTO} (ganho de capital vem só
 * de {@code transactions}, sem consultar preço de mercado), e {@code
 * AssetService} é usado aqui só para {@code listAssets} (sem tocar
 * {@code BrapiClient}/{@code CoinGeckoClient}, passados como {@code null}).</p>
 */
public final class TaxHistoryViewManualTest {

    private static final double EPSILON = 0.01;

    private TaxHistoryViewManualTest() {
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
            System.out.println("TODOS OS CENARIOS PASSARAM (ATV-17)");
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
        IncomeTaxService incomeTaxService = new IncomeTaxServiceImpl(assetRepository, positionService);
        TransactionService transactionService = new TransactionServiceImpl(transactionRepository, assetRepository);
        AssetService assetService = new AssetServiceImpl(assetRepository, null, null, marketService);

        // ---- Massa de dados: os MESMOS 5 cenários da ATV-11 (IncomeTaxServiceManualTest),
        //      reaproveitados aqui para que o "resumo por categoria bate com os cenários de
        //      teste da ATV-11" (critério de aceite da ATV-17) seja verificado ponta a ponta,
        //      da transação crua até a tela renderizada. ----

        // Cenário 1: Ações, jan/2026, vendas 19.000, ganho 3.000 -> isento.
        Asset aaaa3 = newAsset(assetRepository, AssetType.STOCK, Category.STOCKS, "AAAA3");
        buyAndSell(transactionRepository, aaaa3, YearMonth.of(2026, 1), 100, 160.0, 190.0);

        // Cenário 2: Ações, fev/2026, vendas 21.000, ganho 3.000 -> tributado, taxDue=450.
        Asset bbbb3 = newAsset(assetRepository, AssetType.STOCK, Category.STOCKS, "BBBB3");
        buyAndSell(transactionRepository, bbbb3, YearMonth.of(2026, 2), 100, 180.0, 210.0);

        // Cenário 3: FII, mar/2026, vendas 500, ganho 100 -> sempre tributado, taxDue=15.
        Asset fiix11 = newAsset(assetRepository, AssetType.FII, Category.FIIS, "FIIX11");
        buyAndSell(transactionRepository, fiix11, YearMonth.of(2026, 3), 10, 40.0, 50.0);

        // Cenário 4: Cripto, abr/2026, vendas 40.000, ganho 2.000 -> tributado (teto 35k), taxDue=300.
        Asset xyz = newAsset(assetRepository, AssetType.CRYPTO, Category.CRYPTO, "XYZ");
        buyAndSell(transactionRepository, xyz, YearMonth.of(2026, 4), 10, 3_800.0, 4_000.0);

        // Cenário 5: FII, mai/2026, prejuízo -> exempt=false (FII nunca isento) mas taxDue=0.
        Asset fiiy11 = newAsset(assetRepository, AssetType.FII, Category.FIIS, "FIIY11");
        buyAndSell(transactionRepository, fiiy11, YearMonth.of(2026, 5), 10, 100.0, 50.0);

        AtomicReference<TaxHistoryView> viewRef = new AtomicReference<>();
        AtomicReference<Parent> rootRef = new AtomicReference<>();
        runOnFxAndWait(() -> {
            TaxHistoryView view = new TaxHistoryView(transactionService, incomeTaxService, positionService, assetService);
            Parent viewRoot = view.getRoot();
            viewRoot.applyCss();
            viewRoot.layout();
            view.onShow();
            viewRoot.applyCss();
            viewRoot.layout();
            viewRef.set(view);
            rootRef.set(viewRoot);
        });
        TaxHistoryView view = viewRef.get();
        Parent root = rootRef.get();

        scenario1_defaultView_taxAssessmentMatchesAtv11(root);
        scenario2_annualSummaryMatchesAtv11(root);
        scenario3_fiisCategoryPillPrejuizoScenario(view, root);
        scenario4_monthFilterReflectsInTableAndSummary(view, root);
        scenario5_assetFilterReflectsInTransactionsTable(view, root, aaaa3);
        scenario6_csvExportFormat(view, transactionService);

        System.out.println("[INFO] ativos de apoio no teste: AAAA3=" + aaaa3.getId() + " BBBB3=" + bbbb3.getId()
                + " FIIX11=" + fiix11.getId() + " XYZ=" + xyz.getId() + " FIIY11=" + fiiy11.getId());
    }

    // =====================================================================
    // Cenário 1 — apuração mensal (categoria padrão STOCKS) bate com ATV-11
    // =====================================================================

    private static void scenario1_defaultView_taxAssessmentMatchesAtv11(Parent root) {
        GridPane grid = (GridPane) findNode(root, "#taxAssessmentGrid");
        assertTrue(grid != null, "deveria existir a tabela de apuracao mensal (categoria STOCKS por padrao)");

        assertEquals("Janeiro", cellText(grid, 0, 1), "1a linha deveria ser Janeiro");
        assertEquals("19.000,00", cellText(grid, 1, 1), "vendas de Janeiro (AAAA3)");
        assertEquals("Isento", cellText(grid, 2, 1), "Janeiro deveria estar isento (vendas <= 20k)");
        assertEquals("+3.000,00", cellText(grid, 3, 1), "resultado de Janeiro");
        assertEquals("0,00", cellText(grid, 4, 1), "imposto de Janeiro (isento) deveria ser 0");

        assertEquals("Fevereiro", cellText(grid, 0, 2), "2a linha deveria ser Fevereiro");
        assertEquals("21.000,00", cellText(grid, 1, 2), "vendas de Fevereiro (BBBB3)");
        assertEquals("Tributável", cellText(grid, 2, 2), "Fevereiro deveria ser tributavel (vendas > 20k)");
        assertEquals("+3.000,00", cellText(grid, 3, 2), "resultado de Fevereiro");
        assertEquals("450,00", cellText(grid, 4, 2), "imposto de Fevereiro (15% de 3000, mes inteiro)");

        System.out.println("[OK] cenario 1 (apuracao mensal STOCKS bate com ATV-11): jan isento/0, fev tributavel/450");
    }

    // =====================================================================
    // Cenário 2 — resumo consolidado por categoria (ano inteiro) bate com ATV-11
    // =====================================================================

    private static void scenario2_annualSummaryMatchesAtv11(Parent root) {
        GridPane grid = (GridPane) findNode(root, "#annualSummaryGrid");
        assertTrue(grid != null, "deveria existir a tabela de resumo consolidado");

        assertEquals("Ações", cellText(grid, 0, 1), "1a linha deveria ser Acoes");
        assertEquals("40.000,00", cellText(grid, 1, 1), "vendas de Acoes no ano (19000+21000)");
        assertEquals("+6.000,00", cellText(grid, 2, 1), "resultado de Acoes no ano (3000+3000)");
        assertEquals("450,00", cellText(grid, 3, 1), "imposto de Acoes no ano (0+450)");

        assertEquals("FIIs", cellText(grid, 0, 2), "2a linha deveria ser FIIs");
        assertEquals("1.000,00", cellText(grid, 1, 2), "vendas de FIIs no ano (500+500)");
        assertEquals("−400,00", cellText(grid, 2, 2), "resultado de FIIs no ano (100-500)");
        assertEquals("20,00", cellText(grid, 3, 2), "imposto de FIIs no ano (20+0 — FII e tributado a 20%)");

        assertEquals("Criptomoedas", cellText(grid, 0, 3), "3a linha deveria ser Criptomoedas");
        assertEquals("40.000,00", cellText(grid, 1, 3), "vendas de Cripto no ano");
        assertEquals("+2.000,00", cellText(grid, 2, 3), "resultado de Cripto no ano");
        assertEquals("300,00", cellText(grid, 3, 3), "imposto de Cripto no ano");

        Label totalLabel = (Label) findNode(root, "#annualSummaryTotalLabel");
        assertTrue(totalLabel.getText().contains("770,00"),
                "imposto total do periodo deveria ser 770,00 (450+20+300), veio: " + totalLabel.getText());

        System.out.println("[OK] cenario 2 (resumo consolidado bate com ATV-11): Acoes 450, FIIs 20, Cripto 300, total 770");
    }

    // =====================================================================
    // Cenário 3 — trocar para a categoria FIIs (pill) mostra o prejuízo (cenário 5 da ATV-11)
    // =====================================================================

    private static void scenario3_fiisCategoryPillPrejuizoScenario(TaxHistoryView view, Parent root)
            throws InterruptedException {
        runOnFxAndWait(() -> view.selectTaxCategoryForTest(Category.FIIS));

        GridPane grid = (GridPane) findNode(root, "#taxAssessmentGrid");
        assertEquals("Março", cellText(grid, 0, 1), "1a linha de FIIs deveria ser Marco");
        assertEquals("Tributável", cellText(grid, 2, 1), "FII sempre tributavel, mesmo com venda pequena");
        assertEquals("20,00", cellText(grid, 4, 1), "imposto de Marco (FII, 20% de 100 — aliquota de FII, nao 15%)");

        assertEquals("Maio", cellText(grid, 0, 2), "2a linha de FIIs deveria ser Maio");
        assertEquals("Tributável", cellText(grid, 2, 2), "FII com prejuizo continua exempt=false (so taxDue=0)");
        assertEquals("−500,00", cellText(grid, 3, 2), "resultado de Maio deveria ser negativo (prejuizo)");
        assertEquals("0,00", cellText(grid, 4, 2), "imposto de Maio deveria ser 0 (nunca imposto negativo)");

        Label lossLabel = (Label) findNode(root, "#accumulatedLossLabel");
        assertTrue(lossLabel.getText().contains("500,00"),
                "prejuizo acumulado de FIIs (informativo) deveria mencionar 500,00, veio: " + lossLabel.getText());

        System.out.println("[OK] cenario 3 (pill FIIs mostra prejuizo do cenario 5 da ATV-11): mar tributavel/15, "
                + "mai tributavel/prejuizo/0, prejuizo acumulado 500,00");
    }

    // =====================================================================
    // Cenário 4 — filtro de mês reflete na tabela de apuração E no resumo
    // =====================================================================

    private static void scenario4_monthFilterReflectsInTableAndSummary(TaxHistoryView view, Parent root)
            throws InterruptedException {
        runOnFxAndWait(() -> {
            view.selectTaxCategoryForTest(Category.STOCKS);
            view.setFiltersForTest(2026, 2, null); // fevereiro, todos os ativos
        });

        GridPane taxGrid = (GridPane) findNode(root, "#taxAssessmentGrid");
        int maxRow = maxRow(taxGrid);
        assertEquals(1, maxRow, "com filtro de fevereiro, apuracao de Acoes deveria ter so 1 linha (nao jan+fev)");
        assertEquals("Fevereiro", cellText(taxGrid, 0, 1), "unica linha deveria ser Fevereiro");

        GridPane summaryGrid = (GridPane) findNode(root, "#annualSummaryGrid");
        assertEquals("21.000,00", cellText(summaryGrid, 1, 1), "resumo de Acoes filtrado por fevereiro deveria ser só 21000 (sem jan)");

        Label periodLabel = (Label) findNode(root, "#annualSummaryPeriodLabel");
        assertTrue(periodLabel.getText().contains("Fevereiro"), "rotulo de periodo deveria indicar Fevereiro/2026");

        // Volta para "todos os meses" para nao afetar os cenarios seguintes.
        runOnFxAndWait(() -> view.setFiltersForTest(2026, null, null));

        System.out.println("[OK] cenario 4 (filtro de mes reflete na tabela e no resumo): fevereiro isola BBBB3");
    }

    // =====================================================================
    // Cenário 5 — filtro de ativo reflete na tabela filtrável (transações)
    // =====================================================================

    private static void scenario5_assetFilterReflectsInTransactionsTable(TaxHistoryView view, Parent root, Asset aaaa3)
            throws InterruptedException {
        runOnFxAndWait(() -> view.setFiltersForTest(2026, null, aaaa3.getId()));

        GridPane grid = (GridPane) findNode(root, "#transactionsGrid");
        int maxRow = maxRow(grid);
        assertEquals(2, maxRow, "filtrando por AAAA3 deveria sobrar so 2 transacoes (1 compra + 1 venda)");
        for (int row = 1; row <= maxRow; row++) {
            assertEquals("AAAA3", cellText(grid, 1, row), "toda linha deveria ser do ativo AAAA3");
        }

        // Restaura "todos os ativos" para o cenario 6 (CSV) exportar tudo.
        runOnFxAndWait(() -> view.setFiltersForTest(2026, null, null));

        System.out.println("[OK] cenario 5 (filtro de ativo reflete na tabela filtravel): AAAA3 isola 2 transacoes");
    }

    // =====================================================================
    // Cenário 6 — formato exato do CSV exportado (separador ;, vírgula decimal)
    // =====================================================================

    private static void scenario6_csvExportFormat(TaxHistoryView view, TransactionService transactionService)
            throws IOException {
        List<TransactionDTO> all = transactionService.listAll();
        assertEquals(10, all.size(), "deveriam existir 10 transacoes no total (5 ativos x 1 compra + 1 venda)");

        File csvFile = File.createTempFile("historico-ir-atv17-", ".csv");
        csvFile.deleteOnExit();
        view.writeCsv(csvFile, all);

        String content = Files.readString(csvFile.toPath(), StandardCharsets.UTF_8);
        if (content.startsWith("﻿")) {
            content = content.substring(1);
        }
        String[] lines = content.split("\r\n");
        assertEquals(11, lines.length, "cabecalho + 10 linhas de dados");
        assertEquals("Data;Ativo;Categoria;TipoOperacao;Quantidade;PrecoUnitario;ValorTotal;Taxas;"
                + "GanhoPerdaRealizado;Isento", lines[0], "cabecalho do CSV deveria bater exatamente com a ATV-17");

        String aaaa3Sell = findCsvLine(lines, "AAAA3", "VENDA");
        assertEquals("2026-01-15;AAAA3;Ações;VENDA;100;190,00;19000,00;0,00;3000,00;Sim", aaaa3Sell,
                "linha de venda da AAAA3 (isenta) deveria bater com o formato exato da ATV-17");

        String aaaa3Buy = findCsvLine(lines, "AAAA3", "COMPRA");
        assertEquals("2026-01-01;AAAA3;Ações;COMPRA;100;160,00;16000,00;0,00;;", aaaa3Buy,
                "linha de compra nunca preenche GanhoPerdaRealizado/Isento");

        String bbbb3Sell = findCsvLine(lines, "BBBB3", "VENDA");
        assertEquals("2026-02-15;BBBB3;Ações;VENDA;100;210,00;21000,00;0,00;3000,00;Não", bbbb3Sell,
                "linha de venda da BBBB3 (tributada) deveria ter Isento=Nao");

        String fiiy11Sell = findCsvLine(lines, "FIIY11", "VENDA");
        assertEquals("2026-05-15;FIIY11;FIIs;VENDA;10;50,00;500,00;0,00;-500,00;Não", fiiy11Sell,
                "linha de venda da FIIY11 (prejuizo) deveria ter resultado negativo e Isento=Nao (FII nunca isento)");

        // Nenhuma linha deveria ter coluna quebrada - todas com exatamente 10 campos separados por ';'.
        for (int i = 1; i < lines.length; i++) {
            String[] fields = lines[i].split(";", -1);
            assertEquals(10, fields.length, "linha " + i + " deveria ter exatamente 10 colunas: " + lines[i]);
        }

        Files.deleteIfExists(csvFile.toPath());
        System.out.println("[OK] cenario 6 (formato exato do CSV): cabecalho + 10 linhas, separador ';', "
                + "virgula decimal, sem colunas quebradas");
    }

    private static String findCsvLine(String[] lines, String ticker, String operation) {
        for (String line : lines) {
            if (line.contains(";" + ticker + ";") && line.contains(";" + operation + ";")) {
                return line;
            }
        }
        throw new AssertionError("Nao encontrei linha de CSV para " + ticker + "/" + operation);
    }

    // =====================================================================
    // Infra de teste
    // =====================================================================

    private static Asset newAsset(AssetRepository assetRepository, AssetType type, Category category, String ticker) {
        return assetRepository.insert(Asset.builder()
                .type(type).category(category).ticker(ticker)
                .displayName(ticker).currency("BRL")
                .quoteSource(category == Category.CRYPTO ? QuoteSource.COINGECKO : QuoteSource.BRAPI)
                .sourceIdentifier(ticker)
                .active(true).build());
    }

    /** Compra e vende {@code quantity} unidades no mesmo mês, sem taxas — mesmo helper da ATV-11. */
    private static void buyAndSell(TransactionRepository transactionRepository, Asset asset,
                                    YearMonth month, double quantity, double buyPrice, double sellPrice) {
        transactionRepository.insert(Transaction.builder()
                .assetId(asset.getId()).operationType(OperationType.BUY)
                .date(month.atDay(1)).quantity(quantity).unitPrice(buyPrice).fees(0).build());
        transactionRepository.insert(Transaction.builder()
                .assetId(asset.getId()).operationType(OperationType.SELL)
                .date(month.atDay(15)).quantity(quantity).unitPrice(sellPrice).fees(0).build());
    }

    private static int maxRow(GridPane grid) {
        return grid.getChildren().stream()
                .mapToInt(n -> Optional.ofNullable(GridPane.getRowIndex(n)).orElse(0))
                .max().orElse(0);
    }

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
     * Mesma técnica das ATV-13/14 ({@code findNode}): desce manualmente por
     * {@code ScrollPane.getContent()} em vez de depender de {@code
     * Node.lookup}, que só encontra descendentes de um {@code ScrollPane}
     * depois que o {@code Skin} é materializado (só acontece com um {@code
     * Stage} visível, que este teste não abre).
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
        try (InputStream in = TaxHistoryViewManualTest.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("Recurso nao encontrado no classpath: " + resourcePath);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(message + " (esperado=" + expected + ", encontrado=" + actual + ")");
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    /**
     * Stub de {@link MarketService} — nenhum ativo FOREX/cotação de mercado é
     * exercitado neste teste (ganho de capital vem só de {@code
     * transactions}), mas {@code PositionServiceImpl} exige a dependência no
     * construtor.
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
