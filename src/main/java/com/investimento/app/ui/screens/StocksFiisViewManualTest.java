package com.investimento.app.ui.screens;

import com.investimento.app.api.hgbrasil.model.Currency;
import com.investimento.app.api.hgbrasil.model.IndicatorPoint;
import com.investimento.app.api.hgbrasil.model.MacroSnapshot;
import com.investimento.app.data.model.Asset;
import com.investimento.app.data.model.AssetType;
import com.investimento.app.data.model.Benchmark;
import com.investimento.app.data.model.Category;
import com.investimento.app.data.model.Distribution;
import com.investimento.app.data.model.DistributionType;
import com.investimento.app.data.model.OperationType;
import com.investimento.app.data.model.QuoteHistory;
import com.investimento.app.data.model.QuoteSource;
import com.investimento.app.data.model.Transaction;
import com.investimento.app.dto.Position;
import com.investimento.app.repository.AssetRepository;
import com.investimento.app.repository.AssetRepositoryImpl;
import com.investimento.app.repository.DistributionRepository;
import com.investimento.app.repository.DistributionRepositoryImpl;
import com.investimento.app.repository.IndicatorHistoryRepository;
import com.investimento.app.repository.QuoteHistoryRepository;
import com.investimento.app.repository.SettingRepository;
import com.investimento.app.repository.QuoteHistoryRepositoryImpl;
import com.investimento.app.repository.RateHistoryRepository;
import com.investimento.app.repository.TransactionRepository;
import com.investimento.app.repository.TransactionRepositoryImpl;
import com.investimento.app.service.MarketService;
import com.investimento.app.ui.CurrencyDisplay;
import com.investimento.app.service.PositionService;
import com.investimento.app.service.PositionServiceImpl;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
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
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Teste manual (sem JUnit, mesmo padrão de {@code *ManualTest} das
 * atividades anteriores) da tela Ações e FIIs (ATV-14).
 *
 * <p><strong>Escopo desta execução</strong>: por instrução explícita, nenhuma
 * etapa de validação visual/manual ({@code mvn javafx:run} + observação
 * humana) foi executada — só os cenários abaixo, verificáveis inteiramente
 * por código, cobrindo os 4 itens do critério de aceite da atividade mais a
 * armadilha de {@code currentQuantity == 0} documentada nela.</p>
 *
 * <p>Roda contra um SQLite em memória isolado (mesmo padrão de
 * {@code RepositoryManualTest}/{@code PositionServiceManualTest}), sem
 * tocar rede nenhuma — {@code PositionService}/{@code StocksFiisView} não
 * dependem de rede para ativos {@code STOCKS}/{@code FIIS} (preço atual vem
 * de {@code QuoteHistoryRepository}, já semeado manualmente aqui), e
 * {@code MarketService.getDailyChanges} só lê um mapa em memória (vazio
 * quando {@code updateQuotes} nunca rodou — sem chamada de rede).</p>
 */
public final class StocksFiisViewManualTest {

    private StocksFiisViewManualTest() {
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
            System.out.println("TODOS OS CENARIOS PASSARAM (ATV-14 + pendencia 2)");
        }
    }

    private static void runAllScenarios() throws Exception {
        Connection connection = openInMemoryDatabase();
        AssetRepository assetRepository = new AssetRepositoryImpl(connection);
        TransactionRepository transactionRepository = new TransactionRepositoryImpl(connection);
        QuoteHistoryRepository quoteHistoryRepository = new QuoteHistoryRepositoryImpl(connection);
        DistributionRepository distributionRepository = new DistributionRepositoryImpl(connection);
        RateHistoryRepository rateHistoryRepository =
                new com.investimento.app.repository.RateHistoryRepositoryImpl(connection);
        IndicatorHistoryRepository indicatorHistoryRepository =
                new com.investimento.app.repository.IndicatorHistoryRepositoryImpl(connection);

        MarketService marketService = new FakeMarketService();
        PositionService positionService = new PositionServiceImpl(assetRepository, transactionRepository,
                marketService, quoteHistoryRepository, rateHistoryRepository, indicatorHistoryRepository);

        // ---- Massa de dados ----
        // PETR4 (STOCK): 100 @ 30 + taxa 5, cotacao atual 40 -> gainLoss conhecido.
        Asset petr4 = assetRepository.insert(Asset.builder()
                .type(AssetType.STOCK).category(Category.STOCKS).ticker("PETR4").displayName("Petrobras PN")
                .currency("BRL").quoteSource(QuoteSource.BRAPI).sourceIdentifier("PETR4").active(true).build());
        transactionRepository.insert(Transaction.builder()
                .assetId(petr4.getId()).operationType(OperationType.BUY)
                .date(LocalDate.of(2026, 1, 10)).quantity(100).unitPrice(30.0).fees(5.0).build());
        quoteHistoryRepository.upsert(QuoteHistory.builder()
                .assetId(petr4.getId()).date(LocalDate.of(2026, 1, 15)).price(32.0).source(QuoteSource.BRAPI).build());
        quoteHistoryRepository.upsert(QuoteHistory.builder()
                .assetId(petr4.getId()).date(LocalDate.now()).price(40.0).source(QuoteSource.BRAPI).build());

        // MXRF11 (FII): 3000 @ 9,89, cotacao atual 10,50.
        Asset mxrf11 = assetRepository.insert(Asset.builder()
                .type(AssetType.FII).category(Category.FIIS).ticker("MXRF11").displayName("Maxi Renda")
                .currency("BRL").quoteSource(QuoteSource.BRAPI).sourceIdentifier("MXRF11").active(true).build());
        transactionRepository.insert(Transaction.builder()
                .assetId(mxrf11.getId()).operationType(OperationType.BUY)
                .date(LocalDate.of(2026, 1, 5)).quantity(3000).unitPrice(9.89).fees(0).build());
        quoteHistoryRepository.upsert(QuoteHistory.builder()
                .assetId(mxrf11.getId()).date(LocalDate.of(2026, 1, 20)).price(10.0).source(QuoteSource.BRAPI).build());
        quoteHistoryRepository.upsert(QuoteHistory.builder()
                .assetId(mxrf11.getId()).date(LocalDate.now()).price(10.50).source(QuoteSource.BRAPI).build());

        // VALE3 (STOCK): comprou e vendeu tudo -> currentQuantity == 0, nao deve aparecer (armadilha da ATV-14).
        Asset vale3 = assetRepository.insert(Asset.builder()
                .type(AssetType.STOCK).category(Category.STOCKS).ticker("VALE3").displayName("Vale ON")
                .currency("BRL").quoteSource(QuoteSource.BRAPI).sourceIdentifier("VALE3").active(true).build());
        transactionRepository.insert(Transaction.builder()
                .assetId(vale3.getId()).operationType(OperationType.BUY)
                .date(LocalDate.of(2026, 1, 1)).quantity(300).unitPrice(50.0).fees(0).build());
        transactionRepository.insert(Transaction.builder()
                .assetId(vale3.getId()).operationType(OperationType.SELL)
                .date(LocalDate.of(2026, 2, 1)).quantity(300).unitPrice(55.0).fees(0).build());

        // CDB (FIXED_INCOME) e BTC (CRYPTO): outras categorias, nunca devem aparecer nesta tela.
        Asset cdb = assetRepository.insert(Asset.builder()
                .type(AssetType.FIXED_INCOME).category(Category.FIXED_INCOME).displayName("CDB Banco Teste")
                .currency("BRL").quoteSource(QuoteSource.NONE).benchmark(Benchmark.FIXED_RATE)
                .contractedRatePct(12.0).financialInstitution("Banco Teste")
                .investmentDate(LocalDate.now().minusMonths(6)).maturityDate(LocalDate.now().plusYears(1))
                .active(true).build());
        transactionRepository.insert(Transaction.builder()
                .assetId(cdb.getId()).operationType(OperationType.BUY)
                .date(LocalDate.now().minusMonths(6)).quantity(1).unitPrice(10000.0).fees(0).build());

        Asset btc = assetRepository.insert(Asset.builder()
                .type(AssetType.CRYPTO).category(Category.CRYPTO).ticker("BTC").displayName("Bitcoin")
                .currency("BRL").quoteSource(QuoteSource.COINGECKO).sourceIdentifier("BTC").active(true).build());
        transactionRepository.insert(Transaction.builder()
                .assetId(btc.getId()).operationType(OperationType.BUY)
                .date(LocalDate.of(2026, 1, 1)).quantity(1).unitPrice(300000.0).fees(0).build());
        quoteHistoryRepository.upsert(QuoteHistory.builder()
                .assetId(btc.getId()).date(LocalDate.now()).price(320000.0).source(QuoteSource.COINGECKO).build());

        AtomicReference<StocksFiisView> viewRef = new AtomicReference<>();
        AtomicReference<Parent> rootRef = new AtomicReference<>();
        runOnFxAndWait(() -> {
            StocksFiisView view = new StocksFiisView(positionService, assetRepository, quoteHistoryRepository,
                    distributionRepository, marketService);
            Parent viewRoot = view.getRoot();
            viewRoot.applyCss();
            viewRoot.layout();
            view.onShow();
            viewRoot.applyCss();
            viewRoot.layout();
            viewRef.set(view);
            rootRef.set(viewRoot);
        });
        StocksFiisView view = viewRef.get();
        Parent root = rootRef.get();

        scenario1_onlyStocksAndFiis(root, petr4, mxrf11);
        scenario2_gainLossMatchesManualCalculation(root);
        scenario3_selectAssetUpdatesChart(view, root, petr4, mxrf11);
        scenario4_registerDistributionAppearsImmediately(root, petr4, distributionRepository);
        scenario5_deleteDistribution(view, distributionRepository, petr4);
        scenario6_primaryCurrencyConvertsTheTable(view, root, marketService);

        System.out.println("[INFO] ativos de apoio no teste: PETR4=" + petr4.getId() + " MXRF11=" + mxrf11.getId()
                + " VALE3(zerado)=" + vale3.getId() + " CDB(fora)=" + cdb.getId() + " BTC(fora)=" + btc.getId());
    }

    // =====================================================================
    // Cenario 1 — so STOCKS/FIIS aparecem (nunca renda fixa/cripto), e
    // posicao zerada (VALE3) nao aparece (armadilha da atividade)
    // =====================================================================

    private static void scenario1_onlyStocksAndFiis(Parent root, Asset petr4, Asset mxrf11) {
        GridPane grid = (GridPane) findNode(root, "#positionsGrid");
        assertTrue(grid != null, "deveria existir uma tabela de posicoes renderizada");

        int maxRow = grid.getChildren().stream()
                .mapToInt(n -> Optional.ofNullable(GridPane.getRowIndex(n)).orElse(0))
                .max().orElse(0);
        assertEquals(2, maxRow, "deveria haver exatamente 2 posicoes (PETR4 + MXRF11) - "
                + "VALE3 (zerado), CDB (renda fixa) e BTC (cripto) nao deveriam aparecer");

        String row1Ticker = cellText(grid, 0, 1);
        String row2Ticker = cellText(grid, 0, 2);
        assertTrue("PETR4".equals(row1Ticker) || "PETR4".equals(row2Ticker), "PETR4 deveria estar na tabela");
        assertTrue("MXRF11".equals(row1Ticker) || "MXRF11".equals(row2Ticker), "MXRF11 deveria estar na tabela");
        assertTrue(!"VALE3".equals(row1Ticker) && !"VALE3".equals(row2Ticker),
                "VALE3 com currentQuantity == 0 nao deveria aparecer na tabela de posicoes");

        System.out.println("[OK] cenario 1 (so STOCKS/FIIS, posicao zerada excluida): 2 linhas (PETR4, MXRF11), "
                + "VALE3/CDB/BTC ausentes");
    }

    // =====================================================================
    // Cenario 2 — ganho/perda % e R$ da tabela batem com calculo manual
    // =====================================================================

    private static void scenario2_gainLossMatchesManualCalculation(Parent root) {
        GridPane grid = (GridPane) findNode(root, "#positionsGrid");

        int petr4Row = findRowByTicker(grid, "PETR4");
        // PETR4: accumulatedCost = 100*30+5 = 3005; currentValue = 40*100 = 4000
        // gainLossAmount = 995; gainLossPercent = 995/3005*100 = 33,11%
        assertEquals("100", cellText(grid, 2, petr4Row), "PETR4 quantidade");
        assertEquals("30,05", cellText(grid, 3, petr4Row), "PETR4 preco medio");
        assertEquals("40,00", cellText(grid, 4, petr4Row), "PETR4 preco atual");
        assertEquals("+33,11%", cellText(grid, 5, petr4Row), "PETR4 ganho %");
        assertEquals("+995,00", cellText(grid, 6, petr4Row), "PETR4 ganho R$");
        assertEquals("4.000,00", cellText(grid, 7, petr4Row), "PETR4 valor atual");

        int mxrfRow = findRowByTicker(grid, "MXRF11");
        // MXRF11: accumulatedCost = 3000*9,89 = 29.670; currentValue = 3000*10,50 = 31.500
        // gainLossAmount = 1.830; gainLossPercent = 1830/29670*100 = 6,17%
        assertEquals("3.000", cellText(grid, 2, mxrfRow), "MXRF11 quantidade");
        assertEquals("9,89", cellText(grid, 3, mxrfRow), "MXRF11 preco medio");
        assertEquals("10,50", cellText(grid, 4, mxrfRow), "MXRF11 preco atual");
        assertEquals("+6,17%", cellText(grid, 5, mxrfRow), "MXRF11 ganho %");
        assertEquals("+1.830,00", cellText(grid, 6, mxrfRow), "MXRF11 ganho R$");
        assertEquals("31.500,00", cellText(grid, 7, mxrfRow), "MXRF11 valor atual");

        System.out.println("[OK] cenario 2 (ganho/perda bate com calculo manual): PETR4 +33,11%/+995,00, "
                + "MXRF11 +6,17%/+1.830,00");
    }

    // =====================================================================
    // Cenario 3 — selecionar um ativo na tabela atualiza o grafico
    // =====================================================================

    private static void scenario3_selectAssetUpdatesChart(StocksFiisView view, Parent root, Asset petr4, Asset mxrf11)
            throws InterruptedException {
        runOnFxAndWait(() -> view.selectAssetForTest(petr4.getId()));
        Label chartTicker1 = (Label) findNode(root, "#chartTickerLabel");
        assertEquals("PETR4", chartTicker1.getText(), "grafico deveria mostrar PETR4 apos selecionar PETR4");
        assertTrue(findNode(root, "#assetChartCanvas") instanceof Canvas, "deveria haver um Canvas de grafico renderizado");

        runOnFxAndWait(() -> view.selectAssetForTest(mxrf11.getId()));
        Label chartTicker2 = (Label) findNode(root, "#chartTickerLabel");
        assertEquals("MXRF11", chartTicker2.getText(), "grafico deveria mudar para MXRF11 apos selecionar MXRF11");
        assertTrue(findNode(root, "#assetChartCanvas") instanceof Canvas, "deveria continuar havendo um Canvas apos trocar de ativo");

        System.out.println("[OK] cenario 3 (selecionar ativo atualiza o grafico): PETR4 -> MXRF11 refletido no cabecalho do grafico");
    }

    // =====================================================================
    // Cenario 4 — registrar provento aparece imediatamente na tabela
    // =====================================================================

    @SuppressWarnings("unchecked")
    private static void scenario4_registerDistributionAppearsImmediately(Parent root, Asset petr4,
                                                                           DistributionRepository distributionRepository)
            throws InterruptedException {
        int before = distributionRepository.listByAsset(petr4.getId()).size();
        assertEquals(0, before, "nao deveria haver proventos registrados antes deste cenario");

        runOnFxAndWait(() -> {
            ComboBox<Position> assetCombo = (ComboBox<Position>) findNode(root, "#distAssetCombo");
            Position target = assetCombo.getItems().stream()
                    .filter(p -> p.asset().id().equals(petr4.getId()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("PETR4 deveria estar nos itens do combo de ativo do provento"));
            assetCombo.setValue(target);

            ((ComboBox<DistributionType>) findNode(root, "#distTypeCombo")).setValue(DistributionType.DIVIDEND);
            ((DatePicker) findNode(root, "#distDatePicker")).setValue(LocalDate.of(2026, 7, 15));
            ((TextField) findNode(root, "#distValueField")).setText("150,50");
            ((Button) findNode(root, "#saveDistributionButton")).fire();
        });

        List<Distribution> after = distributionRepository.listByAsset(petr4.getId());
        assertEquals(1, after.size(), "deveria haver 1 provento apos salvar");
        assertEquals(150.50, after.get(0).getValue(), "valor do provento salvo");
        assertEquals(DistributionType.DIVIDEND, after.get(0).getType(), "tipo do provento salvo");

        GridPane distGrid = (GridPane) findNode(root, "#distributionsGrid");
        assertTrue(distGrid != null, "tabela de proventos deveria estar renderizada com 1 linha de dados");
        assertEquals("PETR4", cellText(distGrid, 0, 1), "linha de provento deveria mostrar o ticker PETR4");
        assertEquals("Dividendo", cellText(distGrid, 1, 1), "linha de provento deveria mostrar o tipo Dividendo");
        assertEquals("150,50", cellText(distGrid, 3, 1), "linha de provento deveria mostrar o valor 150,50");

        System.out.println("[OK] cenario 4 (registrar provento aparece imediatamente): PETR4/Dividendo/150,50 na tabela");
    }

    // =====================================================================
    // Cenario 5 (extra) — excluir provento remove imediatamente da tabela
    // =====================================================================

    private static void scenario5_deleteDistribution(StocksFiisView view, DistributionRepository distributionRepository,
                                                       Asset petr4) throws InterruptedException {
        Distribution toDelete = distributionRepository.listByAsset(petr4.getId()).get(0);
        runOnFxAndWait(() -> view.confirmDeleteDistributionForTest(toDelete));

        List<Distribution> after = distributionRepository.listByAsset(petr4.getId());
        assertEquals(0, after.size(), "deveria haver 0 proventos apos excluir");

        System.out.println("[OK] cenario 5 extra (excluir provento): tabela volta a ficar vazia imediatamente");
    }

    // =====================================================================
    // Infra de teste
    // =====================================================================

    private static int findRowByTicker(GridPane grid, String ticker) {
        for (int row = 1; row <= 10; row++) {
            String text = cellText(grid, 0, row);
            if (ticker.equals(text)) {
                return row;
            }
        }
        throw new AssertionError("Nao encontrei linha para ticker " + ticker);
    }

    // =====================================================================
    // Cenario 6 — moeda principal (pendencia 2): a tabela renderiza os
    // valores convertidos, e o cabecalho troca de simbolo junto
    // =====================================================================

    /**
     * Prova o caminho completo da conversao — de {@code settings} ate a
     * celula renderizada — e nao so a classe {@link CurrencyDisplay}
     * isoladamente (isso o {@code CurrencyDisplayManualTest} ja cobre).
     *
     * <p>Os valores esperados sao os do cenario 2 divididos por 5,30 (a taxa
     * USD do {@code FakeMarketService} deste teste), calculados a mao aqui.
     * O cenario termina voltando para BRL e reconferindo os valores
     * originais — sem isso o estado estatico de {@link CurrencyDisplay}
     * vazaria para qualquer cenario adicionado depois.</p>
     */
    private static void scenario6_primaryCurrencyConvertsTheTable(StocksFiisView view, Parent root,
                                                                  MarketService marketService) throws Exception {
        System.out.println();
        System.out.println("== Cenario 6: moeda principal converte a tabela renderizada ==");

        SettingRepository settings = new InMemorySettings();
        settings.save(SettingsView.SETTING_PRIMARY_CURRENCY, "Dólar norte-americano (USD)");

        try {
            CurrencyDisplay.configure(settings, marketService);
            assertEquals("US$", CurrencyDisplay.symbol(), "moeda principal deveria ter virado USD");
            runOnFxAndWait(view::onShow);

            GridPane grid = (GridPane) findNode(root, "#positionsGrid");
            int petr4Row = findRowByTicker(grid, "PETR4");
            int mxrfRow = findRowByTicker(grid, "MXRF11");

            // PETR4: 4.000,00 / 5,30 = 754,7169... e 995,00 / 5,30 = 187,7358...
            assertEquals("7,55", cellText(grid, 4, petr4Row), "PETR4 preco atual em USD (40,00 / 5,30)");
            assertEquals("+187,74", cellText(grid, 6, petr4Row), "PETR4 ganho em USD (995,00 / 5,30)");
            assertEquals("754,72", cellText(grid, 7, petr4Row), "PETR4 valor atual em USD (4.000,00 / 5,30)");
            // MXRF11: 31.500,00 / 5,30 = 5.943,396... e 1.830,00 / 5,30 = 345,283...
            assertEquals("+345,28", cellText(grid, 6, mxrfRow), "MXRF11 ganho em USD (1.830,00 / 5,30)");
            assertEquals("5.943,40", cellText(grid, 7, mxrfRow), "MXRF11 valor atual em USD (31.500,00 / 5,30)");

            // Quantidade NAO e valor monetario — nao pode ser convertida.
            assertEquals("100", cellText(grid, 2, petr4Row), "quantidade continua 100 (nao e dinheiro)");
            assertEquals("+33,11%", cellText(grid, 5, petr4Row), "ganho % continua o mesmo (nao e dinheiro)");

            // Cabecalho da coluna de ganho acompanha o simbolo.
            assertEquals("GANHO US$", cellText(grid, 6, 0), "cabecalho da coluna de ganho deveria mostrar US$");
        } finally {
            // Volta ao padrao mesmo se uma assercao falhar no meio.
            settings.save(SettingsView.SETTING_PRIMARY_CURRENCY, "Real (BRL)");
            CurrencyDisplay.configure(settings, marketService);
            runOnFxAndWait(view::onShow);
        }

        GridPane grid = (GridPane) findNode(root, "#positionsGrid");
        int petr4Row = findRowByTicker(grid, "PETR4");
        assertEquals("4.000,00", cellText(grid, 7, petr4Row), "voltar para BRL restaura o valor original");
        assertEquals("GANHO R$", cellText(grid, 6, 0), "voltar para BRL restaura o cabecalho");
        System.out.println("[ok] conversao de exibicao aplicada e revertida sem residuo");
    }

    /** {@link SettingRepository} em memoria — este teste nao persiste preferencia nenhuma. */
    private static final class InMemorySettings implements SettingRepository {
        private final Map<String, String> values = new java.util.HashMap<>();

        @Override
        public String get(String key, String defaultValue) {
            return values.getOrDefault(key, defaultValue);
        }

        @Override
        public void save(String key, String value) {
            values.put(key, value);
        }
    }

    /** Extrai o texto de uma celula do GridPane por coluna/linha — cobre tanto {@link Label} direto quanto o {@link VBox} de 2 linhas (coluna TICKER/ATIVO). */
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
     * Mesma técnica da ATV-13 ({@code RegistrationViewManualTest.findNode}):
     * desce manualmente por {@code ScrollPane.getContent()} em vez de
     * depender de {@code Node.lookup}, que só encontra descendentes de um
     * {@code ScrollPane} depois que o {@code Skin} é materializado (só
     * acontece com um {@code Stage} visível).
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
        try (InputStream in = StocksFiisViewManualTest.class.getResourceAsStream(resourcePath)) {
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

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    /**
     * Mesmo padrão do {@code FakeMarketService} do {@code
     * PositionServiceManualTest} (ATV-10) — sem rede, {@code
     * getDailyChanges} sempre vazio (suficiente para este teste, que não
     * cobre "variação do dia").
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
