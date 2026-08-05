package com.investimento.app.ui.screens;

import com.investimento.app.api.brapi.BrapiClient;
import com.investimento.app.api.brapi.BrapiClientImpl;
import com.investimento.app.api.coingecko.CoinGeckoClient;
import com.investimento.app.api.coingecko.CoinGeckoClientImpl;
import com.investimento.app.data.model.AssetType;
import com.investimento.app.data.model.Benchmark;
import com.investimento.app.data.model.Category;
import com.investimento.app.data.model.QuoteSource;
import com.investimento.app.dto.AssetDTO;
import com.investimento.app.dto.TransactionDTO;
import com.investimento.app.repository.AssetRepository;
import com.investimento.app.repository.AssetRepositoryImpl;
import com.investimento.app.repository.TransactionRepository;
import com.investimento.app.repository.TransactionRepositoryImpl;
import com.investimento.app.service.AssetService;
import com.investimento.app.service.AssetServiceImpl;
import com.investimento.app.service.TransactionService;
import com.investimento.app.service.TransactionServiceImpl;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.GridPane;
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
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Teste manual (sem JUnit, mesmo padrão de {@code *ManualTest} das
 * atividades anteriores) da tela Cadastro de Ativos e Transações (ATV-13).
 *
 * <p><strong>Escopo desta execução</strong>: por instrução explícita do
 * usuário, nenhuma etapa de validação visual/manual (ex.: {@code mvn
 * javafx:run} + observação humana da tela) foi executada — só os cenários
 * abaixo, verificáveis inteiramente por código/lógica:</p>
 * <ol>
 *   <li>Autocomplete de ticker com debounce — digitar "PETR4" tecla a tecla
 *       dispara <strong>1 única</strong> requisição à brapi.dev (contador de
 *       {@link BrapiClientImpl#getRequestCount()}), não uma por tecla.</li>
 *   <li>Detecção de ticker renomeado ({@link RegistrationView#isTickerRenamed}) —
 *       BIDI11→INBR32 e casos negativos.</li>
 *   <li>Campos condicionais — os 7 {@link AssetType} mostram a seção
 *       condicional certa (e só ela) no {@code conditionalContainer}.</li>
 *   <li>Cadastro dos 7 tipos de ativo através do formulário real (fim a fim,
 *       contra a API real), confirmando {@code category}/{@code quoteSource}/
 *       {@code ticker} derivados corretos em cada caso.</li>
 *   <li>CRUD de transação (criar/editar/excluir) refletindo imediatamente na
 *       tabela renderizada.</li>
 * </ol>
 *
 * <p>Roda contra um SQLite em memória isolado (mesmo padrão de
 * {@code RepositoryManualTest}/{@code AssetServiceManualTest}) e as APIs
 * reais da brapi.dev/CoinGecko (sem {@code MarketService} — construído com
 * {@code null}, evitando qualquer chamada de rede de seed de histórico
 * disparada em background por {@code AssetServiceImpl.create}).</p>
 */
public final class RegistrationViewManualTest {

    private RegistrationViewManualTest() {
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
            System.out.println("TODOS OS CENARIOS PASSARAM (ATV-13)");
        }
    }

    private static void runAllScenarios() throws Exception {
        Connection connection = openInMemoryDatabase();
        AssetRepository assetRepository = new AssetRepositoryImpl(connection);
        TransactionRepository transactionRepository = new TransactionRepositoryImpl(connection);
        BrapiClient brapiClient = new BrapiClientImpl();
        BrapiClientImpl brapiClientImpl = (BrapiClientImpl) brapiClient;
        CoinGeckoClient coinGeckoClient = new CoinGeckoClientImpl();
        // marketService = null: nao precisamos de seed de historico em
        // background para este teste, e isso evita rede extra irrelevante ao
        // escopo desta atividade (AssetServiceImpl ja trata marketService
        // nulo como "nao semear", ver ATV-08).
        AssetService assetService = new AssetServiceImpl(assetRepository, brapiClient, coinGeckoClient, null);
        TransactionService transactionService = new TransactionServiceImpl(transactionRepository, assetRepository);

        AtomicReference<RegistrationView> viewRef = new AtomicReference<>();
        AtomicReference<Parent> rootRef = new AtomicReference<>();
        runOnFxAndWait(() -> {
            RegistrationView view = new RegistrationView(assetService, transactionService, brapiClient, coinGeckoClient);
            Parent viewRoot = view.getRoot();
            // Forca a criacao do Skin dos 2 ScrollPane da tela (root ->
            // contentBody; card de ativos -> assetListBody) - sem isto,
            // Node.lookup() nao consegue descer para dentro do conteudo do
            // ScrollPane (nao materializado como filho de verdade ate o
            // Skin existir), mesmo sem nenhum Stage/Scene visivel.
            viewRoot.applyCss();
            viewRoot.layout();
            viewRef.set(view);
            rootRef.set(viewRoot);
        });
        RegistrationView view = viewRef.get();
        Parent root = rootRef.get();

        scenario1_debounce(root, brapiClientImpl);
        scenario2_tickerRenameDetection();
        scenario3_conditionalFieldsVisibility(root);
        scenario4_sevenAssetTypesCreate(root, assetService);
        scenario5_transactionCrud(view, root, assetService, transactionService);
    }

    // =====================================================================
    // Cenario 1 — debounce do autocomplete de ticker
    // =====================================================================

    private static void scenario1_debounce(Parent root, BrapiClientImpl brapiClientImpl) throws InterruptedException {
        runOnFxAndWait(() -> comboBox(root, "#typeCombo", AssetType.class).setValue(AssetType.STOCK));
        Thread.sleep(50);

        int before = brapiClientImpl.getRequestCount();

        // Digita "PETR4" tecla a tecla, tudo dentro da mesma execucao na FX
        // thread (equivalente a digitar rapido, bem dentro da janela de
        // 300ms de debounce) - so a ULTIMA mudanca de texto deveria
        // efetivamente disparar uma busca, depois do debounce expirar.
        runOnFxAndWait(() -> {
            TextField tickerField = textField(root, "#tickerField");
            tickerField.setText("P");
            tickerField.setText("PE");
            tickerField.setText("PET");
            tickerField.setText("PETR");
            tickerField.setText("PETR4");
        });

        // Menos que o debounce (300ms) - ainda nao deveria ter despachado nada.
        Thread.sleep(150);
        int duringDebounce = brapiClientImpl.getRequestCount();
        assertEquals(before, duringDebounce,
                "nao deveria ter disparado nenhuma requisicao ainda (dentro da janela de debounce de 300ms)");

        // Espera o debounce expirar + tempo de rede real.
        Thread.sleep(3000);
        int after = brapiClientImpl.getRequestCount();
        assertEquals(before + 1, after,
                "5 teclas digitadas deveriam disparar exatamente 1 requisicao, nao uma por tecla");

        System.out.println("[OK] cenario 1 (debounce): 5 teclas digitadas -> " + (after - before)
                + " requisicao(oes) de rede (esperado: 1)");
    }

    // =====================================================================
    // Cenario 2 — deteccao de ticker renomeado
    // =====================================================================

    private static void scenario2_tickerRenameDetection() {
        assertTrue(RegistrationView.isTickerRenamed("BIDI11", "INBR32"),
                "BIDI11 -> INBR32 deveria ser detectado como ticker renomeado");
        assertTrue(!RegistrationView.isTickerRenamed("PETR4", "PETR4"),
                "PETR4 -> PETR4 (sem mudanca) nao deveria contar como renomeado");
        assertTrue(!RegistrationView.isTickerRenamed("petr4", "PETR4"),
                "comparacao deveria ser case-insensitive");
        assertTrue(!RegistrationView.isTickerRenamed(null, null),
                "nulo nos dois lados (CRYPTO/FOREIGN_CURRENCY/FIXED_INCOME) nao deveria contar como renomeado");
        assertTrue(!RegistrationView.isTickerRenamed("", "ALGO"),
                "ticker digitado vazio nao deveria contar como renomeado");

        System.out.println("[OK] cenario 2 (ticker renomeado): BIDI11->INBR32 detectado, casos negativos corretos");
    }

    // =====================================================================
    // Cenario 3 — campos condicionais por AssetType
    // =====================================================================

    private static void scenario3_conditionalFieldsVisibility(Parent root) throws InterruptedException {
        for (AssetType type : AssetType.values()) {
            runOnFxAndWait(() -> comboBox(root, "#typeCombo", AssetType.class).setValue(type));
            Thread.sleep(20);

            String expectedId = switch (type) {
                case STOCK, FII, ETF, BDR -> "tickerSection";
                case CRYPTO -> "cryptoSection";
                case FOREIGN_CURRENCY -> "forexSection";
                case FIXED_INCOME -> "fixedIncomeSection";
            };

            VBox conditionalContainer = (VBox) findNode(root, "#conditionalContainer");
            assertEquals(1, conditionalContainer.getChildren().size(),
                    "deveria haver exatamente 1 secao condicional visivel para " + type);
            String actualId = conditionalContainer.getChildren().get(0).getId();
            assertEquals(expectedId, actualId, "secao condicional errada para tipo " + type);
        }

        System.out.println("[OK] cenario 3 (campos condicionais): os 7 tipos mostram a secao certa, e so ela");
    }

    // =====================================================================
    // Cenario 4 — cadastro dos 7 tipos de ativo via UI (fim a fim)
    // =====================================================================

    private static void scenario4_sevenAssetTypesCreate(Parent root, AssetService assetService) throws Exception {
        // STOCK, ETF e BDR reaproveitam o mesmo ticker conhecido-bom (PETR4)
        // - AssetServiceImpl valida so a EXISTENCIA do ticker via
        // BrapiClient.getQuote, o mesmo caminho de codigo para os 4 tipos
        // B3 (STOCK/FII/ETF/BDR); o objetivo aqui e confirmar que a
        // categoria/fonte derivada bate por TIPO selecionado no formulario,
        // nao que o ticker escolhido e tematicamente um ETF/BDR de verdade.
        createAsset(root, assetService, AssetType.STOCK, "Petrobras Teste ATV13", () -> {
            textField(root, "#tickerField").setText("PETR4");
        });
        assertAssetDerived(assetService, "Petrobras Teste ATV13", Category.STOCKS, QuoteSource.BRAPI, "PETR4");

        createAsset(root, assetService, AssetType.FII, "Maxi Renda Teste ATV13", () -> {
            textField(root, "#tickerField").setText("MXRF11");
        });
        assertAssetDerived(assetService, "Maxi Renda Teste ATV13", Category.FIIS, QuoteSource.BRAPI, "MXRF11");

        createAsset(root, assetService, AssetType.ETF, "ETF Teste ATV13", () -> {
            textField(root, "#tickerField").setText("PETR4");
        });
        assertAssetDerived(assetService, "ETF Teste ATV13", Category.STOCKS, QuoteSource.BRAPI, "PETR4");

        createAsset(root, assetService, AssetType.BDR, "BDR Teste ATV13", () -> {
            textField(root, "#tickerField").setText("PETR4");
        });
        assertAssetDerived(assetService, "BDR Teste ATV13", Category.STOCKS, QuoteSource.BRAPI, "PETR4");

        createAsset(root, assetService, AssetType.CRYPTO, "Bitcoin Teste ATV13", () -> {
            comboBox(root, "#cryptoCombo", String.class).setValue("BTC");
        });
        assertAssetDerived(assetService, "Bitcoin Teste ATV13", Category.CRYPTO, QuoteSource.COINGECKO, "BTC");

        createAsset(root, assetService, AssetType.FOREIGN_CURRENCY, "Dolar Teste ATV13", () -> {
            comboBox(root, "#forexCombo", String.class).setValue("USD");
        });
        assertAssetDerived(assetService, "Dolar Teste ATV13", Category.FOREX, QuoteSource.HGBRASIL, null);

        createAsset(root, assetService, AssetType.FIXED_INCOME, "CDB Teste ATV13", () -> {
            comboBox(root, "#benchmarkCombo", Benchmark.class).setValue(Benchmark.FIXED_RATE);
            textField(root, "#contractedRateField").setText("12");
            textField(root, "#financialInstitutionField").setText("Banco Teste");
            datePicker(root, "#investmentDatePicker").setValue(LocalDate.now().minusMonths(6));
            datePicker(root, "#maturityDatePicker").setValue(LocalDate.now().plusYears(1));
        });
        assertAssetDerived(assetService, "CDB Teste ATV13", Category.FIXED_INCOME, QuoteSource.NONE, null);

        System.out.println("[OK] cenario 4 (7 tipos de ativo): category/quoteSource/ticker derivados corretos em todos");
    }

    private static void createAsset(Parent root, AssetService assetService, AssetType type, String displayName,
                                     Runnable fieldFiller) throws Exception {
        int before = assetService.listAssets(false).size();
        runOnFxAndWait(() -> {
            comboBox(root, "#typeCombo", AssetType.class).setValue(type);
            textField(root, "#displayNameField").setText(displayName);
            fieldFiller.run();
            button(root, "#saveAssetButton").fire();
        });
        waitForAssetCount(assetService, before + 1, 20_000);
    }

    private static void assertAssetDerived(AssetService assetService, String displayName, Category category,
                                            QuoteSource quoteSource, String expectedTicker) {
        AssetDTO asset = assetService.listAssets(false).stream()
                .filter(a -> displayName.equals(a.displayName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Ativo nao encontrado apos salvar: " + displayName));
        assertEquals(category, asset.category(), "categoria errada para " + displayName);
        assertEquals(quoteSource, asset.quoteSource(), "quoteSource errado para " + displayName);
        if (expectedTicker != null) {
            assertEquals(expectedTicker, asset.ticker(), "ticker errado para " + displayName);
        }
    }

    // =====================================================================
    // Cenario 5 — CRUD de transacao reflete imediatamente na tabela
    // =====================================================================

    private static void scenario5_transactionCrud(RegistrationView view, Parent root, AssetService assetService,
                                                   TransactionService transactionService) throws InterruptedException {
        AssetDTO stock = assetService.listAssets(false).stream()
                .filter(a -> "Petrobras Teste ATV13".equals(a.displayName()))
                .findFirst()
                .orElseThrow();

        runOnFxAndWait(() -> {
            view.onShow(); // popula a lista de ativos (refreshAssets) antes de selecionar
            view.selectAssetForTest(stock);
        });

        // ---- criar ----
        runOnFxAndWait(() -> {
            ((ToggleButton) findNode(root, "#buyToggle")).setSelected(true);
            datePicker(root, "#transactionDatePicker").setValue(LocalDate.of(2026, 3, 12));
            textField(root, "#quantityField").setText("100");
            textField(root, "#unitPriceField").setText("30");
            textField(root, "#feesField").setText("5");
            button(root, "#saveTransactionButton").fire();
        });

        List<TransactionDTO> afterCreate = transactionService.listByAsset(stock.id());
        assertEquals(1, afterCreate.size(), "deveria haver 1 transacao apos criar");
        assertRenderedRowCount(root, 1);

        TransactionDTO created = afterCreate.get(0);
        assertEquals(100.0, created.quantity(), "quantidade da transacao criada");
        assertEquals(30.0, created.unitPrice(), "preco unitario da transacao criada");

        // ---- editar ----
        runOnFxAndWait(() -> view.editTransactionForTest(created));
        runOnFxAndWait(() -> {
            textField(root, "#quantityField").setText("150");
            button(root, "#saveTransactionButton").fire();
        });

        List<TransactionDTO> afterEdit = transactionService.listByAsset(stock.id());
        assertEquals(1, afterEdit.size(), "edicao nao deveria criar uma segunda transacao");
        assertEquals(150.0, afterEdit.get(0).quantity(), "quantidade deveria refletir a edicao (100 -> 150)");
        assertRenderedRowCount(root, 1);

        // ---- excluir ----
        runOnFxAndWait(() -> view.confirmDeleteTransactionForTest(afterEdit.get(0)));

        List<TransactionDTO> afterDelete = transactionService.listByAsset(stock.id());
        assertEquals(0, afterDelete.size(), "deveria haver 0 transacoes apos excluir");
        assertRenderedRowCount(root, 0);

        System.out.println("[OK] cenario 5 (CRUD de transacao): criar/editar/excluir refletem imediatamente na tabela");
    }

    private static void assertRenderedRowCount(Parent root, int expectedDataRows) {
        VBox tableBody = (VBox) findNode(root, "#transactionsTableBody");
        if (expectedDataRows == 0) {
            boolean hasGrid = tableBody.getChildren().stream().anyMatch(n -> "transactionsGrid".equals(n.getId()));
            assertTrue(!hasGrid, "tabela nao deveria ter grid nenhum quando nao ha transacoes");
            return;
        }
        GridPane grid = (GridPane) findNode(root, "#transactionsGrid");
        assertTrue(grid != null, "deveria existir um GridPane de transacoes renderizado");
        int maxRow = grid.getChildren().stream()
                .mapToInt(n -> Optional.ofNullable(GridPane.getRowIndex(n)).orElse(0))
                .max().orElse(0);
        // maxRow == numero de linhas de dados (linha 0 e o cabecalho).
        assertEquals(expectedDataRows, maxRow, "numero de linhas de dados renderizadas na tabela nao bate");
    }

    // =====================================================================
    // Infra de teste (FX thread / SQLite em memoria / asserts)
    // =====================================================================

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

    private static void waitForAssetCount(AssetService assetService, int expectedCount, long timeoutMs)
            throws InterruptedException {
        long start = System.currentTimeMillis();
        int lastSize;
        do {
            lastSize = assetService.listAssets(false).size();
            if (lastSize >= expectedCount) {
                return;
            }
            Thread.sleep(200);
        } while (System.currentTimeMillis() - start < timeoutMs);
        throw new AssertionError("Timeout esperando ativo ser salvo (esperado >= " + expectedCount
                + ", ultimo observado=" + lastSize + ")");
    }

    /**
     * Equivalente a {@code Node.lookup(String)}, mas descendo manualmente
     * pela árvore de nós em vez de depender do mecanismo de CSS do JavaFX —
     * {@code Node.lookup} só encontra descendentes de um {@code ScrollPane}
     * depois que o {@code Skin} dele é materializado (o que normalmente só
     * acontece com um {@code Stage} visível), e esta tela usa 2
     * {@code ScrollPane} (conteúdo principal e lista de ativos). Descer
     * manualmente por {@code ScrollPane.getContent()} funciona
     * independentemente de qualquer Stage/Scene/Skin.
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

    @SuppressWarnings("unchecked")
    private static <T> ComboBox<T> comboBox(Parent root, String selector, Class<T> type) {
        return (ComboBox<T>) findNode(root, selector);
    }

    private static TextField textField(Parent root, String selector) {
        return (TextField) findNode(root, selector);
    }

    private static DatePicker datePicker(Parent root, String selector) {
        return (DatePicker) findNode(root, selector);
    }

    private static Button button(Parent root, String selector) {
        return (Button) findNode(root, selector);
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
        try (InputStream in = RegistrationViewManualTest.class.getResourceAsStream(resourcePath)) {
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
}
