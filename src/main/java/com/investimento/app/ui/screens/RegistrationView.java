package com.investimento.app.ui.screens;

import com.investimento.app.api.brapi.BrapiClient;
import com.investimento.app.api.brapi.model.SearchResult;
import com.investimento.app.api.coingecko.CoinGeckoClient;
import com.investimento.app.data.model.AssetType;
import com.investimento.app.data.model.Benchmark;
import com.investimento.app.data.model.Category;
import com.investimento.app.data.model.OperationType;
import com.investimento.app.dto.AssetDTO;
import com.investimento.app.dto.CreateAssetRequest;
import com.investimento.app.dto.CreateTransactionRequest;
import com.investimento.app.dto.TransactionDTO;
import com.investimento.app.service.AssetService;
import com.investimento.app.service.TransactionService;
import com.investimento.app.service.ValidationException;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.text.NumberFormat;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Tela Cadastro de Ativos e Transações (ATV-13, RF01/RF02, planejamento 5.2)
 * — formulário de cadastro de ativo com campos condicionais por tipo,
 * autocomplete de ticker B3 com debounce, formulário de transação e tabela de
 * transações do ativo selecionado com editar/excluir. Substitui o stub da
 * ATV-07.
 *
 * <p>Não depende de {@code PositionService}/{@code MarketService} (ATV-10) —
 * a atividade lista como dependência só ATV-07/08/09/04, então o painel de
 * "posição consolidada após o lançamento" do template visual foi substituído
 * pela lista de ativos cadastrados (que a própria atividade já exige: "esta
 * tela deve ter uma lista de ativos existentes").</p>
 */
public class RegistrationView implements ScreenView {

    private static final Locale PT_BR = new Locale("pt", "BR");
    private static final List<AssetType> ASSET_TYPES = List.of(AssetType.values());

    private final AssetService assetService;
    private final TransactionService transactionService;
    private final BrapiClient brapiClient;
    private final CoinGeckoClient coinGeckoClient;

    private final VBox root;
    private final VBox contentBody;
    private final Label subtitleLabel;

    // ---- Formulário de cadastro de ativo -------------------------------
    private final ComboBox<AssetType> typeCombo = new ComboBox<>();
    private final Label categoryValueLabel = buildCategoryValueLabel();
    private final VBox conditionalContainer = new VBox(12);
    private final TextField displayNameField = new TextField();
    private final VBox currencyFieldGroup;
    private final Label typeErrorLabel = buildErrorLabel();
    private final Label nameErrorLabel = buildErrorLabel();
    private final Label conditionalErrorLabel = buildErrorLabel();
    private final Label tickerRenamedInfoLabel = buildInfoLabel();
    private final Button saveAssetButton = new Button("Salvar ativo");

    // Campo condicional: ticker B3 (STOCK/FII/ETF/BDR) + autocomplete
    private final TextField tickerField = new TextField();
    private final VBox autocompleteResultsBox = new VBox();
    private final VBox tickerSection;
    private TickerAutocomplete tickerAutocomplete;

    // Campo condicional: criptomoeda (CRYPTO) — lista fixa, nao texto livre
    private final ComboBox<String> cryptoCombo = new ComboBox<>();
    private final VBox cryptoSection;

    // Campo condicional: moeda de referencia (FOREIGN_CURRENCY)
    private final ComboBox<String> forexCombo = new ComboBox<>();
    private final VBox forexSection;

    // Campos condicionais: renda fixa (FIXED_INCOME)
    private final ComboBox<Benchmark> benchmarkCombo = new ComboBox<>();
    private final TextField contractedRateField = new TextField();
    private final TextField financialInstitutionField = new TextField();
    private final DatePicker investmentDatePicker = new DatePicker();
    private final DatePicker maturityDatePicker = new DatePicker();
    private final VBox fixedIncomeSection;

    // ---- Lista de ativos cadastrados ------------------------------------
    private final VBox assetListBody = new VBox(8);
    private final Label assetListSubtitle = new Label();
    private List<AssetDTO> cachedAssets = List.of();
    private AssetDTO selectedAsset;

    // ---- Formulario de transacao -----------------------------------------
    private final Label linkedAssetBadge = new Label("Nenhum ativo");
    private final Label linkedAssetDisplay = new Label("Selecione um ativo na lista abaixo");
    private final ToggleGroup operationGroup = new ToggleGroup();
    private final ToggleButton buyToggle = new ToggleButton("Compra");
    private final ToggleButton sellToggle = new ToggleButton("Venda");
    private final DatePicker transactionDatePicker = new DatePicker(LocalDate.now());
    private final TextField quantityField = new TextField();
    private final TextField unitPriceField = new TextField();
    private final TextField feesField = new TextField("0");
    private final Label totalValueLabel = new Label("R$ 0,00");
    private final Label transactionFormErrorLabel = buildErrorLabel();
    private final Button saveTransactionButton = new Button("Salvar transação");
    private final Label cancelEditLink = new Label("Cancelar edição");
    private Long editingTransactionId;

    // ---- Tabela de transacoes do ativo selecionado ------------------------
    private final Label transactionsTitleLabel = new Label("Transações");
    private final Label transactionsSubtitleLabel = new Label();
    private final VBox transactionsTableBody = new VBox();

    public RegistrationView(AssetService assetService,
                             TransactionService transactionService,
                             BrapiClient brapiClient,
                             CoinGeckoClient coinGeckoClient) {
        this.assetService = assetService;
        this.transactionService = transactionService;
        this.brapiClient = brapiClient;
        this.coinGeckoClient = coinGeckoClient;

        subtitleLabel = new Label("0 ativos cadastrados · 0 lançamentos");
        subtitleLabel.getStyleClass().add("header-subtitle");

        tickerSection = buildTickerSection();
        cryptoSection = buildCryptoSection();
        forexSection = buildForexSection();
        fixedIncomeSection = buildFixedIncomeSection();
        currencyFieldGroup = fieldGroup("MOEDA", buildCurrencyDisplay());

        configureTypeCombo();

        HBox header = buildHeader();

        contentBody = new VBox(20);
        contentBody.setPadding(new Insets(24, 28, 28, 28));
        contentBody.getChildren().setAll(buildTopRow(), buildTransactionsTableCard());

        ScrollPane scrollPane = new ScrollPane(contentBody);
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        root = new VBox(header, scrollPane);
        root.setStyle("-fx-background-color: -fx-color-bg-shell;");

        wireTransactionFormListeners();
        updateLinkedAssetLabel();
        refreshTransactionsTable();
    }

    @Override
    public Parent getRoot() {
        return root;
    }

    @Override
    public void onShow() {
        refreshAssets();
    }

    // =====================================================================
    // Header
    // =====================================================================

    private HBox buildHeader() {
        Label title = new Label("Cadastro de ativos e transações");
        title.getStyleClass().add("header-title");
        VBox titleBox = new VBox(4, title, subtitleLabel);
        HBox.setHgrow(titleBox, Priority.ALWAYS);

        HBox header = new HBox(titleBox);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("header-bar");
        return header;
    }

    private GridPane buildTopRow() {
        VBox assetFormCard = buildAssetFormCard();
        VBox rightColumn = new VBox(16, buildTransactionFormCard(), buildAssetListCard());
        return twoColumnRow(assetFormCard, rightColumn, 50, 50);
    }

    // =====================================================================
    // Formulário de cadastro de ativo
    // =====================================================================

    private VBox buildAssetFormCard() {
        Label title = new Label("Cadastrar ativo");
        title.getStyleClass().add("content-card-title");
        Label subtitle = new Label("campos mudam conforme o tipo de investimento");
        subtitle.getStyleClass().add("content-card-subtitle");
        VBox titleBox = new VBox(4, title, subtitle);

        VBox card = new VBox(16, titleBox);
        card.getStyleClass().add("content-card");
        card.setId("assetFormCard");

        GridPane typeCategoryGrid = twoColGrid();
        typeCategoryGrid.add(fieldGroup("TIPO DE INVESTIMENTO", typeCombo), 0, 0);
        typeCategoryGrid.add(fieldGroup("CATEGORIA", categoryValueLabel), 1, 0);

        conditionalContainer.setId("conditionalContainer");

        displayNameField.setId("displayNameField");
        displayNameField.getStyleClass().add("text-field");

        GridPane nameCurrencyGrid = twoColGrid();
        nameCurrencyGrid.add(fieldGroup("NOME DE EXIBIÇÃO", displayNameField), 0, 0);
        nameCurrencyGrid.add(currencyFieldGroup, 1, 0);

        Button cancelButton = new Button("Cancelar");
        cancelButton.getStyleClass().add("pill-secondary");
        cancelButton.setOnAction(e -> clearAssetForm());

        saveAssetButton.getStyleClass().add("button-accent-strong");
        saveAssetButton.setId("saveAssetButton");
        saveAssetButton.setOnAction(e -> handleSaveAsset());

        HBox actions = new HBox(8, cancelButton, saveAssetButton);
        actions.setAlignment(Pos.CENTER_RIGHT);

        card.getChildren().addAll(
                typeCategoryGrid, typeErrorLabel,
                conditionalContainer, conditionalErrorLabel,
                nameCurrencyGrid, nameErrorLabel,
                tickerRenamedInfoLabel,
                actions
        );
        return card;
    }

    private void configureTypeCombo() {
        typeCombo.setId("typeCombo");
        typeCombo.setItems(FXCollections.observableArrayList(ASSET_TYPES));
        typeCombo.getStyleClass().add("text-field");
        typeCombo.setMaxWidth(Double.MAX_VALUE);
        typeCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(AssetType type) {
                return type == null ? "" : assetTypeLabel(type);
            }

            @Override
            public AssetType fromString(String s) {
                return null;
            }
        });
        typeCombo.valueProperty().addListener((obs, old, value) -> onTypeChanged(value));
    }

    /**
     * Troca os campos condicionais visíveis conforme o {@link AssetType}
     * selecionado — só a seção do tipo atual fica no {@code conditionalContainer}
     * (as demais nem entram na árvore de nós), e todos os campos condicionais
     * de qualquer tipo são limpos antes — evita que o usuário salve campos de
     * um tipo anterior junto com o tipo novo (armadilha documentada na ATV-13).
     */
    private void onTypeChanged(AssetType type) {
        clearAssetFormErrors();
        clearConditionalFields();
        conditionalContainer.getChildren().clear();

        if (type == null) {
            categoryValueLabel.setText("--");
            currencyFieldGroup.setVisible(true);
            currencyFieldGroup.setManaged(true);
            return;
        }

        categoryValueLabel.setText(categoryLabel(previewCategory(type)));
        switch (type) {
            case STOCK, FII, ETF, BDR -> conditionalContainer.getChildren().add(tickerSection);
            case CRYPTO -> conditionalContainer.getChildren().add(cryptoSection);
            case FOREIGN_CURRENCY -> conditionalContainer.getChildren().add(forexSection);
            case FIXED_INCOME -> conditionalContainer.getChildren().add(fixedIncomeSection);
        }

        // A moeda so faz sentido como selecao livre para FOREIGN_CURRENCY
        // (onde ela E o proprio campo condicional, o dropdown de moeda de
        // referencia) - nos demais tipos toda fonte de cotacao do app so
        // fornece preco em BRL (brapi.dev/CoinGecko), entao o segmented
        // control fica so como informacao (sempre BRL), nunca escondendo
        // dado relevante, mas tambem nunca oferecendo uma escolha sem
        // sentido para o tipo (armadilha da atividade).
        boolean isForex = type == AssetType.FOREIGN_CURRENCY;
        currencyFieldGroup.setVisible(!isForex);
        currencyFieldGroup.setManaged(!isForex);
    }

    private void clearConditionalFields() {
        tickerField.clear();
        hideAutocompleteResults();
        cryptoCombo.getSelectionModel().clearSelection();
        forexCombo.getSelectionModel().clearSelection();
        benchmarkCombo.getSelectionModel().clearSelection();
        contractedRateField.clear();
        financialInstitutionField.clear();
        investmentDatePicker.setValue(null);
        maturityDatePicker.setValue(null);
    }

    private void clearAssetFormErrors() {
        typeErrorLabel.setText("");
        nameErrorLabel.setText("");
        conditionalErrorLabel.setText("");
    }

    private void clearAssetForm() {
        typeCombo.getSelectionModel().clearSelection();
        displayNameField.clear();
        clearAssetFormErrors();
        tickerRenamedInfoLabel.setText("");
        onTypeChanged(null);
    }

    // ---- Secao ticker B3 (STOCK/FII/ETF/BDR) ----------------------------

    private VBox buildTickerSection() {
        tickerField.setId("tickerField");
        tickerField.setPromptText("Digite o ticker (ex.: PETR4)");
        tickerField.getStyleClass().add("text-field");

        Label eyebrow = new Label("TICKER B3");
        eyebrow.getStyleClass().add("field-label");
        Label hint = new Label("autocomplete brapi.dev");
        hint.setStyle("-fx-font-family: 'Manrope Medium'; -fx-font-size: 11px; -fx-text-fill: -fx-color-accent-strong;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox labelRow = new HBox(eyebrow, spacer, hint);
        labelRow.setAlignment(Pos.CENTER_LEFT);

        autocompleteResultsBox.getStyleClass().add("autocomplete-panel");
        autocompleteResultsBox.setVisible(false);
        autocompleteResultsBox.setManaged(false);

        VBox section = new VBox(7, labelRow, tickerField, autocompleteResultsBox);
        section.setId("tickerSection");

        tickerAutocomplete = new TickerAutocomplete(tickerField, brapiClient,
                results -> showAutocompleteResults(results, tickerField.getText()),
                this::hideAutocompleteResults,
                error -> hideAutocompleteResults());

        return section;
    }

    private void showAutocompleteResults(List<SearchResult> results, String query) {
        autocompleteResultsBox.getChildren().clear();
        if (results == null || results.isEmpty()) {
            hideAutocompleteResults();
            return;
        }
        List<SearchResult> limited = results.size() > 6 ? results.subList(0, 6) : results;
        for (int i = 0; i < limited.size(); i++) {
            autocompleteResultsBox.getChildren().add(buildAutocompleteRow(limited.get(i), i == 0));
        }
        Label footer = new Label(results.size() + (results.size() == 1 ? " resultado" : " resultados")
                + " para \"" + query + "\"");
        footer.getStyleClass().add("autocomplete-footer");
        footer.setMaxWidth(Double.MAX_VALUE);
        autocompleteResultsBox.getChildren().add(footer);
        autocompleteResultsBox.setVisible(true);
        autocompleteResultsBox.setManaged(true);
    }

    private void hideAutocompleteResults() {
        autocompleteResultsBox.setVisible(false);
        autocompleteResultsBox.setManaged(false);
    }

    private HBox buildAutocompleteRow(SearchResult result, boolean highlight) {
        Label symbol = new Label(result.symbol() != null ? result.symbol() : "?");
        symbol.getStyleClass().add("autocomplete-item-primary");
        Label desc = new Label(result.name() != null ? result.name() : "");
        desc.getStyleClass().add("autocomplete-item-secondary");
        VBox left = new VBox(3, symbol, desc);
        HBox.setHgrow(left, Priority.ALWAYS);

        Label price = new Label(result.currentPrice() != null ? formatCurrency(result.currentPrice()) : "--");
        price.getStyleClass().add("autocomplete-item-price");

        HBox row = new HBox(10, left, price);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("autocomplete-item");
        if (highlight) {
            row.getStyleClass().add("autocomplete-item-highlight");
        }
        row.setOnMouseClicked(e -> selectAutocompleteResult(result));
        return row;
    }

    private void selectAutocompleteResult(SearchResult result) {
        if (result.symbol() != null) {
            tickerField.setText(result.symbol());
        }
        if ((displayNameField.getText() == null || displayNameField.getText().isBlank()) && result.name() != null) {
            displayNameField.setText(result.name());
        }
        hideAutocompleteResults();
    }

    // ---- Secao criptomoeda (CRYPTO) --------------------------------------

    private VBox buildCryptoSection() {
        cryptoCombo.setId("cryptoCombo");
        cryptoCombo.getStyleClass().add("text-field");
        cryptoCombo.setMaxWidth(Double.MAX_VALUE);
        List<String> symbols = coinGeckoClient.supportedSymbols().stream().sorted().toList();
        cryptoCombo.setItems(FXCollections.observableArrayList(symbols));

        VBox section = fieldGroup("CRIPTOMOEDA (LISTA FIXA)", cryptoCombo);
        section.setId("cryptoSection");
        return section;
    }

    // ---- Secao moeda estrangeira (FOREIGN_CURRENCY) ----------------------

    private VBox buildForexSection() {
        forexCombo.setId("forexCombo");
        forexCombo.getStyleClass().add("text-field");
        forexCombo.setMaxWidth(Double.MAX_VALUE);
        List<String> currencies = assetService.supportedForexCurrencies().stream().sorted().toList();
        forexCombo.setItems(FXCollections.observableArrayList(currencies));

        VBox section = fieldGroup("MOEDA DE REFERÊNCIA", forexCombo);
        section.setId("forexSection");
        return section;
    }

    // ---- Secao renda fixa (FIXED_INCOME) ---------------------------------

    private VBox buildFixedIncomeSection() {
        benchmarkCombo.setId("benchmarkCombo");
        benchmarkCombo.getStyleClass().add("text-field");
        benchmarkCombo.setMaxWidth(Double.MAX_VALUE);
        benchmarkCombo.setItems(FXCollections.observableArrayList(Benchmark.values()));
        benchmarkCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(Benchmark benchmark) {
                return benchmark == null ? "" : benchmarkLabel(benchmark);
            }

            @Override
            public Benchmark fromString(String s) {
                return null;
            }
        });

        contractedRateField.setId("contractedRateField");
        contractedRateField.getStyleClass().add("text-field");
        contractedRateField.setPromptText("Ex.: 12,5");

        financialInstitutionField.setId("financialInstitutionField");
        financialInstitutionField.getStyleClass().add("text-field");
        financialInstitutionField.setPromptText("Ex.: Banco Rubro");

        investmentDatePicker.setId("investmentDatePicker");
        investmentDatePicker.getStyleClass().add("text-field");
        investmentDatePicker.setMaxWidth(Double.MAX_VALUE);

        maturityDatePicker.setId("maturityDatePicker");
        maturityDatePicker.getStyleClass().add("text-field");
        maturityDatePicker.setMaxWidth(Double.MAX_VALUE);

        GridPane dateGrid = twoColGrid();
        dateGrid.add(fieldGroup("DATA DE APLICAÇÃO", investmentDatePicker), 0, 0);
        dateGrid.add(fieldGroup("DATA DE VENCIMENTO", maturityDatePicker), 1, 0);

        VBox section = new VBox(12,
                fieldGroup("INDEXADOR", benchmarkCombo),
                fieldGroup("TAXA CONTRATADA (% a.a.)", contractedRateField),
                fieldGroup("INSTITUIÇÃO FINANCEIRA", financialInstitutionField),
                dateGrid);
        section.setId("fixedIncomeSection");
        return section;
    }

    // ---- Moeda (segmented control, sempre BRL) ---------------------------

    private HBox buildCurrencyDisplay() {
        Label brl = segmentedLabel("BRL", true);
        Label usd = segmentedLabel("USD", false);
        Label eur = segmentedLabel("EUR", false);
        HBox box = new HBox(6, brl, usd, eur);
        for (Label option : List.of(brl, usd, eur)) {
            HBox.setHgrow(option, Priority.ALWAYS);
            option.setMaxWidth(Double.MAX_VALUE);
            option.setAlignment(Pos.CENTER);
        }
        return box;
    }

    private Label segmentedLabel(String text, boolean selected) {
        Label label = new Label(text);
        label.getStyleClass().add(selected ? "segmented-option-selected" : "segmented-option");
        label.setAlignment(Pos.CENTER);
        return label;
    }

    // ---- Salvar ativo -----------------------------------------------------

    private void handleSaveAsset() {
        clearAssetFormErrors();
        tickerRenamedInfoLabel.setText("");

        AssetType type = typeCombo.getValue();
        if (type == null) {
            typeErrorLabel.setText("Selecione o tipo de investimento.");
            return;
        }

        CreateAssetRequest req = buildCreateAssetRequest(type);
        String typedTicker = req.ticker();

        saveAssetButton.setDisable(true);
        Task<AssetDTO> task = new Task<>() {
            @Override
            protected AssetDTO call() throws Exception {
                return assetService.create(req);
            }
        };
        task.setOnSucceeded(e -> {
            saveAssetButton.setDisable(false);
            AssetDTO dto = task.getValue();
            if (isTickerRenamed(typedTicker, dto.ticker())) {
                tickerRenamedInfoLabel.setText(
                        "Ticker atualizado de " + typedTicker.trim().toUpperCase(PT_BR) + " para " + dto.ticker() + ".");
            }
            clearAssetForm();
            refreshAssets();
        });
        task.setOnFailed(e -> {
            saveAssetButton.setDisable(false);
            Throwable ex = task.getException();
            if (ex instanceof ValidationException ve) {
                routeValidationError(ve.getMessage());
            } else {
                conditionalErrorLabel.setText("Erro ao salvar ativo: "
                        + (ex != null ? ex.getMessage() : "erro desconhecido"));
            }
        });

        Thread thread = new Thread(task, "save-asset");
        thread.setDaemon(true);
        thread.start();
    }

    private CreateAssetRequest buildCreateAssetRequest(AssetType type) {
        String ticker = null;
        String currency = "BRL";
        Benchmark benchmark = null;
        Double contractedRatePct = null;
        String financialInstitution = null;
        LocalDate investmentDate = null;
        LocalDate maturityDate = null;

        switch (type) {
            case STOCK, FII, ETF, BDR -> ticker = tickerField.getText();
            case CRYPTO -> ticker = cryptoCombo.getValue();
            case FOREIGN_CURRENCY -> currency = forexCombo.getValue();
            case FIXED_INCOME -> {
                benchmark = benchmarkCombo.getValue();
                contractedRatePct = parseDoubleOrNull(contractedRateField.getText());
                financialInstitution = financialInstitutionField.getText();
                investmentDate = investmentDatePicker.getValue();
                maturityDate = maturityDatePicker.getValue();
            }
        }

        return new CreateAssetRequest(type, ticker, displayNameField.getText(), currency,
                benchmark, contractedRatePct, financialInstitution, investmentDate, maturityDate);
    }

    /** Rotea a mensagem de {@link ValidationException} para o rotulo mais proximo do campo relevante, nao um alerta generico. */
    private void routeValidationError(String message) {
        clearAssetFormErrors();
        String lower = message.toLowerCase(PT_BR);
        if (lower.contains("tipo de ativo")) {
            typeErrorLabel.setText(message);
        } else if (lower.contains("exibição") || lower.contains("exibicao")) {
            nameErrorLabel.setText(message);
        } else {
            conditionalErrorLabel.setText(message);
        }
    }

    /**
     * Ticker foi resolvido para um valor diferente do digitado pelo usuario
     * (brapi.dev {@code changed=true}, ex.: BIDI11 -> INBR32) - comparacao
     * case-insensitive, com trim. {@code null}/vazio nunca conta como
     * renomeacao (cobre CRYPTO/FOREIGN_CURRENCY/FIXED_INCOME, onde o ticker
     * pode ser null nos dois lados). Extraido como metodo estatico
     * package-private para ser testavel sem depender de rede/UI renderizada.
     */
    static boolean isTickerRenamed(String typed, String resolved) {
        if (typed == null || resolved == null) {
            return false;
        }
        String typedTrim = typed.trim();
        if (typedTrim.isEmpty()) {
            return false;
        }
        return !typedTrim.equalsIgnoreCase(resolved.trim());
    }

    // =====================================================================
    // Lista de ativos cadastrados
    // =====================================================================

    private VBox buildAssetListCard() {
        Label title = new Label("Ativos cadastrados");
        title.getStyleClass().add("content-card-title");
        assetListSubtitle.getStyleClass().add("content-card-subtitle");
        VBox titleBox = new VBox(4, title, assetListSubtitle);

        VBox card = new VBox(14, titleBox);
        card.getStyleClass().add("content-card");

        assetListBody.setId("assetListBody");
        ScrollPane scrollPane = new ScrollPane(assetListBody);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefHeight(230);
        scrollPane.setMaxHeight(230);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent;");

        card.getChildren().add(scrollPane);
        return card;
    }

    private void refreshAssets() {
        cachedAssets = assetService.listAssets(false);
        int totalTransactions = transactionService.listAll().size();
        subtitleLabel.setText(cachedAssets.size() + " ativos cadastrados · " + totalTransactions + " lançamentos");
        assetListSubtitle.setText(cachedAssets.size() + " no total");

        if (selectedAsset != null) {
            selectedAsset = cachedAssets.stream()
                    .filter(a -> a.id().equals(selectedAsset.id()))
                    .findFirst()
                    .orElse(null);
        }

        renderAssetList();
        updateLinkedAssetLabel();
        refreshTransactionsTable();
    }

    private void renderAssetList() {
        assetListBody.getChildren().clear();
        if (cachedAssets.isEmpty()) {
            Label empty = new Label("Nenhum ativo cadastrado ainda — use o formulário ao lado.");
            empty.getStyleClass().add("content-card-subtitle");
            empty.setWrapText(true);
            assetListBody.getChildren().add(empty);
            return;
        }
        for (AssetDTO asset : cachedAssets) {
            assetListBody.getChildren().add(buildAssetRow(asset));
        }
    }

    private HBox buildAssetRow(AssetDTO asset) {
        Label icon = new Label(categoryAbbreviation(asset.category()));
        icon.getStyleClass().add("asset-row-icon");
        // Classe de CSS em vez de -fx-text-fill:white inline: o fundo do
        // icone e -fx-color-fill-inverse, que inverte no tema escuro.
        icon.getStyleClass().add("asset-row-icon-text");
        StackPane iconStack = new StackPane(icon);
        iconStack.getStyleClass().add("asset-row-icon");

        Label name = new Label(assetLabel(asset));
        name.setStyle("-fx-font-family: 'Manrope'; -fx-font-weight: bold; -fx-font-size: 13px; -fx-text-fill: -fx-color-text-primary;");
        Label subtitle = new Label(categoryLabel(asset.category()) + " · " + asset.currency());
        subtitle.setStyle("-fx-font-family: 'Manrope Medium'; -fx-font-size: 11px; -fx-text-fill: -fx-color-text-muted;");
        VBox nameBox = new VBox(3, name, subtitle);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox row = new HBox(11, iconStack, nameBox, spacer);
        row.setAlignment(Pos.CENTER_LEFT);
        boolean isSelected = selectedAsset != null && selectedAsset.id().equals(asset.id());
        row.getStyleClass().add(isSelected ? "asset-row-selected" : "asset-row");
        row.setOnMouseClicked(e -> selectAsset(asset));
        return row;
    }

    private void selectAsset(AssetDTO asset) {
        this.selectedAsset = asset;
        clearTransactionForm();
        renderAssetList();
        updateLinkedAssetLabel();
        refreshTransactionsTable();
    }

    /**
     * Seam de teste (mesmo pacote, ver {@code RegistrationViewManualTest}) —
     * simula clicar numa linha da lista de ativos sem depender de localizar o
     * {@code Node} exato da linha renderizada (a ordem de exibição segue
     * {@code display_name}, não a ordem de criação, e não é estável o
     * suficiente para um teste indexar por posição).
     */
    void selectAssetForTest(AssetDTO asset) {
        selectAsset(asset);
    }

    // =====================================================================
    // Formulário de transação
    // =====================================================================

    private VBox buildTransactionFormCard() {
        Label title = new Label("Lançar transação");
        title.setStyle("-fx-font-family: 'Manrope'; -fx-font-weight: bold; -fx-font-size: 15px; -fx-text-fill: white;");
        Label subtitle = new Label("vinculada a um ativo já cadastrado");
        subtitle.getStyleClass().add("card-dark-label");
        VBox titleBox = new VBox(4, title, subtitle);

        linkedAssetBadge.setStyle("-fx-background-color: #22282b; -fx-background-radius: 20; -fx-padding: 5 10;"
                + " -fx-font-family: 'IBM Plex Mono SemiBold'; -fx-font-size: 11px; -fx-text-fill: -fx-color-gain-on-dark;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox headerRow = new HBox(titleBox, spacer, linkedAssetBadge);
        headerRow.setAlignment(Pos.CENTER_LEFT);

        linkedAssetDisplay.setId("linkedAssetLabel");
        linkedAssetDisplay.getStyleClass().add("text-field-dark");
        linkedAssetDisplay.setMaxWidth(Double.MAX_VALUE);
        linkedAssetDisplay.setAlignment(Pos.CENTER_LEFT);
        StackPane linkedAssetBox = new StackPane(linkedAssetDisplay);
        StackPane.setAlignment(linkedAssetDisplay, Pos.CENTER_LEFT);

        HBox operationSegment = buildOperationSegment();

        GridPane fieldsGrid = twoColGrid();
        transactionDatePicker.setId("transactionDatePicker");
        transactionDatePicker.getStyleClass().add("text-field-dark");
        transactionDatePicker.setMaxWidth(Double.MAX_VALUE);
        quantityField.setId("quantityField");
        quantityField.getStyleClass().add("text-field-dark");
        quantityField.setPromptText("0");
        unitPriceField.setId("unitPriceField");
        unitPriceField.getStyleClass().add("text-field-dark");
        unitPriceField.setPromptText("0,00");
        feesField.setId("feesField");
        feesField.getStyleClass().add("text-field-dark");

        fieldsGrid.add(fieldGroupDark("DATA", transactionDatePicker), 0, 0);
        fieldsGrid.add(fieldGroupDark("QUANTIDADE", quantityField), 1, 0);
        fieldsGrid.add(fieldGroupDark("PREÇO UNITÁRIO", unitPriceField), 0, 1);
        fieldsGrid.add(fieldGroupDark("TAXAS / CORRETAGEM", feesField), 1, 1);
        fieldsGrid.setVgap(12);

        Region divider = new Region();
        divider.getStyleClass().add("card-dark-divider");
        divider.setMaxWidth(Double.MAX_VALUE);

        Label totalCaption = new Label("Total da operação");
        totalCaption.getStyleClass().add("card-dark-label");
        totalValueLabel.setStyle("-fx-font-family: 'Manrope ExtraBold'; -fx-font-size: 24px; -fx-text-fill: white;");
        VBox totalBox = new VBox(5, totalCaption, totalValueLabel);

        cancelEditLink.setId("cancelEditLink");
        cancelEditLink.setStyle("-fx-font-family: 'Manrope SemiBold'; -fx-font-size: 12px; -fx-text-fill: -fx-color-sidebar-text; -fx-cursor: hand; -fx-underline: true;");
        cancelEditLink.setVisible(false);
        cancelEditLink.setManaged(false);
        cancelEditLink.setOnMouseClicked(e -> clearTransactionForm());

        Button clearButton = new Button("Limpar");
        clearButton.setStyle("-fx-background-color: transparent; -fx-border-color: -fx-color-field-dark-border;"
                + " -fx-border-radius: 10; -fx-padding: 10 15; -fx-font-family: 'Manrope SemiBold'; -fx-font-size: 13px;"
                + " -fx-text-fill: -fx-color-sidebar-text; -fx-cursor: hand;");
        clearButton.setOnAction(e -> clearTransactionForm());

        saveTransactionButton.setId("saveTransactionButton");
        saveTransactionButton.setStyle("-fx-background-color: -fx-color-accent; -fx-background-radius: 10;"
                + " -fx-padding: 11 18; -fx-font-family: 'Manrope'; -fx-font-weight: bold; -fx-font-size: 13px;"
                + " -fx-text-fill: -fx-color-on-accent; -fx-cursor: hand;");
        saveTransactionButton.setOnAction(e -> handleSaveTransaction());
        saveTransactionButton.setDisable(true);

        HBox totalActionsRow = new HBox();
        totalActionsRow.setAlignment(Pos.CENTER_LEFT);
        Region spacer2 = new Region();
        HBox.setHgrow(spacer2, Priority.ALWAYS);
        HBox buttonsBox = new HBox(8, clearButton, saveTransactionButton);
        totalActionsRow.getChildren().addAll(totalBox, spacer2, buttonsBox);

        VBox card = new VBox(18, headerRow,
                fieldGroupDark("ATIVO VINCULADO", linkedAssetBox), cancelEditLink,
                fieldGroupDark("TIPO DE OPERAÇÃO", operationSegment),
                fieldsGrid, divider, totalActionsRow, transactionFormErrorLabel);
        card.getStyleClass().add("card-dark");
        return card;
    }

    private HBox buildOperationSegment() {
        buyToggle.setId("buyToggle");
        sellToggle.setId("sellToggle");
        buyToggle.setToggleGroup(operationGroup);
        sellToggle.setToggleGroup(operationGroup);
        buyToggle.setSelected(true);
        for (ToggleButton toggle : List.of(buyToggle, sellToggle)) {
            toggle.getStyleClass().add("segmented-option-dark");
            toggle.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(toggle, Priority.ALWAYS);
        }
        applyOperationToggleStyle();
        operationGroup.selectedToggleProperty().addListener((obs, old, val) -> applyOperationToggleStyle());

        HBox box = new HBox(8, buyToggle, sellToggle);
        return box;
    }

    private void applyOperationToggleStyle() {
        buyToggle.getStyleClass().removeAll("segmented-option-dark", "segmented-option-selected-buy");
        sellToggle.getStyleClass().removeAll("segmented-option-dark", "segmented-option-selected-sell");
        buyToggle.getStyleClass().add(buyToggle.isSelected() ? "segmented-option-selected-buy" : "segmented-option-dark");
        sellToggle.getStyleClass().add(sellToggle.isSelected() ? "segmented-option-selected-sell" : "segmented-option-dark");
    }

    private void wireTransactionFormListeners() {
        quantityField.textProperty().addListener((obs, old, val) -> updateTotalLabel());
        unitPriceField.textProperty().addListener((obs, old, val) -> updateTotalLabel());
        feesField.textProperty().addListener((obs, old, val) -> updateTotalLabel());
    }

    private void updateTotalLabel() {
        Double quantity = parseDoubleOrNull(quantityField.getText());
        Double unitPrice = parseDoubleOrNull(unitPriceField.getText());
        Double fees = parseDoubleOrNull(feesField.getText());
        double total = (quantity == null ? 0 : quantity) * (unitPrice == null ? 0 : unitPrice)
                + (fees == null ? 0 : fees);
        totalValueLabel.setText(formatCurrency(total));
    }

    private void updateLinkedAssetLabel() {
        if (selectedAsset == null) {
            linkedAssetDisplay.setText("Selecione um ativo na lista abaixo");
            linkedAssetBadge.setText("Nenhum ativo");
            saveTransactionButton.setDisable(true);
        } else {
            String name = selectedAsset.displayName() != null && !selectedAsset.displayName().isBlank()
                    ? " — " + selectedAsset.displayName() : "";
            linkedAssetDisplay.setText(assetLabel(selectedAsset) + name);
            linkedAssetBadge.setText(assetLabel(selectedAsset));
            saveTransactionButton.setDisable(false);
        }
    }

    private void handleSaveTransaction() {
        transactionFormErrorLabel.setText("");
        if (selectedAsset == null) {
            transactionFormErrorLabel.setText("Selecione um ativo na lista para lançar uma transação.");
            return;
        }

        OperationType operationType = buyToggle.isSelected() ? OperationType.BUY : OperationType.SELL;
        LocalDate date = transactionDatePicker.getValue();
        Double quantity = parseDoubleOrNull(quantityField.getText());
        Double unitPrice = parseDoubleOrNull(unitPriceField.getText());
        Double fees = parseDoubleOrNull(feesField.getText());

        if (quantity == null || unitPrice == null) {
            transactionFormErrorLabel.setText("Quantidade e preço unitário são obrigatórios e devem ser números válidos.");
            return;
        }
        double feesValue = fees == null ? 0 : fees;

        try {
            if (editingTransactionId == null) {
                CreateTransactionRequest req = new CreateTransactionRequest(
                        selectedAsset.id(), operationType, date, quantity, unitPrice, feesValue, null);
                transactionService.create(req);
            } else {
                TransactionDTO updated = new TransactionDTO(
                        editingTransactionId, selectedAsset.id(), operationType, date, quantity, unitPrice, feesValue, null);
                transactionService.update(updated);
            }
            clearTransactionForm();
            refreshTransactionsTable();
            refreshAssets();
        } catch (ValidationException e) {
            transactionFormErrorLabel.setText(e.getMessage());
        }
    }

    private void clearTransactionForm() {
        editingTransactionId = null;
        buyToggle.setSelected(true);
        transactionDatePicker.setValue(LocalDate.now());
        quantityField.clear();
        unitPriceField.clear();
        feesField.setText("0");
        transactionFormErrorLabel.setText("");
        saveTransactionButton.setText("Salvar transação");
        cancelEditLink.setVisible(false);
        cancelEditLink.setManaged(false);
        updateTotalLabel();
    }

    // =====================================================================
    // Tabela de transações do ativo selecionado
    // =====================================================================

    private VBox buildTransactionsTableCard() {
        transactionsTitleLabel.getStyleClass().add("content-card-title");
        transactionsSubtitleLabel.getStyleClass().add("content-card-subtitle");
        VBox titleBox = new VBox(4, transactionsTitleLabel, transactionsSubtitleLabel);

        VBox card = new VBox(14, titleBox);
        card.getStyleClass().add("content-card");

        transactionsTableBody.setId("transactionsTableBody");
        card.getChildren().add(transactionsTableBody);
        return card;
    }

    private void refreshTransactionsTable() {
        transactionsTableBody.getChildren().clear();

        if (selectedAsset == null) {
            transactionsTitleLabel.setText("Transações");
            transactionsSubtitleLabel.setText("selecione um ativo na lista para ver seus lançamentos");
            Label empty = new Label("Nenhum ativo selecionado.");
            empty.getStyleClass().add("content-card-subtitle");
            transactionsTableBody.getChildren().add(empty);
            return;
        }

        transactionsTitleLabel.setText("Transações de " + assetLabel(selectedAsset));
        List<TransactionDTO> transactions = transactionService.listByAsset(selectedAsset.id()).stream()
                .sorted(Comparator.comparing(TransactionDTO::date).reversed())
                .toList();
        transactionsSubtitleLabel.setText(transactions.size() + " lançamentos · editável");

        if (transactions.isEmpty()) {
            Label empty = new Label("Nenhuma transação registrada para este ativo ainda.");
            empty.getStyleClass().add("content-card-subtitle");
            transactionsTableBody.getChildren().add(empty);
            return;
        }

        GridPane grid = new GridPane();
        grid.setId("transactionsGrid");
        grid.setHgap(14);
        ColumnConstraints cDate = pctColumn(16, HPos.LEFT);
        ColumnConstraints cOp = pctColumn(14, HPos.LEFT);
        ColumnConstraints cQty = pctColumn(12, HPos.RIGHT);
        ColumnConstraints cPrice = pctColumn(16, HPos.RIGHT);
        ColumnConstraints cFees = pctColumn(12, HPos.RIGHT);
        ColumnConstraints cTotal = pctColumn(16, HPos.RIGHT);
        ColumnConstraints cActions = pctColumn(14, HPos.RIGHT);
        grid.getColumnConstraints().addAll(cDate, cOp, cQty, cPrice, cFees, cTotal, cActions);

        grid.add(tableHeaderCell("DATA", HPos.LEFT), 0, 0);
        grid.add(tableHeaderCell("OPERAÇÃO", HPos.LEFT), 1, 0);
        grid.add(tableHeaderCell("QTD.", HPos.RIGHT), 2, 0);
        grid.add(tableHeaderCell("PREÇO UNIT.", HPos.RIGHT), 3, 0);
        grid.add(tableHeaderCell("TAXAS", HPos.RIGHT), 4, 0);
        grid.add(tableHeaderCell("TOTAL", HPos.RIGHT), 5, 0);
        grid.add(tableHeaderCell("AÇÕES", HPos.RIGHT), 6, 0);

        for (int i = 0; i < transactions.size(); i++) {
            TransactionDTO tx = transactions.get(i);
            int row = i + 1;
            boolean last = i == transactions.size() - 1;

            double total = tx.quantity() * tx.unitPrice() + tx.fees();

            grid.add(tableCell(formatDate(tx.date()), "table-row-secondary", HPos.LEFT, last), 0, row);
            grid.add(operationBadge(tx.operationType(), last), 1, row);
            grid.add(tableCell(formatDecimal(tx.quantity(), 0), "table-cell-numeric", HPos.RIGHT, last), 2, row);
            grid.add(tableCell(formatDecimal(tx.unitPrice(), 2), "table-cell-numeric", HPos.RIGHT, last), 3, row);
            grid.add(tableCell(formatDecimal(tx.fees(), 2), "table-row-secondary", HPos.RIGHT, last), 4, row);
            grid.add(tableCell(formatDecimal(total, 2), "table-cell-numeric", HPos.RIGHT, last), 5, row);
            grid.add(actionsCell(tx, last), 6, row);
        }

        transactionsTableBody.getChildren().add(grid);
    }

    private HBox operationBadge(OperationType type, boolean last) {
        Label badge = new Label(type == OperationType.BUY ? "Compra" : "Venda");
        badge.getStyleClass().add(type == OperationType.BUY ? "badge-buy" : "badge-sell");
        HBox box = new HBox(badge);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setPadding(new Insets(13, 0, 13, 0));
        if (!last) {
            box.getStyleClass().add("table-row-divider");
        }
        return box;
    }

    private Label tableCell(String text, String styleClass, HPos align, boolean last) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        label.setMaxWidth(Double.MAX_VALUE);
        GridPane.setHalignment(label, align);
        label.setPadding(new Insets(13, 0, 13, 0));
        if (!last) {
            label.getStyleClass().add("table-row-divider");
        }
        return label;
    }

    private Label tableHeaderCell(String text, HPos align) {
        Label label = new Label(text);
        label.getStyleClass().add("table-header-cell");
        GridPane.setHalignment(label, align);
        return label;
    }

    private HBox actionsCell(TransactionDTO tx, boolean last) {
        Label edit = new Label("Editar");
        edit.setStyle("-fx-font-family: 'Manrope'; -fx-font-weight: bold; -fx-font-size: 12px; -fx-text-fill: -fx-color-accent-strong; -fx-cursor: hand;");
        edit.setOnMouseClicked(e -> editTransaction(tx));

        Label delete = new Label("Excluir");
        delete.setStyle("-fx-font-family: 'Manrope'; -fx-font-weight: bold; -fx-font-size: 12px; -fx-text-fill: -fx-color-loss; -fx-cursor: hand;");
        delete.setOnMouseClicked(e -> deleteTransaction(tx));

        HBox box = new HBox(10, edit, delete);
        box.setAlignment(Pos.CENTER_RIGHT);
        box.setPadding(new Insets(13, 0, 13, 0));
        if (!last) {
            box.getStyleClass().add("table-row-divider");
        }
        return box;
    }

    private void editTransaction(TransactionDTO tx) {
        editingTransactionId = tx.id();
        if (tx.operationType() == OperationType.BUY) {
            buyToggle.setSelected(true);
        } else {
            sellToggle.setSelected(true);
        }
        transactionDatePicker.setValue(tx.date());
        quantityField.setText(formatPlain(tx.quantity()));
        unitPriceField.setText(formatPlain(tx.unitPrice()));
        feesField.setText(formatPlain(tx.fees()));
        saveTransactionButton.setText("Atualizar transação");
        cancelEditLink.setVisible(true);
        cancelEditLink.setManaged(true);
    }

    private void deleteTransaction(TransactionDTO tx) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                "Excluir esta transação? Esta ação não pode ser desfeita.", ButtonType.YES, ButtonType.NO);
        alert.setHeaderText("Excluir transação");
        Optional<ButtonType> result = alert.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.YES) {
            confirmDeleteTransaction(tx);
        }
    }

    private void confirmDeleteTransaction(TransactionDTO tx) {
        transactionService.delete(tx.id());
        if (editingTransactionId != null && editingTransactionId.equals(tx.id())) {
            clearTransactionForm();
        }
        refreshTransactionsTable();
    }

    /**
     * Seam de teste (mesmo pacote) para "Editar" — evita ter que localizar o
     * {@code Label} exato dentro do {@code GridPane} renderizado.
     */
    void editTransactionForTest(TransactionDTO tx) {
        editTransaction(tx);
    }

    /**
     * Seam de teste (mesmo pacote) para "Excluir" — pula deliberadamente o
     * {@link Alert} de confirmação (automatizar o clique num dialog modal é
     * exatamente o tipo de interação visual que esta atividade foi instruída
     * a pular); exercita só a lógica que de fato importa verificar por
     * código: excluir e refletir imediatamente na tabela.
     */
    void confirmDeleteTransactionForTest(TransactionDTO tx) {
        confirmDeleteTransaction(tx);
    }

    // =====================================================================
    // Helpers de layout
    // =====================================================================

    private VBox fieldGroup(String labelText, Node... control) {
        return fieldGroupInternal(labelText, "field-label", control);
    }

    private VBox fieldGroupDark(String labelText, Node control) {
        return fieldGroupInternal(labelText, "field-label-dark", control);
    }

    private VBox fieldGroupInternal(String labelText, String labelStyleClass, Node... controls) {
        Label label = new Label(labelText);
        label.getStyleClass().add(labelStyleClass);
        VBox group = new VBox(7);
        group.getChildren().add(label);
        for (Node control : controls) {
            if (control instanceof Region region) {
                region.setMaxWidth(Double.MAX_VALUE);
            }
            group.getChildren().add(control);
        }
        return group;
    }

    private GridPane twoColGrid() {
        GridPane grid = new GridPane();
        grid.setHgap(14);
        ColumnConstraints c1 = new ColumnConstraints();
        c1.setPercentWidth(50);
        c1.setHgrow(Priority.ALWAYS);
        ColumnConstraints c2 = new ColumnConstraints();
        c2.setPercentWidth(50);
        c2.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(c1, c2);
        return grid;
    }

    private ColumnConstraints pctColumn(double percent, HPos align) {
        ColumnConstraints c = new ColumnConstraints();
        c.setPercentWidth(percent);
        c.setHalignment(align);
        return c;
    }

    private GridPane twoColumnRow(Region left, Region right, double leftPercent, double rightPercent) {
        GridPane grid = new GridPane();
        grid.setHgap(16);
        ColumnConstraints c1 = new ColumnConstraints();
        c1.setPercentWidth(leftPercent);
        c1.setHgrow(Priority.ALWAYS);
        ColumnConstraints c2 = new ColumnConstraints();
        c2.setPercentWidth(rightPercent);
        c2.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(c1, c2);
        left.setMaxWidth(Double.MAX_VALUE);
        right.setMaxWidth(Double.MAX_VALUE);
        grid.add(left, 0, 0);
        grid.add(right, 1, 0);
        return grid;
    }

    private static Label buildErrorLabel() {
        Label label = new Label();
        label.getStyleClass().add("form-error-label");
        label.setWrapText(true);
        label.setManaged(true);
        return label;
    }

    private static Label buildInfoLabel() {
        Label label = new Label();
        label.getStyleClass().add("form-info-label");
        label.setWrapText(true);
        return label;
    }

    /** Caixa não editável, mesmo visual de {@code .text-field} (template mostra "Categoria" como um campo, não texto solto). */
    private static Label buildCategoryValueLabel() {
        Label label = new Label("--");
        label.getStyleClass().add("text-field");
        label.setAlignment(Pos.CENTER_LEFT);
        return label;
    }

    // =====================================================================
    // Rótulos / cores / categoria
    // =====================================================================

    private static String assetTypeLabel(AssetType type) {
        return switch (type) {
            case STOCK -> "Ação";
            case FII -> "FII";
            case ETF -> "ETF";
            case BDR -> "BDR";
            case FIXED_INCOME -> "Renda Fixa";
            case CRYPTO -> "Criptomoeda";
            case FOREIGN_CURRENCY -> "Moeda Estrangeira";
        };
    }

    private static String benchmarkLabel(Benchmark benchmark) {
        return switch (benchmark) {
            case CDI -> "CDI";
            case SELIC -> "SELIC";
            case IPCA -> "IPCA";
            case FIXED_RATE -> "Prefixado (taxa fixa)";
        };
    }

    /**
     * Preview local da categoria antes de salvar (o valor persistido de
     * verdade sempre vem de {@code AssetDTO.category()}, calculado por
     * {@code AssetServiceImpl.deriveCategory} — esta é só uma cópia da mesma
     * regra fixa, só para o campo "Categoria" do formulário mostrar algo
     * antes do POST, mesma tabela fixa tipo→categoria da ATV-08).
     */
    private static Category previewCategory(AssetType type) {
        return switch (type) {
            case STOCK, ETF, BDR -> Category.STOCKS;
            case FII -> Category.FIIS;
            case FIXED_INCOME -> Category.FIXED_INCOME;
            case CRYPTO -> Category.CRYPTO;
            case FOREIGN_CURRENCY -> Category.FOREX;
        };
    }

    private static String categoryLabel(Category category) {
        return switch (category) {
            case STOCKS -> "Renda variável";
            case FIIS -> "Fundos imobiliários";
            case FIXED_INCOME -> "Renda fixa";
            case CRYPTO -> "Criptomoedas";
            case FOREX -> "Câmbio";
        };
    }

    private static String categoryAbbreviation(Category category) {
        return switch (category) {
            case STOCKS -> "AÇ";
            case FIIS -> "FI";
            case FIXED_INCOME -> "RF";
            case CRYPTO -> "CR";
            case FOREX -> "CB";
        };
    }

    private static String assetLabel(AssetDTO asset) {
        if (asset.ticker() != null && !asset.ticker().isBlank()) {
            return asset.ticker();
        }
        return asset.displayName();
    }

    // =====================================================================
    // Parsing / formatação (pt-BR)
    // =====================================================================

    private static Double parseDoubleOrNull(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(text.trim().replace(",", "."));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String formatPlain(double value) {
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }

    private static String formatDecimal(double value, int fractionDigits) {
        NumberFormat nf = NumberFormat.getNumberInstance(PT_BR);
        nf.setMinimumFractionDigits(fractionDigits);
        nf.setMaximumFractionDigits(fractionDigits);
        return nf.format(value);
    }

    private static String formatCurrency(double value) {
        return "R$ " + formatDecimal(value, 2);
    }

    private static String formatDate(LocalDate date) {
        return date == null ? "--" : "%02d/%02d/%04d".formatted(date.getDayOfMonth(), date.getMonthValue(), date.getYear());
    }
}
