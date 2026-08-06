package com.investimento.app.ui.screens;

import com.investimento.app.api.hgbrasil.model.Currency;
import com.investimento.app.api.hgbrasil.model.IndicatorPoint;
import com.investimento.app.api.hgbrasil.model.MacroSnapshot;
import com.investimento.app.data.model.Asset;
import com.investimento.app.data.model.AssetType;
import com.investimento.app.data.model.Category;
import com.investimento.app.data.model.OperationType;
import com.investimento.app.data.model.QuoteHistory;
import com.investimento.app.data.model.QuoteSource;
import com.investimento.app.data.model.Transaction;
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
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Teste manual (sem JUnit, mesmo padrão de {@code *ManualTest} das
 * atividades anteriores) da tela Câmbio e Cripto (ATV-16).
 *
 * <p><strong>Escopo desta execução</strong>: por instrução explícita, nenhuma
 * etapa de validação visual/manual ({@code mvn javafx:run} + observação
 * humana) foi executada — só os cenários abaixo, verificáveis inteiramente
 * por código, cobrindo os itens do critério de aceite da ATV-16 (câmbio e
 * cripto separados/identificáveis com a fonte correta, gráfico de câmbio
 * curto/vazio para ativo recém-cadastrado vs. gráfico de cripto já com
 * histórico) mais alguns cenários extras de rigor (ganho/perda batendo com
 * cálculo manual, filtro Ambos/Câmbio/Cripto).</p>
 *
 * <p>Roda contra um SQLite em memória isolado, sem tocar rede nenhuma — usa
 * um {@code FakeMarketService} fixo (mesmo padrão do
 * {@code PositionServiceManualTest}/ {@code StocksFiisViewManualTest}) para
 * a taxa de câmbio USD/BRL (câmbio não depende de {@code quote_history} para
 * o valor atual, só para o gráfico — ver {@code PositionServiceImpl}) e para
 * a variação do dia.</p>
 */
public final class ForexCryptoViewManualTest {

    private ForexCryptoViewManualTest() {
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
            System.out.println("TODOS OS CENARIOS PASSARAM (ATV-16)");
        }
    }

    private static void runAllScenarios() throws Exception {
        Connection connection = openInMemoryDatabase();
        AssetRepository assetRepository = new AssetRepositoryImpl(connection);
        TransactionRepository transactionRepository = new TransactionRepositoryImpl(connection);
        QuoteHistoryRepository quoteHistoryRepository = new QuoteHistoryRepositoryImpl(connection);
        RateHistoryRepository rateHistoryRepository = new RateHistoryRepositoryImpl(connection);
        IndicatorHistoryRepository indicatorHistoryRepository = new IndicatorHistoryRepositoryImpl(connection);

        // BTC recebe variacao do dia (simula um updateQuotes ja rodado para
        // cripto); USD nao recebe (simula cambio ainda sem atualizacao do dia)
        // - exercita os dois caminhos (kpi-footer-gain/loss x kpi-footer-neutral).
        MarketService marketService = new FakeMarketService();
        PositionService positionService = new PositionServiceImpl(assetRepository, transactionRepository,
                marketService, quoteHistoryRepository, rateHistoryRepository, indicatorHistoryRepository);

        // ---- Massa de dados ----
        // USD (FOREX): 1000 un. @ 5,00 (investido 5.000,00), taxa de cambio
        // fake 5,35 -> atual 5.350,00, ganho +350,00 (+7,00%). So 1 linha em
        // quote_history (< 2 pontos) - simula ativo de cambio recem-cadastrado,
        // sem seed (armadilha/criterio de aceite da ATV-16: grafico de cambio
        // comeca vazio/curto, isso e esperado).
        Asset usd = assetRepository.insert(Asset.builder()
                .type(AssetType.FOREIGN_CURRENCY).category(Category.FOREX).ticker(null).displayName("Dólar americano")
                .currency("USD").quoteSource(QuoteSource.HGBRASIL).sourceIdentifier("FOREX:USDBRL").active(true).build());
        transactionRepository.insert(Transaction.builder()
                .assetId(usd.getId()).operationType(OperationType.BUY)
                .date(LocalDate.of(2026, 1, 10)).quantity(1000).unitPrice(5.00).fees(0).build());
        quoteHistoryRepository.upsert(QuoteHistory.builder()
                .assetId(usd.getId()).date(LocalDate.now()).price(5.35).source(QuoteSource.HGBRASIL).build());

        // BTC (CRYPTO): 0,05 un. @ 250.000 (investido 12.500,00), cotacao atual
        // 320.000 (via quote_history, nao via HG Brasil) -> atual 16.000,00,
        // ganho +3.500,00 (+28,00%). 2 linhas em quote_history - simula o seed
        // inicial da CoinGecko ja aplicado no cadastro (ATV-06).
        Asset btc = assetRepository.insert(Asset.builder()
                .type(AssetType.CRYPTO).category(Category.CRYPTO).ticker("BTC").displayName("Bitcoin")
                .currency("BRL").quoteSource(QuoteSource.COINGECKO).sourceIdentifier("BTC").active(true).build());
        transactionRepository.insert(Transaction.builder()
                .assetId(btc.getId()).operationType(OperationType.BUY)
                .date(LocalDate.of(2026, 1, 1)).quantity(0.05).unitPrice(250000.0).fees(0).build());
        quoteHistoryRepository.upsert(QuoteHistory.builder()
                .assetId(btc.getId()).date(LocalDate.of(2026, 3, 1)).price(300000.0).source(QuoteSource.COINGECKO).build());
        quoteHistoryRepository.upsert(QuoteHistory.builder()
                .assetId(btc.getId()).date(LocalDate.now()).price(320000.0).source(QuoteSource.COINGECKO).build());

        // PETR4 (STOCK): categoria fora do escopo desta tela - nunca deveria aparecer.
        Asset petr4 = assetRepository.insert(Asset.builder()
                .type(AssetType.STOCK).category(Category.STOCKS).ticker("PETR4").displayName("Petrobras PN")
                .currency("BRL").quoteSource(QuoteSource.BRAPI).sourceIdentifier("PETR4").active(true).build());

        AtomicReference<ForexCryptoView> viewRef = new AtomicReference<>();
        AtomicReference<Parent> rootRef = new AtomicReference<>();
        runOnFxAndWait(() -> {
            ForexCryptoView view = new ForexCryptoView(positionService, assetRepository, quoteHistoryRepository, marketService, null);
            Parent viewRoot = view.getRoot();
            viewRoot.applyCss();
            viewRoot.layout();
            view.onShow();
            viewRoot.applyCss();
            viewRoot.layout();
            viewRef.set(view);
            rootRef.set(viewRoot);
        });
        ForexCryptoView view = viewRef.get();
        Parent root = rootRef.get();

        scenario1_onlyForexAndCryptoAppear(root);
        scenario2_correctSourcePerRow(root);
        scenario3_gainLossMatchesManualCalculation(root);
        scenario4_forexChartStartsEmptyCryptoChartHasHistory(view, root, usd, btc);
        scenario5_filterPillsFilterTable(view, root);

        System.out.println("[INFO] ativos de apoio no teste: USD=" + usd.getId() + " BTC=" + btc.getId()
                + " PETR4(fora)=" + petr4.getId());
    }

    // =====================================================================
    // Cenario 1 — so FOREX/CRYPTO aparecem (nunca acoes/FIIs/renda fixa)
    // =====================================================================

    private static void scenario1_onlyForexAndCryptoAppear(Parent root) {
        GridPane grid = (GridPane) findNode(root, "#positionsGrid");
        assertTrue(grid != null, "deveria existir uma tabela de posicoes renderizada");

        int maxRow = grid.getChildren().stream()
                .mapToInt(n -> Optional.ofNullable(GridPane.getRowIndex(n)).orElse(0))
                .max().orElse(0);
        assertEquals(2, maxRow, "deveria haver exatamente 2 posicoes (USD + BTC) - PETR4 nao deveria aparecer");

        System.out.println("[OK] cenario 1 (so FOREX/CRYPTO aparecem): 2 linhas (USD, BTC), PETR4 ausente");
    }

    // =====================================================================
    // Cenario 2 — cada categoria com a fonte de dado correta (BTC != HG Brasil)
    // =====================================================================

    private static void scenario2_correctSourcePerRow(Parent root) {
        GridPane grid = (GridPane) findNode(root, "#positionsGrid");

        int usdRow = findRowByPrimaryTextContaining(grid, "USD");
        int btcRow = findRowByPrimaryTextContaining(grid, "BTC");

        String usdSource = cellSubText(grid, 0, usdRow);
        String btcSource = cellSubText(grid, 0, btcRow);
        assertEquals("HG Brasil", usdSource, "linha de cambio (USD) deveria indicar fonte HG Brasil");
        assertEquals("CoinGecko", btcSource, "linha de cripto (BTC) deveria indicar fonte CoinGecko - NUNCA HG Brasil");

        String usdBadge = cellText(grid, 1, usdRow);
        String btcBadge = cellText(grid, 1, btcRow);
        assertEquals("Câmbio", usdBadge, "USD deveria ter o badge Câmbio");
        assertEquals("Cripto", btcBadge, "BTC deveria ter o badge Cripto");

        System.out.println("[OK] cenario 2 (fonte correta por categoria): USD=HG Brasil/Câmbio, BTC=CoinGecko/Cripto");
    }

    // =====================================================================
    // Cenario 3 — ganho/perda da tabela bate com calculo manual
    // =====================================================================

    private static void scenario3_gainLossMatchesManualCalculation(Parent root) {
        GridPane grid = (GridPane) findNode(root, "#positionsGrid");

        int usdRow = findRowByPrimaryTextContaining(grid, "USD");
        // USD: investido = 1000*5,00 = 5.000,00; atual = 1000*5,35 = 5.350,00;
        // ganho = 350,00 (+7,00%).
        assertEquals("1.000,00", cellText(grid, 2, usdRow), "USD quantidade");
        assertEquals("5,35", cellText(grid, 3, usdRow), "USD cotacao atual");
        assertEquals("5.000,00", cellText(grid, 4, usdRow), "USD investido");
        assertEquals("5.350,00", cellText(grid, 5, usdRow), "USD atual");
        assertEquals("+7,00%", cellText(grid, 6, usdRow), "USD ganho %");

        int btcRow = findRowByPrimaryTextContaining(grid, "BTC");
        // BTC: investido = 0,05*250.000 = 12.500,00; atual = 0,05*320.000 = 16.000,00;
        // ganho = 3.500,00 (+28,00%).
        assertEquals("0,050000", cellText(grid, 2, btcRow), "BTC quantidade");
        assertEquals("320.000", cellText(grid, 3, btcRow), "BTC cotacao atual");
        assertEquals("12.500,00", cellText(grid, 4, btcRow), "BTC investido");
        assertEquals("16.000,00", cellText(grid, 5, btcRow), "BTC atual");
        assertEquals("+28,00%", cellText(grid, 6, btcRow), "BTC ganho %");

        System.out.println("[OK] cenario 3 (ganho/perda bate com calculo manual): USD +7,00%, BTC +28,00%");
    }

    // =====================================================================
    // Cenario 4 — grafico de cambio comeca vazio/curto, cripto ja tem historico
    // =====================================================================

    private static void scenario4_forexChartStartsEmptyCryptoChartHasHistory(ForexCryptoView view, Parent root,
                                                                                Asset usd, Asset btc)
            throws InterruptedException {
        runOnFxAndWait(() -> view.selectAssetForTest(usd.getId()));
        Label chartTickerUsd = (Label) findNode(root, "#chartTickerLabel");
        assertEquals("USD", chartTickerUsd.getText(), "grafico deveria mostrar USD apos selecionar o cambio");
        assertTrue(findNode(root, "#assetChartCanvas") == null,
                "cambio com < 2 pontos de historico NAO deveria desenhar um Canvas ainda (esperado, nao e bug)");

        runOnFxAndWait(() -> view.selectAssetForTest(btc.getId()));
        Label chartTickerBtc = (Label) findNode(root, "#chartTickerLabel");
        assertEquals("BTC", chartTickerBtc.getText(), "grafico deveria mudar para BTC apos selecionar a cripto");
        assertTrue(findNode(root, "#assetChartCanvas") instanceof Canvas,
                "cripto com historico seedado (>= 2 pontos) deveria desenhar um Canvas");

        System.out.println("[OK] cenario 4 (grafico de cambio vazio/curto x cripto com historico): "
                + "USD sem Canvas (esperado), BTC com Canvas");
    }

    // =====================================================================
    // Cenario 5 (extra) — pills Ambos/Câmbio/Cripto filtram a tabela
    // =====================================================================

    private static void scenario5_filterPillsFilterTable(ForexCryptoView view, Parent root) throws InterruptedException {
        runOnFxAndWait(() -> ((Label) findNode(root, "#filterPillFOREX")).getOnMouseClicked()
                .handle(new javafx.scene.input.MouseEvent(javafx.scene.input.MouseEvent.MOUSE_CLICKED,
                        0, 0, 0, 0, javafx.scene.input.MouseButton.PRIMARY, 1,
                        true, true, true, true, true, true, true, true, true, true, null)));
        GridPane gridForex = (GridPane) findNode(root, "#positionsGrid");
        int maxRowForex = gridForex.getChildren().stream()
                .mapToInt(n -> Optional.ofNullable(GridPane.getRowIndex(n)).orElse(0)).max().orElse(0);
        assertEquals(1, maxRowForex, "filtro Câmbio deveria mostrar so 1 linha (USD)");

        runOnFxAndWait(() -> ((Label) findNode(root, "#filterPillCRYPTO")).getOnMouseClicked()
                .handle(new javafx.scene.input.MouseEvent(javafx.scene.input.MouseEvent.MOUSE_CLICKED,
                        0, 0, 0, 0, javafx.scene.input.MouseButton.PRIMARY, 1,
                        true, true, true, true, true, true, true, true, true, true, null)));
        GridPane gridCrypto = (GridPane) findNode(root, "#positionsGrid");
        int maxRowCrypto = gridCrypto.getChildren().stream()
                .mapToInt(n -> Optional.ofNullable(GridPane.getRowIndex(n)).orElse(0)).max().orElse(0);
        assertEquals(1, maxRowCrypto, "filtro Cripto deveria mostrar so 1 linha (BTC)");

        runOnFxAndWait(() -> ((Label) findNode(root, "#filterPillALL")).getOnMouseClicked()
                .handle(new javafx.scene.input.MouseEvent(javafx.scene.input.MouseEvent.MOUSE_CLICKED,
                        0, 0, 0, 0, javafx.scene.input.MouseButton.PRIMARY, 1,
                        true, true, true, true, true, true, true, true, true, true, null)));
        GridPane gridAll = (GridPane) findNode(root, "#positionsGrid");
        int maxRowAll = gridAll.getChildren().stream()
                .mapToInt(n -> Optional.ofNullable(GridPane.getRowIndex(n)).orElse(0)).max().orElse(0);
        assertEquals(2, maxRowAll, "filtro Ambos deveria voltar a mostrar as 2 linhas");

        System.out.println("[OK] cenario 5 extra (filtro Ambos/Câmbio/Cripto): 1/1/2 linhas conforme esperado");
    }

    // =====================================================================
    // Infra de teste
    // =====================================================================

    private static int findRowByPrimaryTextContaining(GridPane grid, String needle) {
        for (int row = 1; row <= 10; row++) {
            String text = cellText(grid, 0, row);
            if (text != null && text.contains(needle)) {
                return row;
            }
        }
        throw new AssertionError("Nao encontrei linha contendo " + needle);
    }

    /** Texto da 1a linha da celula (Label direto, ou 1o filho de VBox/HBox). */
    private static String cellText(GridPane grid, int col, int row) {
        Node cell = findCell(grid, col, row);
        if (cell == null) {
            return null;
        }
        if (cell instanceof Label label) {
            return label.getText();
        }
        if (cell instanceof VBox vbox && !vbox.getChildren().isEmpty() && vbox.getChildren().get(0) instanceof Label label) {
            return label.getText();
        }
        if (cell instanceof HBox hbox && !hbox.getChildren().isEmpty() && hbox.getChildren().get(0) instanceof Label label) {
            return label.getText();
        }
        return null;
    }

    /** Texto da 2a linha da celula (so existe em celulas VBox de 2 linhas, ex.: ATIVO e GANHO/PERDA). */
    private static String cellSubText(GridPane grid, int col, int row) {
        Node cell = findCell(grid, col, row);
        if (cell instanceof VBox vbox && vbox.getChildren().size() > 1 && vbox.getChildren().get(1) instanceof Label label) {
            return label.getText();
        }
        return null;
    }

    private static Node findCell(GridPane grid, int col, int row) {
        for (Node child : grid.getChildrenUnmodifiable()) {
            int c = Optional.ofNullable(GridPane.getColumnIndex(child)).orElse(0);
            int r = Optional.ofNullable(GridPane.getRowIndex(child)).orElse(0);
            if (c == col && r == row) {
                return child;
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
     * Mesma técnica das ATV-13/14/15 ({@code findNode}): desce manualmente
     * por {@code ScrollPane.getContent()} em vez de depender de
     * {@code Node.lookup}, que só encontra descendentes de um
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
        try (InputStream in = ForexCryptoViewManualTest.class.getResourceAsStream(resourcePath)) {
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
     * Mesmo padrão do {@code FakeMarketService} das ATV-10/14/15 — sem rede.
     * {@code getMacroSnapshot} devolve uma taxa fixa de USD/BRL (a única que
     * este teste precisa, câmbio depende dela para o valor atual, ver
     * {@code PositionServiceImpl}). {@code getDailyChanges} devolve variação
     * só para BTC (simula um {@code updateQuotes} já rodado para cripto, mas
     * não para câmbio nesta sessão) — exercita tanto o caminho com dado do
     * dia quanto o caminho "sem dado do dia" da tela.
     */
    private static class FakeMarketService implements MarketService {
        @Override
        public MacroSnapshot getMacroSnapshot() {
            return new MacroSnapshot(Map.of("USD", new Currency("USD", "Dollar", 5.35, null, 0.10)), Map.of(), null);
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
            Asset btc = assets.stream().filter(a -> "BTC".equals(a.getTicker())).findFirst().orElse(null);
            return btc == null ? Map.of() : Map.of(btc.getId(), 4.5);
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
