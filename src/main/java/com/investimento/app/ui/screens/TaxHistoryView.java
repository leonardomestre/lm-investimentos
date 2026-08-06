package com.investimento.app.ui.screens;

import com.investimento.app.data.model.Category;
import com.investimento.app.data.model.OperationType;
import com.investimento.app.dto.AnnualIncomeTaxSummary;
import com.investimento.app.dto.AssetDTO;
import com.investimento.app.dto.MonthlyTaxAssessment;
import com.investimento.app.dto.RealizedSale;
import com.investimento.app.dto.TransactionDTO;
import com.investimento.app.service.AssetService;
import com.investimento.app.service.IncomeTaxService;
import com.investimento.app.service.PositionService;
import com.investimento.app.service.TransactionService;
import javafx.collections.FXCollections;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.util.StringConverter;

import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Tela Histórico e Relatório para Imposto de Renda (ATV-17, RF07, planejamento
 * 5.6) — substitui o stub da ATV-07. Três blocos exigidos pela atividade:
 * (1) tabela filtrável (ano/mês/ativo) de todas as transações, (2) apuração
 * mensal de ganho de capital por categoria tributável ({@link IncomeTaxService},
 * ATV-11), (3) resumo consolidado por categoria do período selecionado — mais
 * exportação CSV de apoio à declaração (não é o formato oficial da Receita).
 *
 * <p><b>Renda fixa não entra na apuração de ganho de capital</b> (ATV-11 já
 * deixa isso explícito — IR retido na fonte, projeção de valor líquido é a
 * ATV-15). <b>Câmbio também não entra</b>: a regra de tributação de câmbio
 * nunca foi definida no planejamento (decisão em aberto documentada na
 * ATV-11/CLAUDE.md) — esta tela não inventa uma regra, simplesmente não
 * apura ganho de capital para {@link Category#FOREX}. Transações de ambas as
 * categorias continuam aparecendo normalmente na tabela filtrável (é só um
 * histórico de lançamentos, não um cálculo fiscal) e no CSV exportado, só sem
 * as colunas "GanhoPerdaRealizado"/"Isento" preenchidas quando fizer sentido.</p>
 */
public class TaxHistoryView implements ScreenView {

    private static final Locale PT_BR = new Locale("pt", "BR");
    private static final List<Category> TAXABLE_CATEGORIES = List.of(Category.STOCKS, Category.FIIS, Category.CRYPTO);
    private static final String[] FULL_MONTH_NAMES = {
            "Janeiro", "Fevereiro", "Março", "Abril", "Maio", "Junho",
            "Julho", "Agosto", "Setembro", "Outubro", "Novembro", "Dezembro"
    };
    private static final long ALL_ASSETS_ID = -1L;
    private static final int ALL_MONTHS = 0;

    private final TransactionService transactionService;
    private final IncomeTaxService incomeTaxService;
    private final PositionService positionService;
    private final AssetService assetService;

    private final VBox root;
    private final Label subtitleLabel;
    private final Label csvCaptionLabel = new Label(
            "Planilha de apoio para preencher a declaração manualmente — não é o arquivo de importação oficial do "
                    + "programa da Receita Federal.");

    private final ComboBox<Integer> yearCombo = new ComboBox<>();
    private final ComboBox<Integer> monthCombo = new ComboBox<>();
    private final ComboBox<Long> assetCombo = new ComboBox<>();
    private final Button applyFiltersButton = new Button("Aplicar filtros");

    private final Map<Category, Label> taxCategoryPills = new EnumMap<>(Category.class);
    private Category selectedTaxCategory = Category.STOCKS;

    private final VBox taxAssessmentBody = new VBox(12);
    private final VBox annualSummaryBody = new VBox(12);
    private final VBox transactionsBody = new VBox();

    private Map<Long, AssetDTO> assetsById = Map.of();
    private int selectedYear = LocalDate.now().getYear();
    private Integer selectedMonth; // null = todos os meses
    private Long selectedAssetId; // null = todos os ativos
    private List<TransactionDTO> filteredTransactions = List.of();
    private AnnualIncomeTaxSummary annualSummary;
    private Map<Long, RealizedSale> realizedSalesByTransactionId = Map.of();
    private boolean initialized;

    public TaxHistoryView(TransactionService transactionService,
                           IncomeTaxService incomeTaxService,
                           PositionService positionService,
                           AssetService assetService) {
        this.transactionService = transactionService;
        this.incomeTaxService = incomeTaxService;
        this.positionService = positionService;
        this.assetService = assetService;

        subtitleLabel = new Label("Selecione o período e clique em \"Aplicar filtros\".");
        subtitleLabel.getStyleClass().add("header-subtitle");

        HBox header = buildHeader();
        VBox filtersCard = buildFiltersCard();
        VBox taxAssessmentCard = buildTaxAssessmentCard();
        VBox annualSummaryCard = buildAnnualSummaryCard();
        VBox transactionsCard = buildTransactionsCard();

        VBox contentBody = new VBox(20, filtersCard, taxAssessmentCard, annualSummaryCard, transactionsCard);
        contentBody.setPadding(new Insets(24, 28, 28, 28));

        ScrollPane scrollPane = new ScrollPane(contentBody);
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        root = new VBox(header, scrollPane);
        root.setStyle("-fx-background-color: -fx-color-bg-shell;");
    }

    @Override
    public Parent getRoot() {
        return root;
    }

    @Override
    public void onShow() {
        refresh();
    }

    // =====================================================================
    // Header
    // =====================================================================

    private HBox buildHeader() {
        Label title = new Label("Histórico e IR");
        title.getStyleClass().add("header-title");
        VBox titleBox = new VBox(4, title, subtitleLabel);
        HBox.setHgrow(titleBox, Priority.ALWAYS);

        Button exportButton = new Button("Exportar CSV");
        exportButton.setId("exportCsvButton");
        exportButton.getStyleClass().add("button-primary");
        exportButton.setOnAction(e -> exportCsv());

        csvCaptionLabel.getStyleClass().add("content-card-subtitle");
        csvCaptionLabel.setWrapText(true);
        csvCaptionLabel.setMaxWidth(220);

        VBox exportBox = new VBox(5, exportButton, csvCaptionLabel);
        exportBox.setAlignment(Pos.TOP_RIGHT);

        HBox header = new HBox(20, titleBox, exportBox);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("header-bar");
        return header;
    }

    // =====================================================================
    // Filtros (ano/mês/ativo) — só recalcula ao clicar "Aplicar filtros"
    // =====================================================================

    private VBox buildFiltersCard() {
        Label title = new Label("Filtros");
        title.getStyleClass().add("content-card-title");

        yearCombo.setId("yearCombo");
        yearCombo.getStyleClass().add("text-field");
        yearCombo.setMaxWidth(Double.MAX_VALUE);

        monthCombo.setId("monthCombo");
        monthCombo.getStyleClass().add("text-field");
        monthCombo.setMaxWidth(Double.MAX_VALUE);
        monthCombo.setItems(FXCollections.observableArrayList(rangeInclusive(ALL_MONTHS, 12)));
        monthCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(Integer month) {
                if (month == null || month == ALL_MONTHS) {
                    return "Todos os meses";
                }
                return FULL_MONTH_NAMES[month - 1];
            }

            @Override
            public Integer fromString(String s) {
                return null;
            }
        });

        assetCombo.setId("assetCombo");
        assetCombo.getStyleClass().add("text-field");
        assetCombo.setMaxWidth(Double.MAX_VALUE);
        assetCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(Long assetId) {
                if (assetId == null || assetId == ALL_ASSETS_ID) {
                    return "Todos os ativos";
                }
                AssetDTO asset = assetsById.get(assetId);
                return asset == null ? "Ativo #" + assetId : assetLabel(asset);
            }

            @Override
            public Long fromString(String s) {
                return null;
            }
        });

        applyFiltersButton.setId("applyFiltersButton");
        applyFiltersButton.getStyleClass().add("button-primary");
        applyFiltersButton.setOnAction(e -> applyFilters());

        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.getColumnConstraints().addAll(
                pctColumn(20), pctColumn(24), pctColumn(36), pctColumn(20));
        grid.add(fieldGroup("ANO", yearCombo), 0, 0);
        grid.add(fieldGroup("MÊS", monthCombo), 1, 0);
        grid.add(fieldGroup("ATIVO", assetCombo), 2, 0);

        VBox buttonGroup = new VBox(7, new Label(" "), applyFiltersButton);
        applyFiltersButton.setMaxWidth(Double.MAX_VALUE);
        grid.add(buttonGroup, 3, 0);

        VBox card = new VBox(14, title, grid);
        card.getStyleClass().add("content-card");
        return card;
    }

    private VBox fieldGroup(String labelText, Node control) {
        Label label = new Label(labelText);
        label.getStyleClass().add("field-label");
        if (control instanceof Region region) {
            region.setMaxWidth(Double.MAX_VALUE);
        }
        return new VBox(7, label, control);
    }

    // =====================================================================
    // Orquestração de dados
    // =====================================================================

    private void refresh() {
        List<AssetDTO> assets = assetService.listAssets(true); // inclui inativos - historico de IR nao some (ATV-11)
        assetsById = assets.stream().collect(Collectors.toMap(AssetDTO::id, a -> a));

        List<Integer> years = availableYears();
        Integer previousYear = yearCombo.getValue();
        yearCombo.setItems(FXCollections.observableArrayList(years));

        List<Long> assetIds = new ArrayList<>();
        assetIds.add(ALL_ASSETS_ID);
        assets.stream()
                .sorted(Comparator.comparing(TaxHistoryView::assetLabel, String.CASE_INSENSITIVE_ORDER))
                .forEach(a -> assetIds.add(a.id()));
        Long previousAsset = assetCombo.getValue();
        assetCombo.setItems(FXCollections.observableArrayList(assetIds));

        if (!initialized) {
            yearCombo.setValue(years.contains(selectedYear) ? selectedYear : years.get(years.size() - 1));
            monthCombo.setValue(ALL_MONTHS);
            assetCombo.setValue(ALL_ASSETS_ID);
            initialized = true;
        } else {
            yearCombo.setValue(previousYear != null && years.contains(previousYear) ? previousYear : selectedYear);
            assetCombo.setValue(previousAsset != null && assetIds.contains(previousAsset) ? previousAsset : ALL_ASSETS_ID);
        }

        applyFilters();
    }

    /** Anos com pelo menos 1 transação, sempre incluindo o ano corrente. */
    private List<Integer> availableYears() {
        Set<Integer> years = transactionService.listAll().stream()
                .map(t -> t.date().getYear())
                .collect(Collectors.toCollection(java.util.TreeSet::new));
        years.add(LocalDate.now().getYear());
        List<Integer> sorted = new ArrayList<>(years);
        sorted.sort(Comparator.reverseOrder());
        return sorted;
    }

    /**
     * Lê os valores atuais dos 3 combos e recalcula tudo — só é chamado pelo
     * clique em "Aplicar filtros" (ou pelo carregamento inicial/onShow), nunca
     * por um listener de mudança de combo, conforme a armadilha da atividade
     * ("não recalcule a cada digitação/seleção no filtro").
     */
    /**
     * Seam de teste (mesmo pacote) — define os 3 filtros e dispara o mesmo
     * caminho de código do clique em "Aplicar filtros" ({@link
     * #applyFiltersButton}{@code .fire()}, não uma chamada direta a {@link
     * #applyFilters()}), para exercitar também o binding do botão.
     *
     * @param year  ano (nunca {@code null} na prática, mas aceito por
     *              simetria com os demais parâmetros)
     * @param month {@code null} = todos os meses, 1-12 = mês específico
     * @param assetId {@code null} = todos os ativos
     */
    void setFiltersForTest(Integer year, Integer month, Long assetId) {
        if (year != null) {
            yearCombo.setValue(year);
        }
        monthCombo.setValue(month == null ? ALL_MONTHS : month);
        assetCombo.setValue(assetId == null ? ALL_ASSETS_ID : assetId);
        applyFiltersButton.fire();
    }

    /** Seam de teste (mesmo pacote) — simula clicar num pill de categoria da apuração mensal. */
    void selectTaxCategoryForTest(Category category) {
        selectedTaxCategory = category;
        applyTaxPillStyles();
        renderTaxAssessmentTable();
    }

    private void applyFilters() {
        selectedYear = yearCombo.getValue() != null ? yearCombo.getValue() : LocalDate.now().getYear();
        Integer month = monthCombo.getValue();
        selectedMonth = (month == null || month == ALL_MONTHS) ? null : month;
        Long assetId = assetCombo.getValue();
        selectedAssetId = (assetId == null || assetId == ALL_ASSETS_ID) ? null : assetId;

        LocalDate start = selectedMonth != null
                ? YearMonth.of(selectedYear, selectedMonth).atDay(1)
                : LocalDate.of(selectedYear, 1, 1);
        LocalDate end = selectedMonth != null
                ? YearMonth.of(selectedYear, selectedMonth).atEndOfMonth()
                : LocalDate.of(selectedYear, 12, 31);

        List<TransactionDTO> periodTransactions = transactionService.listByPeriod(start, end);
        filteredTransactions = (selectedAssetId == null
                ? periodTransactions
                : periodTransactions.stream().filter(t -> selectedAssetId.equals(t.assetId())).toList())
                .stream()
                .sorted(Comparator.comparing(TransactionDTO::date).reversed())
                .toList();

        annualSummary = incomeTaxService.assessAnnualSummary(selectedYear);
        realizedSalesByTransactionId = buildRealizedSalesIndex(filteredTransactions);

        subtitleLabel.setText(filteredTransactions.size()
                + (filteredTransactions.size() == 1 ? " lançamento considerado" : " lançamentos considerados")
                + " · ano-calendário " + selectedYear);

        renderAll();
    }

    /**
     * {@link RealizedSale} de cada transação {@code SELL} da lista, indexado
     * por {@code transactionId} — reaproveita {@link PositionService#calculateRealizedSales(long)}
     * (ATV-10) uma vez por ativo distinto em vez de recalcular o custo médio
     * por transação.
     */
    private Map<Long, RealizedSale> buildRealizedSalesIndex(List<TransactionDTO> transactions) {
        Set<Long> assetIds = transactions.stream()
                .filter(t -> t.operationType() == OperationType.SELL)
                .map(TransactionDTO::assetId)
                .collect(Collectors.toSet());
        Map<Long, RealizedSale> index = new HashMap<>();
        for (Long assetId : assetIds) {
            for (RealizedSale sale : positionService.calculateRealizedSales(assetId)) {
                index.put(sale.transactionId(), sale);
            }
        }
        return index;
    }

    private void renderAll() {
        renderTaxAssessmentTable();
        renderAnnualSummaryTable();
        renderTransactionsTable();
    }

    // =====================================================================
    // Bloco 1 — Apuração mensal de ganho de capital por categoria (ATV-11)
    // =====================================================================

    private VBox buildTaxAssessmentCard() {
        Label title = new Label("Apuração mensal — ganho de capital");
        title.getStyleClass().add("content-card-title");
        Label subtitle = new Label("isenção de R$ 20.000/mês em vendas de ações e R$ 35.000/mês em cripto; FIIs "
                + "nunca têm isenção. Renda fixa e câmbio não entram nesta apuração.");
        subtitle.getStyleClass().add("content-card-subtitle");
        subtitle.setWrapText(true);
        VBox titleBox = new VBox(4, title, subtitle);

        HBox pillsRow = new HBox(5);
        for (Category category : TAXABLE_CATEGORIES) {
            Label pill = new Label(categoryLabel(category));
            pill.setId("taxPill" + category.name());
            pill.setCursor(Cursor.HAND);
            pill.setOnMouseClicked(e -> {
                selectedTaxCategory = category;
                applyTaxPillStyles();
                renderTaxAssessmentTable();
            });
            taxCategoryPills.put(category, pill);
            pillsRow.getChildren().add(pill);
        }
        applyTaxPillStyles();

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox headerRow = new HBox(titleBox, spacer, pillsRow);
        headerRow.setAlignment(Pos.TOP_LEFT);

        taxAssessmentBody.setId("taxAssessmentBody");
        VBox card = new VBox(14, headerRow, taxAssessmentBody);
        card.getStyleClass().add("content-card");
        return card;
    }

    private void applyTaxPillStyles() {
        for (Map.Entry<Category, Label> entry : taxCategoryPills.entrySet()) {
            entry.getValue().getStyleClass().removeAll("tab-pill", "tab-pill-active");
            entry.getValue().getStyleClass().add(entry.getKey() == selectedTaxCategory ? "tab-pill-active" : "tab-pill");
        }
    }

    private void renderTaxAssessmentTable() {
        taxAssessmentBody.getChildren().clear();
        if (annualSummary == null) {
            return;
        }

        List<MonthlyTaxAssessment> assessments = annualSummary.assessmentsByCategory()
                .getOrDefault(selectedTaxCategory, List.of()).stream()
                .filter(a -> selectedMonth == null || a.month().getMonthValue() == selectedMonth)
                .sorted(Comparator.comparing(MonthlyTaxAssessment::month))
                .toList();

        if (assessments.isEmpty()) {
            Label empty = new Label("Nenhuma venda de " + categoryLabel(selectedTaxCategory).toLowerCase(PT_BR)
                    + " considerada no período selecionado.");
            empty.getStyleClass().add("content-card-subtitle");
            empty.setWrapText(true);
            taxAssessmentBody.getChildren().add(empty);
        } else {
            GridPane grid = new GridPane();
            grid.setId("taxAssessmentGrid");
            grid.setHgap(14);
            grid.getColumnConstraints().addAll(
                    pctColumn(22), pctColumn(20), pctColumn(18), pctColumn(20), pctColumn(20));

            grid.add(tableHeaderCell("MÊS", HPos.LEFT), 0, 0);
            grid.add(tableHeaderCell("VENDAS", HPos.RIGHT), 1, 0);
            grid.add(tableHeaderCell("SITUAÇÃO", HPos.LEFT), 2, 0);
            grid.add(tableHeaderCell("RESULTADO", HPos.RIGHT), 3, 0);
            grid.add(tableHeaderCell("IMPOSTO DEVIDO", HPos.RIGHT), 4, 0);

            double totalSold = 0;
            double totalGain = 0;
            double totalTax = 0;
            for (int i = 0; i < assessments.size(); i++) {
                MonthlyTaxAssessment a = assessments.get(i);
                int row = i + 1;
                boolean last = i == assessments.size() - 1;

                grid.add(tableCell(FULL_MONTH_NAMES[a.month().getMonthValue() - 1], "table-row-primary", HPos.LEFT, last), 0, row);
                grid.add(tableCell(formatDecimal(a.totalSold(), 2), "table-cell-numeric", HPos.RIGHT, last), 1, row);
                grid.add(situationCell(a.exempt(), last), 2, row);
                grid.add(tableCell(formatSignedNumber(a.grossGain(), 2),
                        a.grossGain() >= 0 ? "table-cell-gain" : "table-cell-loss", HPos.RIGHT, last), 3, row);
                grid.add(tableCell(formatDecimal(a.taxDue(), 2), "table-cell-numeric", HPos.RIGHT, last), 4, row);

                totalSold += a.totalSold();
                totalGain += a.grossGain();
                totalTax += a.taxDue();
            }
            taxAssessmentBody.getChildren().add(grid);

            HBox footer = new HBox();
            footer.setAlignment(Pos.CENTER_RIGHT);
            footer.setSpacing(20);
            footer.setStyle("-fx-border-color: transparent transparent -fx-color-border-row transparent;"
                    + " -fx-border-width: 1 0 0 0; -fx-padding: 12 0 0 0;");
            Label totalsLabel = new Label("Totais do período: vendas " + formatDecimal(totalSold, 2)
                    + " · resultado " + formatSignedNumber(totalGain, 2)
                    + " · imposto " + formatDecimal(totalTax, 2));
            totalsLabel.setId("taxAssessmentTotalsLabel");
            totalsLabel.setStyle("-fx-font-family: 'IBM Plex Mono SemiBold'; -fx-font-size: 13px; -fx-text-fill: -fx-color-text-primary;");
            footer.getChildren().add(totalsLabel);
            taxAssessmentBody.getChildren().add(footer);
        }

        double accumulatedLoss = incomeTaxService.getAccumulatedLoss(selectedTaxCategory, YearMonth.now());
        Label lossLabel = new Label("Prejuízo acumulado de " + categoryLabel(selectedTaxCategory).toLowerCase(PT_BR)
                + " (informativo, não abatido automaticamente do imposto acima): "
                + formatCurrency(accumulatedLoss));
        lossLabel.setId("accumulatedLossLabel");
        lossLabel.getStyleClass().add("content-card-subtitle");
        lossLabel.setWrapText(true);
        taxAssessmentBody.getChildren().add(lossLabel);
    }

    private Label situationCell(boolean exempt, boolean last) {
        Label label = new Label(exempt ? "Isento" : "Tributável");
        label.getStyleClass().add(exempt ? "table-cell-gain" : "table-cell-loss");
        GridPane.setHalignment(label, HPos.LEFT);
        styleRowCell(label, last);
        return label;
    }

    // =====================================================================
    // Bloco 2 — Resumo consolidado por categoria (do período selecionado)
    // =====================================================================

    private VBox buildAnnualSummaryCard() {
        Label title = new Label("Resumo consolidado por categoria");
        title.getStyleClass().add("content-card-title");

        annualSummaryBody.setId("annualSummaryBody");
        VBox card = new VBox(14, title, annualSummaryBody);
        card.getStyleClass().add("content-card");
        return card;
    }

    private void renderAnnualSummaryTable() {
        annualSummaryBody.getChildren().clear();
        if (annualSummary == null) {
            return;
        }

        Label periodLabel = new Label(selectedMonth == null
                ? "ano-calendário " + selectedYear
                : FULL_MONTH_NAMES[selectedMonth - 1] + "/" + selectedYear);
        periodLabel.setId("annualSummaryPeriodLabel");
        periodLabel.getStyleClass().add("content-card-subtitle");
        annualSummaryBody.getChildren().add(periodLabel);

        GridPane grid = new GridPane();
        grid.setId("annualSummaryGrid");
        grid.setHgap(14);
        grid.getColumnConstraints().addAll(pctColumn(28), pctColumn(24), pctColumn(24), pctColumn(24));

        grid.add(tableHeaderCell("CATEGORIA", HPos.LEFT), 0, 0);
        grid.add(tableHeaderCell("VENDAS", HPos.RIGHT), 1, 0);
        grid.add(tableHeaderCell("RESULTADO", HPos.RIGHT), 2, 0);
        grid.add(tableHeaderCell("IMPOSTO DEVIDO", HPos.RIGHT), 3, 0);

        double totalTaxDue = 0;
        for (int i = 0; i < TAXABLE_CATEGORIES.size(); i++) {
            Category category = TAXABLE_CATEGORIES.get(i);
            int row = i + 1;
            boolean last = i == TAXABLE_CATEGORIES.size() - 1;

            List<MonthlyTaxAssessment> assessments = annualSummary.assessmentsByCategory()
                    .getOrDefault(category, List.of()).stream()
                    .filter(a -> selectedMonth == null || a.month().getMonthValue() == selectedMonth)
                    .toList();
            double sold = assessments.stream().mapToDouble(MonthlyTaxAssessment::totalSold).sum();
            double gain = assessments.stream().mapToDouble(MonthlyTaxAssessment::grossGain).sum();
            double tax = assessments.stream().mapToDouble(MonthlyTaxAssessment::taxDue).sum();
            totalTaxDue += tax;

            grid.add(tableCell(categoryLabel(category), "table-row-primary", HPos.LEFT, last), 0, row);
            grid.add(tableCell(formatDecimal(sold, 2), "table-cell-numeric", HPos.RIGHT, last), 1, row);
            grid.add(tableCell(formatSignedNumber(gain, 2), gain >= 0 ? "table-cell-gain" : "table-cell-loss", HPos.RIGHT, last), 2, row);
            grid.add(tableCell(formatDecimal(tax, 2), "table-cell-numeric", HPos.RIGHT, last), 3, row);
        }
        annualSummaryBody.getChildren().add(grid);

        Label totalLabel = new Label("Imposto total devido no período: " + formatCurrency(totalTaxDue));
        totalLabel.setId("annualSummaryTotalLabel");
        totalLabel.setStyle("-fx-font-family: 'IBM Plex Mono SemiBold'; -fx-font-size: 14px; -fx-text-fill: -fx-color-text-primary;");
        HBox totalRow = new HBox(totalLabel);
        totalRow.setAlignment(Pos.CENTER_RIGHT);
        totalRow.setStyle("-fx-border-color: transparent transparent -fx-color-border-row transparent;"
                + " -fx-border-width: 1 0 0 0; -fx-padding: 12 0 0 0;");
        annualSummaryBody.getChildren().add(totalRow);
    }

    // =====================================================================
    // Bloco 3 — Histórico completo de operações (tabela filtrável)
    // =====================================================================

    private VBox buildTransactionsCard() {
        Label title = new Label("Histórico completo de operações");
        title.getStyleClass().add("content-card-title");

        transactionsBody.setId("transactionsBody");
        VBox card = new VBox(14, title, transactionsBody);
        card.getStyleClass().add("content-card");
        return card;
    }

    private void renderTransactionsTable() {
        transactionsBody.getChildren().clear();

        if (filteredTransactions.isEmpty()) {
            Label empty = new Label("Nenhuma transação encontrada para os filtros selecionados.");
            empty.getStyleClass().add("content-card-subtitle");
            empty.setWrapText(true);
            transactionsBody.getChildren().add(empty);
            return;
        }

        GridPane grid = new GridPane();
        grid.setId("transactionsGrid");
        grid.setHgap(12);
        grid.getColumnConstraints().addAll(
                pctColumn(12), pctColumn(16), pctColumn(14), pctColumn(12),
                pctColumn(10), pctColumn(12), pctColumn(12), pctColumn(12));

        grid.add(tableHeaderCell("DATA", HPos.LEFT), 0, 0);
        grid.add(tableHeaderCell("ATIVO", HPos.LEFT), 1, 0);
        grid.add(tableHeaderCell("CATEGORIA", HPos.LEFT), 2, 0);
        grid.add(tableHeaderCell("OPERAÇÃO", HPos.LEFT), 3, 0);
        grid.add(tableHeaderCell("QTD.", HPos.RIGHT), 4, 0);
        grid.add(tableHeaderCell("PREÇO", HPos.RIGHT), 5, 0);
        grid.add(tableHeaderCell("TOTAL", HPos.RIGHT), 6, 0);
        grid.add(tableHeaderCell("RESULTADO", HPos.RIGHT), 7, 0);

        for (int i = 0; i < filteredTransactions.size(); i++) {
            TransactionDTO t = filteredTransactions.get(i);
            int row = i + 1;
            boolean last = i == filteredTransactions.size() - 1;
            AssetDTO asset = assetsById.get(t.assetId());
            double total = t.quantity() * t.unitPrice();
            RealizedSale sale = realizedSalesByTransactionId.get(t.id());

            grid.add(tableCell(formatDate(t.date()), "table-row-secondary", HPos.LEFT, last), 0, row);
            grid.add(tableCell(asset != null ? assetLabel(asset) : "#" + t.assetId(), "table-row-primary", HPos.LEFT, last), 1, row);
            grid.add(tableCell(asset != null ? categoryLabel(asset.category()) : "--", "table-row-secondary", HPos.LEFT, last), 2, row);
            grid.add(operationCell(t.operationType(), last), 3, row);
            grid.add(tableCell(formatQuantity(t.quantity()), "table-cell-numeric", HPos.RIGHT, last), 4, row);
            grid.add(tableCell(formatDecimal(t.unitPrice(), 2), "table-cell-numeric", HPos.RIGHT, last), 5, row);
            grid.add(tableCell(formatDecimal(total, 2), "table-cell-numeric", HPos.RIGHT, last), 6, row);

            String resultText = sale != null ? formatSignedNumber(sale.realizedGainAmount(), 2) : "--";
            String resultStyle = sale == null ? "table-row-secondary"
                    : sale.realizedGainAmount() >= 0 ? "table-cell-gain" : "table-cell-loss";
            grid.add(tableCell(resultText, resultStyle, HPos.RIGHT, last), 7, row);
        }

        transactionsBody.getChildren().add(grid);
    }

    private Label operationCell(OperationType type, boolean last) {
        Label label = new Label(type == OperationType.BUY ? "Compra" : "Venda");
        label.getStyleClass().add(type == OperationType.BUY ? "table-cell-gain" : "table-cell-loss");
        GridPane.setHalignment(label, HPos.LEFT);
        styleRowCell(label, last);
        return label;
    }

    // =====================================================================
    // Exportação CSV
    // =====================================================================

    private void exportCsv() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Exportar histórico para IR (CSV)");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV (Excel)", "*.csv"));
        String monthSuffix = selectedMonth != null ? "-%02d".formatted(selectedMonth) : "";
        chooser.setInitialFileName("historico-ir-" + selectedYear + monthSuffix + ".csv");

        File file = root.getScene() != null && root.getScene().getWindow() != null
                ? chooser.showSaveDialog(root.getScene().getWindow())
                : null;
        if (file == null) {
            return;
        }
        try {
            writeCsv(file, filteredTransactions);
        } catch (IOException e) {
            System.err.println("Falha ao exportar CSV: " + e.getMessage());
        }
    }

    /**
     * Grava o CSV de apoio (formato exato da ATV-17: separador {@code ;},
     * vírgula decimal, cabeçalho/rótulos em português). Extraído em método
     * próprio (não privado) para ser exercitado por teste headless sem
     * depender de {@link FileChooser}/{@code Stage} (mesma estratégia de seam
     * de teste já usada nas ATV-13 a 16 para pular interações modais/de
     * janela).
     *
     * <p>UTF-8 com BOM (não o {@code java.io.FileWriter} puro, que usa o
     * charset padrão da plataforma) — sem isso o Excel no Windows exibe
     * "Ações"/"Câmbio" com acentuação corrompida ao abrir o arquivo
     * diretamente por duplo clique.</p>
     */
    void writeCsv(File file, List<TransactionDTO> transactions) throws IOException {
        try (Writer writer = new OutputStreamWriter(Files.newOutputStream(file.toPath()), StandardCharsets.UTF_8)) {
            writer.write('﻿');
            writer.write("Data;Ativo;Categoria;TipoOperacao;Quantidade;PrecoUnitario;ValorTotal;Taxas;"
                    + "GanhoPerdaRealizado;Isento\r\n");

            Map<Long, RealizedSale> salesIndex = buildRealizedSalesIndex(transactions);
            Map<Category, Map<YearMonth, Boolean>> exemptionByCategoryMonth = buildExemptionIndex();

            for (TransactionDTO t : transactions) {
                AssetDTO asset = assetsById.get(t.assetId());
                double total = t.quantity() * t.unitPrice();
                RealizedSale sale = salesIndex.get(t.id());
                boolean isSell = t.operationType() == OperationType.SELL;

                String ganhoPerda = "";
                String isento = "";
                if (isSell && sale != null) {
                    ganhoPerda = formatCsvNumber(sale.realizedGainAmount(), 2);
                    if (asset != null) {
                        Boolean exempt = exemptionByCategoryMonth
                                .getOrDefault(asset.category(), Map.of())
                                .get(YearMonth.from(t.date()));
                        if (exempt != null) {
                            isento = exempt ? "Sim" : "Não";
                        }
                    }
                }

                writer.write(String.join(";",
                        t.date().toString(),
                        csvEscape(asset != null ? assetLabel(asset) : "#" + t.assetId()),
                        csvEscape(asset != null ? categoryLabel(asset.category()) : ""),
                        t.operationType() == OperationType.BUY ? "COMPRA" : "VENDA",
                        formatCsvNumber(t.quantity(), t.quantity() == Math.floor(t.quantity()) ? 0 : 4),
                        formatCsvNumber(t.unitPrice(), 2),
                        formatCsvNumber(total, 2),
                        formatCsvNumber(t.fees(), 2),
                        ganhoPerda,
                        isento));
                writer.write("\r\n");
            }
        }
    }

    /** {@code exempt} de cada (categoria, mês) do {@code selectedYear} — usado para a coluna "Isento" do CSV. */
    private Map<Category, Map<YearMonth, Boolean>> buildExemptionIndex() {
        Map<Category, Map<YearMonth, Boolean>> result = new EnumMap<>(Category.class);
        if (annualSummary == null) {
            return result;
        }
        for (Category category : TAXABLE_CATEGORIES) {
            Map<YearMonth, Boolean> byMonth = annualSummary.assessmentsByCategory()
                    .getOrDefault(category, List.of()).stream()
                    .collect(Collectors.toMap(MonthlyTaxAssessment::month, MonthlyTaxAssessment::exempt));
            result.put(category, byMonth);
        }
        return result;
    }

    private static String csvEscape(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(";") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    // =====================================================================
    // Helpers de layout
    // =====================================================================

    private ColumnConstraints pctColumn(double percent) {
        ColumnConstraints c = new ColumnConstraints();
        c.setPercentWidth(percent);
        return c;
    }

    private Label tableHeaderCell(String text, HPos align) {
        Label label = new Label(text);
        label.getStyleClass().add("table-header-cell");
        GridPane.setHalignment(label, align);
        return label;
    }

    private Label tableCell(String text, String styleClass, HPos align, boolean last) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        label.setMaxWidth(Double.MAX_VALUE);
        GridPane.setHalignment(label, align);
        styleRowCell(label, last);
        return label;
    }

    private void styleRowCell(Region node, boolean last) {
        node.setPadding(new Insets(11, 0, 11, 0));
        if (!last) {
            node.getStyleClass().add("table-row-divider");
        }
    }

    private static List<Integer> rangeInclusive(int from, int to) {
        List<Integer> result = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            result.add(i);
        }
        return result;
    }

    // =====================================================================
    // Rótulos
    // =====================================================================

    private static String assetLabel(AssetDTO asset) {
        if (asset.category() == Category.FOREX) {
            return asset.currency();
        }
        return asset.ticker() != null && !asset.ticker().isBlank() ? asset.ticker() : asset.displayName();
    }

    private static String categoryLabel(Category category) {
        return switch (category) {
            case STOCKS -> "Ações";
            case FIIS -> "FIIs";
            case FIXED_INCOME -> "Renda fixa";
            case CRYPTO -> "Criptomoedas";
            case FOREX -> "Câmbio";
        };
    }

    // =====================================================================
    // Parsing / formatação (pt-BR)
    // =====================================================================

    private static String formatQuantity(double qty) {
        return formatDecimal(qty, qty == Math.floor(qty) ? 0 : 4);
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

    private static String formatSignedNumber(double value, int fractionDigits) {
        String sign = value < 0 ? "−" : "+";
        return sign + formatDecimal(Math.abs(value), fractionDigits);
    }

    private static String formatDate(LocalDate date) {
        return date == null ? "--" : "%02d/%02d/%04d".formatted(date.getDayOfMonth(), date.getMonthValue(), date.getYear());
    }

    /**
     * Formatação numérica do CSV (ATV-17): vírgula decimal, SEM separador de
     * milhar (o exemplo literal da atividade mostra {@code 3850,00}, não
     * {@code 3.850,00}) — evita qualquer ambiguidade de parsing no Excel,
     * mesmo não sendo estritamente necessário já que o separador de coluna é
     * {@code ;}, não {@code ,}.
     */
    private static String formatCsvNumber(double value, int fractionDigits) {
        NumberFormat nf = NumberFormat.getNumberInstance(PT_BR);
        nf.setGroupingUsed(false);
        nf.setMinimumFractionDigits(fractionDigits);
        nf.setMaximumFractionDigits(fractionDigits);
        return nf.format(value);
    }
}
