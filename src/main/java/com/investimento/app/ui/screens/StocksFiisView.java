package com.investimento.app.ui.screens;

import com.investimento.app.data.model.Asset;
import com.investimento.app.data.model.AssetType;
import com.investimento.app.data.model.Category;
import com.investimento.app.data.model.Distribution;
import com.investimento.app.data.model.DistributionType;
import com.investimento.app.data.model.QuoteHistory;
import com.investimento.app.dto.AssetDTO;
import com.investimento.app.dto.PortfolioSummary;
import com.investimento.app.dto.Position;
import com.investimento.app.repository.AssetRepository;
import com.investimento.app.repository.DistributionRepository;
import com.investimento.app.repository.QuoteHistoryRepository;
import com.investimento.app.service.MarketService;
import com.investimento.app.service.PositionService;
import com.investimento.app.ui.CurrencyDisplay;
import com.investimento.app.ui.Theme;
import com.investimento.app.ui.ThemeManager;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.util.StringConverter;

import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Tela Ações e FIIs (ATV-14, RF05/RF06, planejamento 5.3) — tabela de
 * posições de renda variável (categorias {@link Category#STOCKS}/{@link
 * Category#FIIS} apenas), gráfico de linha do ativo selecionado comparado ao
 * preço médio de compra, e área de registro manual de proventos. Substitui o
 * stub da ATV-07.
 *
 * <p>Não confundir com a ATV-17 (Histórico/IR) — aqui é posição atual, não
 * histórico de transações fechadas para fins fiscais. Proventos registrados
 * aqui não entram no cálculo de IR (RF07 cobre só ganho de capital em venda,
 * a própria atividade proíbe inventar essa regra).</p>
 */
public class StocksFiisView implements ScreenView {

    /**
     * Cores de grafico do tema ativo (ver {@link ThemeManager}) — a linha
     * "PM" usa {@code neutralWarn}, e o texto "PM ..." ao lado dela usa
     * {@code neutralWarnDark}, mais escuro que a linha para legibilidade,
     * exatamente como no template.
     */
    private static Theme theme() {
        return ThemeManager.current();
    }

    private static final Locale PT_BR = new Locale("pt", "BR");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final String[] MONTH_ABBR =
            {"jan", "fev", "mar", "abr", "mai", "jun", "jul", "ago", "set", "out", "nov", "dez"};

    private final PositionService positionService;
    private final AssetRepository assetRepository;
    private final QuoteHistoryRepository quoteHistoryRepository;
    private final DistributionRepository distributionRepository;
    private final MarketService marketService;

    private final VBox root;
    private final VBox contentBody;
    private final Label subtitleLabel;

    private final Map<PositionFilter, Label> filterPills = new EnumMap<>(PositionFilter.class);
    private PositionFilter currentFilter = PositionFilter.ALL;

    private final GridPane kpiRow;
    private final VBox chartCardBody = new VBox(16);
    private final VBox chartCard;
    private final VBox positionsCardBody = new VBox(14);
    private final VBox positionsCard;
    private final VBox distributionsTableBody = new VBox();
    private final VBox distributionsTableCard;

    // ---- Formulário de registro de provento (card escuro, campos persistentes) ----
    private final ComboBox<Position> distAssetCombo = new ComboBox<>();
    private final ComboBox<DistributionType> distTypeCombo = new ComboBox<>();
    private final DatePicker distDatePicker = new DatePicker(LocalDate.now());
    private final TextField distValueField = new TextField();
    private final TextField distNotesField = new TextField();
    private final Label distFormErrorLabel = buildErrorLabel();
    private final Button saveDistributionButton = new Button("Salvar provento");
    private final VBox distributionFormCard;

    private List<Position> cachedPositions = List.of();
    private PortfolioSummary cachedSummary;
    private Map<Long, Double> cachedDailyChanges = Map.of();
    private Long selectedAssetId;

    public StocksFiisView(PositionService positionService,
                           AssetRepository assetRepository,
                           QuoteHistoryRepository quoteHistoryRepository,
                           DistributionRepository distributionRepository,
                           MarketService marketService) {
        this.positionService = positionService;
        this.assetRepository = assetRepository;
        this.quoteHistoryRepository = quoteHistoryRepository;
        this.distributionRepository = distributionRepository;
        this.marketService = marketService;

        subtitleLabel = new Label("Cadastre ativos de Ações/FIIs para começar.");
        subtitleLabel.getStyleClass().add("header-subtitle");

        HBox header = buildHeader();

        kpiRow = evenColumnsGrid(4, 12);

        chartCard = new VBox(chartCardBody);
        chartCard.getStyleClass().add("content-card");

        positionsCard = buildPositionsCardWrapper();
        distributionsTableCard = buildDistributionsTableCardWrapper();
        distributionFormCard = buildDistributionFormCard();

        GridPane distributionsRow = twoColumnRow(distributionFormCard, distributionsTableCard, 42, 58);

        contentBody = new VBox(20);
        contentBody.setPadding(new Insets(24, 28, 28, 28));
        contentBody.getChildren().setAll(kpiRow, chartCard, positionsCard, distributionsRow);

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
    // Header + filtro + "Atualizar cotações"
    // =====================================================================

    private HBox buildHeader() {
        Label title = new Label("Ações e FIIs");
        title.getStyleClass().add("header-title");
        VBox titleBox = new VBox(4, title, subtitleLabel);
        HBox.setHgrow(titleBox, Priority.ALWAYS);

        HBox filterPillsBox = buildFilterPills();

        Button updateButton = new Button("Atualizar cotações");
        updateButton.getStyleClass().add("button-primary");
        updateButton.setId("updateQuotesButton");
        updateButton.setOnAction(e -> triggerUpdate(updateButton));

        HBox actions = new HBox(10, filterPillsBox, updateButton);
        actions.setAlignment(Pos.CENTER_RIGHT);

        HBox header = new HBox(20, titleBox, actions);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("header-bar");
        return header;
    }

    private HBox buildFilterPills() {
        HBox box = new HBox(5);
        for (PositionFilter filter : PositionFilter.values()) {
            Label pill = new Label(filter.label);
            pill.setId("filterPill" + filter.name());
            pill.setCursor(Cursor.HAND);
            pill.setOnMouseClicked(e -> {
                currentFilter = filter;
                applyFilterStyles();
                renderPositionsTable();
            });
            filterPills.put(filter, pill);
            box.getChildren().add(pill);
        }
        applyFilterStyles();
        return box;
    }

    private void applyFilterStyles() {
        for (Map.Entry<PositionFilter, Label> entry : filterPills.entrySet()) {
            entry.getValue().getStyleClass().removeAll("tab-pill", "tab-pill-active");
            entry.getValue().getStyleClass().add(entry.getKey() == currentFilter ? "tab-pill-active" : "tab-pill");
        }
    }

    /** Mesmo padrão RT06 da ATV-12 — roda em background, nunca bloqueia a FX thread. */
    private void triggerUpdate(Button button) {
        List<Asset> assets = assetRepository.listAssets(false);
        Task<Void> task = marketService.updateQuotes(assets);

        button.disableProperty().bind(task.runningProperty());
        button.textProperty().bind(Bindings.when(task.runningProperty())
                .then("Atualizando...")
                .otherwise("Atualizar cotações"));

        task.setOnSucceeded(ev -> {
            button.disableProperty().unbind();
            button.textProperty().unbind();
            button.setDisable(false);
            button.setText("Atualizar cotações");
            refresh();
        });
        task.setOnFailed(ev -> {
            button.disableProperty().unbind();
            button.textProperty().unbind();
            button.setDisable(false);
            button.setText("Atualizar cotações");
        });

        Thread thread = new Thread(task, "market-update-stocks-fiis");
        thread.setDaemon(true);
        thread.start();
    }

    // =====================================================================
    // Orquestração de dados
    // =====================================================================

    private void refresh() {
        List<Asset> assets = assetRepository.listAssets(false);
        List<Position> allPositions = positionService.calculateAllPositions(false);
        // Armadilha documentada na ATV-14: calculateAllPositions(false) já
        // omite currentQuantity == 0 — não precisa filtrar isso aqui de novo.
        cachedPositions = allPositions.stream()
                .filter(p -> p.asset().category() == Category.STOCKS || p.asset().category() == Category.FIIS)
                .sorted(Comparator.comparing((Position p) -> p.asset().category())
                        .thenComparing(Comparator.comparingDouble(Position::currentValue).reversed()))
                .toList();
        // Resumo montado sobre allPositions (carteira inteira, nao a lista ja
        // filtrada por categoria) — a versao sem argumento recalcularia todas
        // as posicoes de novo.
        cachedSummary = positionService.calculatePortfolioSummary(allPositions);
        cachedDailyChanges = marketService.getDailyChanges(assets);

        if (selectedAssetId == null || cachedPositions.stream().noneMatch(p -> p.asset().id().equals(selectedAssetId))) {
            selectedAssetId = cachedPositions.isEmpty() ? null : cachedPositions.get(0).asset().id();
        }

        int totalCount = cachedPositions.size();
        subtitleLabel.setText(totalCount + (totalCount == 1 ? " posição" : " posições")
                + " · cotações via brapi.dev às " + LocalTime.now().format(TIME_FMT));

        renderAll();
    }

    private void renderAll() {
        renderKpiRow();
        renderChartCard();
        renderPositionsTable();
        renderDistributionsTable();
        refreshDistAssetComboItems();
    }

    private void refreshDistAssetComboItems() {
        Position previous = distAssetCombo.getValue();
        distAssetCombo.setItems(FXCollections.observableArrayList(cachedPositions));
        if (previous != null) {
            cachedPositions.stream()
                    .filter(p -> p.asset().id().equals(previous.asset().id()))
                    .findFirst()
                    .ifPresent(distAssetCombo::setValue);
        }
    }

    private void selectAsset(long assetId) {
        this.selectedAssetId = assetId;
        renderChartCard();
        renderPositionsTable();
    }

    /** Seam de teste (mesmo pacote) — simula clicar numa linha da tabela de posições. */
    void selectAssetForTest(long assetId) {
        selectAsset(assetId);
    }

    private boolean matchesFilter(Position p) {
        AssetType type = p.asset().type();
        return switch (currentFilter) {
            case ALL -> true;
            case STOCK -> type == AssetType.STOCK;
            case FII -> type == AssetType.FII;
            case ETF_BDR -> type == AssetType.ETF || type == AssetType.BDR;
        };
    }

    // =====================================================================
    // Bloco 1 — 4 KPI cards
    // =====================================================================

    private void renderKpiRow() {
        kpiRow.getChildren().clear();

        double totalCurrent = cachedPositions.stream().mapToDouble(Position::currentValue).sum();
        double totalInvested = cachedPositions.stream().mapToDouble(Position::investedValue).sum();
        double totalGainLoss = totalCurrent - totalInvested;
        double gainLossPct = totalInvested > 0 ? totalGainLoss / totalInvested * 100 : 0;
        double pctOfPortfolio = cachedSummary != null && cachedSummary.totalCurrentValue() > 0
                ? totalCurrent / cachedSummary.totalCurrentValue() * 100 : 0;

        double totalDailyDelta = 0;
        double totalDailyBase = 0;
        boolean hasDailyData = false;
        for (Position p : cachedPositions) {
            Double change = cachedDailyChanges.get(p.asset().id());
            if (change != null) {
                hasDailyData = true;
                double delta = p.currentValue() - p.currentValue() / (1 + change / 100.0);
                totalDailyDelta += delta;
                totalDailyBase += p.currentValue() - delta;
            }
        }
        double dailyPct = totalDailyBase > 0 ? totalDailyDelta / totalDailyBase * 100 : 0;

        int year = LocalDate.now().getYear();
        double proventosYear = loadDistributions().stream()
                .filter(r -> r.distribution().getPaymentDate().getYear() == year)
                .mapToDouble(r -> r.distribution().getValue())
                .sum();

        List<VBox> cards = new ArrayList<>();
        cards.add(buildKpiCard("VALOR EM RENDA VARIÁVEL", formatCurrency(totalCurrent), null,
                formatDecimal(pctOfPortfolio, 0) + "% da carteira", "kpi-footer-neutral"));
        cards.add(buildKpiCard("RESULTADO ACUMULADO", formatSignedCurrency(totalGainLoss), totalGainLoss >= 0,
                formatSignedPercent(gainLossPct), totalGainLoss >= 0 ? "kpi-footer-gain" : "kpi-footer-loss"));
        if (hasDailyData) {
            cards.add(buildKpiCard("VARIAÇÃO DO DIA", formatSignedCurrency(totalDailyDelta), totalDailyDelta >= 0,
                    formatSignedPercent(dailyPct), totalDailyDelta >= 0 ? "kpi-footer-gain" : "kpi-footer-loss"));
        } else {
            cards.add(buildKpiCard("VARIAÇÃO DO DIA", "--", null,
                    "clique em \"Atualizar cotações\"", "kpi-footer-neutral"));
        }
        cards.add(buildKpiCard("PROVENTOS EM " + year, formatCurrency(proventosYear), null,
                "registro manual", "kpi-footer-neutral"));

        for (int i = 0; i < cards.size(); i++) {
            VBox card = cards.get(i);
            card.setMaxWidth(Double.MAX_VALUE);
            kpiRow.add(card, i, 0);
        }
    }

    private VBox buildKpiCard(String label, String value, Boolean positive, String footerText, String footerStyleClass) {
        Label labelNode = new Label(label);
        labelNode.getStyleClass().add("kpi-label");

        Label valueNode = new Label(value);
        valueNode.getStyleClass().add("kpi-value");
        if (positive != null) {
            valueNode.setStyle("-fx-text-fill: " + (positive ? "-fx-color-gain;" : "-fx-color-loss;"));
        }

        Label footer = new Label(footerText);
        footer.getStyleClass().add(footerStyleClass);

        VBox card = new VBox(9, labelNode, valueNode, footer);
        card.getStyleClass().add("kpi-card");
        return card;
    }

    // =====================================================================
    // Bloco 2 — Gráfico do ativo selecionado (preço x preço médio)
    // =====================================================================

    private void renderChartCard() {
        Position selected = cachedPositions.stream()
                .filter(p -> selectedAssetId != null && selectedAssetId.equals(p.asset().id()))
                .findFirst()
                .orElse(null);

        chartCardBody.getChildren().clear();

        if (selected == null) {
            Label title = new Label("Gráfico do ativo selecionado");
            title.getStyleClass().add("content-card-title");
            Label subtitle = new Label(
                    "Cadastre e selecione um ativo de Ações/FIIs na tabela abaixo para ver a evolução do preço.");
            subtitle.getStyleClass().add("content-card-subtitle");
            subtitle.setWrapText(true);
            chartCardBody.getChildren().addAll(title, subtitle);
            return;
        }

        AssetDTO asset = selected.asset();
        double unitPrice = selected.currentQuantity() > 0 ? selected.currentValue() / selected.currentQuantity() : 0;
        Double changePct = cachedDailyChanges.get(asset.id());

        Label icon = new Label(tickerAbbreviation(asset));
        icon.setStyle("-fx-text-fill: -fx-color-accent; -fx-font-family: 'IBM Plex Mono SemiBold'; -fx-font-size: 13px;");
        StackPane iconBox = new StackPane(icon);
        iconBox.setPrefSize(46, 46);
        iconBox.setMaxSize(46, 46);
        iconBox.setMinSize(46, 46);
        iconBox.setStyle("-fx-background-color: -fx-color-text-primary; -fx-background-radius: 12;");

        Label tickerLabel = new Label(assetLabel(asset));
        tickerLabel.setId("chartTickerLabel");
        tickerLabel.setStyle("-fx-font-family: 'Manrope ExtraBold'; -fx-font-size: 19px; -fx-text-fill: -fx-color-text-primary;");
        String subtitleText = (asset.displayName() != null && !asset.displayName().isBlank() ? asset.displayName() + " · " : "")
                + assetTypeShortLabel(asset.type());
        Label typeLabel = new Label(subtitleText);
        typeLabel.setStyle("-fx-font-family: 'Manrope Medium'; -fx-font-size: 13px; -fx-text-fill: -fx-color-text-muted;");
        HBox nameRow = new HBox(9, tickerLabel, typeLabel);
        nameRow.setAlignment(Pos.BASELINE_LEFT);

        Label priceLabel = new Label(formatCurrency(unitPrice));
        priceLabel.setStyle("-fx-font-family: 'IBM Plex Mono SemiBold'; -fx-font-size: 24px; -fx-text-fill: -fx-color-text-primary;");
        Label changeLabel = new Label(changePct != null ? formatSignedPercent(changePct) : "--");
        changeLabel.setStyle("-fx-font-family: 'IBM Plex Mono SemiBold'; -fx-font-size: 13px; -fx-text-fill: "
                + (changePct == null ? "-fx-color-text-muted;" : (changePct >= 0 ? "-fx-color-gain;" : "-fx-color-loss;")));
        Label avgLabel = new Label("preço médio " + formatCurrency(selected.averagePrice()));
        avgLabel.setStyle("-fx-font-family: 'Manrope Medium'; -fx-font-size: 12px; -fx-text-fill: -fx-color-text-faint;");
        HBox priceRow = new HBox(10, priceLabel, changeLabel, avgLabel);
        priceRow.setAlignment(Pos.BASELINE_LEFT);

        VBox infoBox = new VBox(5, nameRow, priceRow);
        HBox leftHeader = new HBox(16, iconBox, infoBox);
        leftHeader.setAlignment(Pos.CENTER_LEFT);

        Label periodTab = new Label("Tudo");
        periodTab.getStyleClass().add("tab-pill-active");
        HBox tabsBox = new HBox(5, periodTab);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox headerRow = new HBox(leftHeader, spacer, tabsBox);
        headerRow.setAlignment(Pos.TOP_LEFT);

        chartCardBody.getChildren().add(headerRow);

        List<QuoteHistory> history = quoteHistoryRepository.listByAsset(asset.id());
        if (history.size() < 2) {
            Label placeholder = new Label(
                    "Ainda não há histórico de preço suficiente para desenhar o gráfico — o histórico é importado "
                            + "no cadastro e complementado pelas consultas periódicas.");
            placeholder.getStyleClass().add("content-card-subtitle");
            placeholder.setWrapText(true);
            chartCardBody.getChildren().add(placeholder);
            return;
        }

        Canvas canvas = new Canvas(640, 220);
        canvas.setId("assetChartCanvas");
        drawAssetChart(canvas, history, selected.averagePrice());
        chartCardBody.getChildren().addAll(canvas, buildChartLegend());
    }

    /**
     * Mesma técnica de {@code Canvas}/{@code GraphicsContext} já usada no
     * Dashboard (ATV-12) — grade, área sob a linha, linha secundária
     * (tracejada, aqui o preço médio de compra em vez do CDI), linha
     * principal, ponto final, labels de eixo.
     */
    private void drawAssetChart(Canvas canvas, List<QuoteHistory> history, double averagePrice) {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        double width = canvas.getWidth();
        double height = canvas.getHeight();
        gc.clearRect(0, 0, width, height);

        double leftPad = 46;
        double rightPad = 14;
        double topPad = 16;
        double bottomPad = 34;
        double plotWidth = width - leftPad - rightPad;
        double plotHeight = height - topPad - bottomPad;

        int n = history.size();
        double minValue = Double.MAX_VALUE;
        double maxValue = -Double.MAX_VALUE;
        for (QuoteHistory q : history) {
            minValue = Math.min(minValue, q.getPrice());
            maxValue = Math.max(maxValue, q.getPrice());
        }
        minValue = Math.min(minValue, averagePrice);
        maxValue = Math.max(maxValue, averagePrice);
        if (maxValue <= minValue) {
            maxValue = minValue + 1;
        }
        double padding = (maxValue - minValue) * 0.15;
        double rangeMin = Math.max(0, minValue - padding);
        double rangeMax = maxValue + padding;

        double[] xs = new double[n];
        double[] ys = new double[n];
        for (int i = 0; i < n; i++) {
            xs[i] = leftPad + (n == 1 ? 0 : plotWidth * i / (double) (n - 1));
            ys[i] = topPad + plotHeight * (1 - (history.get(i).getPrice() - rangeMin) / (rangeMax - rangeMin));
        }
        double avgY = topPad + plotHeight * (1 - (averagePrice - rangeMin) / (rangeMax - rangeMin));

        // 1. grade horizontal + 2. labels eixo Y
        gc.setStroke(theme().chartGrid());
        gc.setLineWidth(1);
        gc.setFont(Font.font("IBM Plex Mono", 10));
        gc.setFill(theme().chartAxisText());
        int gridLines = 5;
        for (int i = 0; i < gridLines; i++) {
            double y = topPad + plotHeight * i / (double) (gridLines - 1);
            gc.strokeLine(leftPad, y, width - rightPad, y);
            double value = rangeMax - (rangeMax - rangeMin) * i / (double) (gridLines - 1);
            gc.fillText(formatMoney(value, 0), 0, y + 4);
        }

        // 3. area sob a linha principal
        double[] areaX = new double[n + 2];
        double[] areaY = new double[n + 2];
        System.arraycopy(xs, 0, areaX, 0, n);
        System.arraycopy(ys, 0, areaY, 0, n);
        areaX[n] = xs[n - 1];
        areaY[n] = topPad + plotHeight;
        areaX[n + 1] = xs[0];
        areaY[n + 1] = topPad + plotHeight;
        gc.setFill(theme().chartArea());
        gc.fillPolygon(areaX, areaY, n + 2);

        // 5. linha secundaria (preco medio de compra, tracejada)
        gc.setStroke(theme().neutralWarn());
        gc.setLineWidth(2);
        gc.setLineDashes(6, 5);
        gc.strokeLine(leftPad, avgY, width - rightPad, avgY);
        gc.setLineDashes((double[]) null);
        gc.setFill(theme().neutralWarnDark());
        gc.fillText("PM " + formatMoney(averagePrice, 2), width - rightPad - 60, avgY - 6);

        // 4. linha principal
        gc.setStroke(theme().chartLinePrimary());
        gc.setLineWidth(2.5);
        gc.strokePolyline(xs, ys, n);

        // 6. ponto final destacado
        gc.setFill(theme().chartLinePrimary());
        gc.fillOval(xs[n - 1] - 4.5, ys[n - 1] - 4.5, 9, 9);

        // 7. labels eixo X (inicio, ~1/4, meio, ~3/4, fim)
        gc.setFill(theme().chartAxisText());
        int[] labelIdx = n <= 5 ? intRange(n) : new int[]{0, n / 4, n / 2, (3 * n) / 4, n - 1};
        for (int idx : labelIdx) {
            LocalDate d = history.get(idx).getDate();
            gc.fillText(MONTH_ABBR[d.getMonthValue() - 1], xs[idx] - 8, height - 6);
        }
    }

    private HBox buildChartLegend() {
        HBox item1 = legendItem("Preço de mercado (brapi.dev)", theme().chartLinePrimary());
        HBox item2 = legendItem("Preço médio de compra", theme().neutralWarn());
        Label caption = new Label("Histórico importado no cadastro e complementado pelas consultas periódicas.");
        caption.setStyle("-fx-font-family: 'Manrope Medium'; -fx-font-size: 12px; -fx-text-fill: -fx-color-text-faint;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox box = new HBox(18, item1, item2, spacer, caption);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private HBox legendItem(String text, Color color) {
        Region swatch = new Region();
        swatch.setPrefSize(14, 3);
        swatch.setMaxSize(14, 3);
        swatch.setStyle("-fx-background-radius: 2; -fx-background-color: " + toHex(color) + ";");
        Label label = new Label(text);
        label.setStyle("-fx-font-family: 'Manrope SemiBold'; -fx-font-size: 12px; -fx-text-fill: -fx-color-text-secondary;");
        HBox box = new HBox(7, swatch, label);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    // =====================================================================
    // Bloco 3 — Tabela de posições
    // =====================================================================

    private VBox buildPositionsCardWrapper() {
        Label title = new Label("Posições");
        title.getStyleClass().add("content-card-title");
        Label hint = new Label("clique em uma linha para ver o gráfico do ativo");
        hint.setStyle("-fx-font-family: 'Manrope Medium'; -fx-font-size: 12px; -fx-text-fill: -fx-color-text-muted;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox headerRow = new HBox(title, spacer, hint);
        headerRow.setAlignment(Pos.CENTER_LEFT);

        positionsCardBody.setId("positionsCardBody");
        VBox card = new VBox(14, headerRow, positionsCardBody);
        card.getStyleClass().add("content-card");
        return card;
    }

    private void renderPositionsTable() {
        positionsCardBody.getChildren().clear();
        List<Position> filtered = cachedPositions.stream().filter(this::matchesFilter).toList();

        if (filtered.isEmpty()) {
            Label empty = new Label(cachedPositions.isEmpty()
                    ? "Nenhum ativo de Ações/FIIs cadastrado ainda — cadastre em \"Cadastro e transações\"."
                    : "Nenhum ativo nesta categoria.");
            empty.getStyleClass().add("content-card-subtitle");
            empty.setWrapText(true);
            positionsCardBody.getChildren().add(empty);
            return;
        }

        GridPane grid = new GridPane();
        grid.setId("positionsGrid");
        grid.setHgap(14);
        grid.getColumnConstraints().addAll(
                pctColumn(20, HPos.LEFT), pctColumn(10, HPos.LEFT), pctColumn(10, HPos.RIGHT),
                pctColumn(12, HPos.RIGHT), pctColumn(12, HPos.RIGHT), pctColumn(11, HPos.RIGHT),
                pctColumn(13, HPos.RIGHT), pctColumn(12, HPos.RIGHT));

        grid.add(tableHeaderCell("TICKER", HPos.LEFT), 0, 0);
        grid.add(tableHeaderCell("TIPO", HPos.LEFT), 1, 0);
        grid.add(tableHeaderCell("QTD.", HPos.RIGHT), 2, 0);
        grid.add(tableHeaderCell("PREÇO MÉDIO", HPos.RIGHT), 3, 0);
        grid.add(tableHeaderCell("PREÇO ATUAL", HPos.RIGHT), 4, 0);
        grid.add(tableHeaderCell("GANHO %", HPos.RIGHT), 5, 0);
        grid.add(tableHeaderCell("GANHO " + CurrencyDisplay.symbol(), HPos.RIGHT), 6, 0);
        grid.add(tableHeaderCell("VALOR ATUAL", HPos.RIGHT), 7, 0);

        for (int i = 0; i < filtered.size(); i++) {
            Position p = filtered.get(i);
            int row = i + 1;
            boolean last = i == filtered.size() - 1;
            boolean selected = selectedAssetId != null && selectedAssetId.equals(p.asset().id());

            double unitPrice = p.currentQuantity() > 0 ? p.currentValue() / p.currentQuantity() : 0;

            Node tickerNode = tickerCell(p, last, selected);
            Label typeNode = tableCell(assetTypeShortLabel(p.asset().type()), "table-row-secondary", HPos.LEFT, last, selected);
            Label qtyNode = tableCell(formatQuantity(p.currentQuantity()), "table-cell-numeric", HPos.RIGHT, last, selected);
            Label pmNode = tableCell(formatMoney(p.averagePrice(), 2), "table-cell-numeric", HPos.RIGHT, last, selected);
            Label paNode = tableCell(formatMoney(unitPrice, 2), "table-cell-numeric", HPos.RIGHT, last, selected);
            Label pctNode = tableCell(formatSignedPercent(p.gainLossPercent()),
                    p.gainLossPercent() >= 0 ? "table-cell-gain" : "table-cell-loss", HPos.RIGHT, last, selected);
            Label amtNode = tableCell(formatSignedNumber(p.gainLossAmount(), 2),
                    p.gainLossAmount() >= 0 ? "table-cell-gain" : "table-cell-loss", HPos.RIGHT, last, selected);
            Label valNode = tableCell(formatMoney(p.currentValue(), 2), "table-cell-numeric", HPos.RIGHT, last, selected);

            long assetId = p.asset().id();
            for (Node n : List.of(tickerNode, typeNode, qtyNode, pmNode, paNode, pctNode, amtNode, valNode)) {
                n.setCursor(Cursor.HAND);
                n.setOnMouseClicked(e -> selectAsset(assetId));
            }

            grid.add(tickerNode, 0, row);
            grid.add(typeNode, 1, row);
            grid.add(qtyNode, 2, row);
            grid.add(pmNode, 3, row);
            grid.add(paNode, 4, row);
            grid.add(pctNode, 5, row);
            grid.add(amtNode, 6, row);
            grid.add(valNode, 7, row);
        }

        positionsCardBody.getChildren().add(grid);
    }

    private VBox tickerCell(Position p, boolean last, boolean selected) {
        Label ticker = new Label(assetLabel(p.asset()));
        ticker.getStyleClass().add("table-row-primary");
        String displayName = p.asset().displayName();
        Label sub = new Label(displayName != null ? displayName : "");
        sub.getStyleClass().add("table-row-secondary");
        VBox box = new VBox(3, ticker, sub);
        box.setAlignment(Pos.CENTER_LEFT);
        styleRowCell(box, last, selected);
        return box;
    }

    private Label tableCell(String text, String styleClass, HPos align, boolean last, boolean selected) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        label.setMaxWidth(Double.MAX_VALUE);
        GridPane.setHalignment(label, align);
        styleRowCell(label, last, selected);
        return label;
    }

    private Label tableHeaderCell(String text, HPos align) {
        Label label = new Label(text);
        label.getStyleClass().add("table-header-cell");
        GridPane.setHalignment(label, align);
        return label;
    }

    private void styleRowCell(Region node, boolean last, boolean selected) {
        node.setPadding(new Insets(13, 0, 13, 0));
        if (!last) {
            node.getStyleClass().add("table-row-divider");
        }
        if (selected) {
            String existing = node.getStyle() == null ? "" : node.getStyle();
            node.setStyle(existing + " -fx-background-color: -fx-color-bg-surface-tint;");
        }
    }

    // =====================================================================
    // Bloco 4 — Registrar provento (form) + Proventos recebidos (tabela)
    // =====================================================================

    private VBox buildDistributionFormCard() {
        Label title = new Label("Registrar provento");
        title.setStyle("-fx-font-family: 'Manrope'; -fx-font-weight: bold; -fx-font-size: 15px; -fx-text-fill: white;");
        Label subtitle = new Label("Dividendos e JCP não estão disponíveis na API no plano gratuito — lançamento manual.");
        subtitle.getStyleClass().add("card-dark-label");
        subtitle.setWrapText(true);
        VBox titleBox = new VBox(4, title, subtitle);

        distAssetCombo.setId("distAssetCombo");
        distAssetCombo.getStyleClass().add("text-field-dark");
        distAssetCombo.setMaxWidth(Double.MAX_VALUE);
        distAssetCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(Position p) {
                return p == null ? "" : assetLabel(p.asset());
            }

            @Override
            public Position fromString(String s) {
                return null;
            }
        });

        distTypeCombo.setId("distTypeCombo");
        distTypeCombo.getStyleClass().add("text-field-dark");
        distTypeCombo.setMaxWidth(Double.MAX_VALUE);
        distTypeCombo.setItems(FXCollections.observableArrayList(DistributionType.values()));
        distTypeCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(DistributionType t) {
                return t == null ? "" : distributionTypeLabel(t);
            }

            @Override
            public DistributionType fromString(String s) {
                return null;
            }
        });

        distDatePicker.setId("distDatePicker");
        distDatePicker.getStyleClass().add("text-field-dark");
        distDatePicker.setMaxWidth(Double.MAX_VALUE);

        distValueField.setId("distValueField");
        distValueField.getStyleClass().add("text-field-dark");
        distValueField.setPromptText("0,00");

        distNotesField.setId("distNotesField");
        distNotesField.getStyleClass().add("text-field-dark");
        distNotesField.setPromptText("Ex.: recebido via corretora (opcional)");

        GridPane fieldsGrid = twoColGrid();
        fieldsGrid.setVgap(12);
        fieldsGrid.add(fieldGroupDark("ATIVO", distAssetCombo), 0, 0);
        fieldsGrid.add(fieldGroupDark("TIPO", distTypeCombo), 1, 0);
        fieldsGrid.add(fieldGroupDark("DATA DE PAGAMENTO", distDatePicker), 0, 1);
        fieldsGrid.add(fieldGroupDark("VALOR TOTAL", distValueField), 1, 1);

        VBox notesGroup = fieldGroupDark("OBSERVAÇÃO (OPCIONAL)", distNotesField);

        Button clearButton = new Button("Limpar");
        clearButton.setStyle("-fx-background-color: transparent; -fx-border-color: -fx-color-field-dark-border;"
                + " -fx-border-radius: 10; -fx-padding: 10 15; -fx-font-family: 'Manrope SemiBold'; -fx-font-size: 13px;"
                + " -fx-text-fill: -fx-color-sidebar-text; -fx-cursor: hand;");
        clearButton.setOnAction(e -> clearDistributionForm());

        saveDistributionButton.setId("saveDistributionButton");
        saveDistributionButton.setStyle("-fx-background-color: -fx-color-accent; -fx-background-radius: 10;"
                + " -fx-padding: 11 18; -fx-font-family: 'Manrope'; -fx-font-weight: bold; -fx-font-size: 13px;"
                + " -fx-text-fill: -fx-color-on-accent; -fx-cursor: hand;");
        saveDistributionButton.setOnAction(e -> handleSaveDistribution());

        HBox actions = new HBox(8, clearButton, saveDistributionButton);
        actions.setAlignment(Pos.CENTER_RIGHT);

        VBox card = new VBox(16, titleBox, fieldsGrid, notesGroup, distFormErrorLabel, actions);
        card.getStyleClass().add("card-dark");
        return card;
    }

    private void handleSaveDistribution() {
        distFormErrorLabel.setText("");

        Position asset = distAssetCombo.getValue();
        DistributionType type = distTypeCombo.getValue();
        LocalDate date = distDatePicker.getValue();
        Double value = parseDoubleOrNull(distValueField.getText());

        if (asset == null || type == null) {
            distFormErrorLabel.setText("Selecione o ativo e o tipo do provento.");
            return;
        }
        if (date == null || date.isAfter(LocalDate.now())) {
            distFormErrorLabel.setText("Informe uma data de pagamento válida (não pode ser futura).");
            return;
        }
        if (value == null || value <= 0) {
            distFormErrorLabel.setText("Informe um valor total maior que zero.");
            return;
        }

        String notes = distNotesField.getText();
        Distribution distribution = Distribution.builder()
                .assetId(asset.asset().id())
                .type(type)
                .paymentDate(date)
                .value(value)
                .notes(notes == null || notes.isBlank() ? null : notes.trim())
                .build();
        distributionRepository.insert(distribution);

        clearDistributionForm();
        renderKpiRow();
        renderDistributionsTable();
    }

    private void clearDistributionForm() {
        distAssetCombo.getSelectionModel().clearSelection();
        distTypeCombo.getSelectionModel().clearSelection();
        distDatePicker.setValue(LocalDate.now());
        distValueField.clear();
        distNotesField.clear();
        distFormErrorLabel.setText("");
    }

    private VBox buildDistributionsTableCardWrapper() {
        Label title = new Label("Proventos recebidos");
        title.getStyleClass().add("content-card-title");
        Label yearLabel = new Label("Ano " + LocalDate.now().getYear());
        yearLabel.setStyle("-fx-font-family: 'Manrope SemiBold'; -fx-font-size: 12px; -fx-text-fill: -fx-color-accent-strong;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox headerRow = new HBox(title, spacer, yearLabel);
        headerRow.setAlignment(Pos.CENTER_LEFT);

        distributionsTableBody.setId("distributionsTableBody");
        VBox card = new VBox(14, headerRow, distributionsTableBody);
        card.getStyleClass().add("content-card");
        return card;
    }

    private void renderDistributionsTable() {
        distributionsTableBody.getChildren().clear();
        List<DistributionRow> rows = loadDistributions();

        if (rows.isEmpty()) {
            Label empty = new Label("Nenhum provento registrado ainda — use o formulário ao lado.");
            empty.getStyleClass().add("content-card-subtitle");
            empty.setWrapText(true);
            distributionsTableBody.getChildren().add(empty);
            return;
        }

        GridPane grid = new GridPane();
        grid.setId("distributionsGrid");
        grid.setHgap(14);
        grid.getColumnConstraints().addAll(
                pctColumn(24, HPos.LEFT), pctColumn(18, HPos.LEFT), pctColumn(18, HPos.LEFT),
                pctColumn(20, HPos.RIGHT), pctColumn(20, HPos.RIGHT));

        grid.add(tableHeaderCell("ATIVO", HPos.LEFT), 0, 0);
        grid.add(tableHeaderCell("TIPO", HPos.LEFT), 1, 0);
        grid.add(tableHeaderCell("PAGAMENTO", HPos.LEFT), 2, 0);
        grid.add(tableHeaderCell("VALOR", HPos.RIGHT), 3, 0);
        grid.add(tableHeaderCell("AÇÕES", HPos.RIGHT), 4, 0);

        for (int i = 0; i < rows.size(); i++) {
            DistributionRow r = rows.get(i);
            int row = i + 1;
            boolean last = i == rows.size() - 1;

            grid.add(tableCell(assetLabel(r.asset()), "table-row-primary", HPos.LEFT, last, false), 0, row);
            grid.add(tableCell(distributionTypeLabel(r.distribution().getType()), "table-row-secondary", HPos.LEFT, last, false), 1, row);
            grid.add(tableCell(formatDate(r.distribution().getPaymentDate()), "table-row-secondary", HPos.LEFT, last, false), 2, row);
            grid.add(tableCell(formatMoney(r.distribution().getValue(), 2), "table-cell-gain", HPos.RIGHT, last, false), 3, row);
            grid.add(deleteActionCell(r, last), 4, row);
        }

        distributionsTableBody.getChildren().add(grid);

        int year = LocalDate.now().getYear();
        double totalYear = rows.stream()
                .filter(r -> r.distribution().getPaymentDate().getYear() == year)
                .mapToDouble(r -> r.distribution().getValue())
                .sum();
        int monthsElapsed = Math.max(1, LocalDate.now().getMonthValue());
        double avgMonthly = totalYear / monthsElapsed;
        double investedBase = cachedPositions.stream().mapToDouble(Position::investedValue).sum();
        double yieldOnCost = investedBase > 0 ? totalYear / investedBase * 100 : 0;

        distributionsTableBody.getChildren().add(buildDistributionsFooter(totalYear, avgMonthly, yieldOnCost));
    }

    private HBox deleteActionCell(DistributionRow r, boolean last) {
        Label delete = new Label("Excluir");
        delete.setStyle("-fx-font-family: 'Manrope'; -fx-font-weight: bold; -fx-font-size: 12px; -fx-text-fill: -fx-color-loss; -fx-cursor: hand;");
        delete.setOnMouseClicked(e -> deleteDistribution(r));
        HBox box = new HBox(delete);
        box.setAlignment(Pos.CENTER_RIGHT);
        box.setPadding(new Insets(12, 0, 12, 0));
        if (!last) {
            box.getStyleClass().add("table-row-divider");
        }
        return box;
    }

    private void deleteDistribution(DistributionRow r) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                "Excluir este provento? Esta ação não pode ser desfeita.", ButtonType.YES, ButtonType.NO);
        alert.setHeaderText("Excluir provento");
        Optional<ButtonType> result = alert.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.YES) {
            deleteDistributionById(r.distribution().getId());
        }
    }

    private void deleteDistributionById(long id) {
        distributionRepository.delete(id);
        renderKpiRow();
        renderDistributionsTable();
    }

    /**
     * Seam de teste (mesmo pacote) — pula deliberadamente o {@link Alert} de
     * confirmação (mesma decisão já documentada na ATV-13 para o dialog de
     * exclusão de transação: automatizar clique num dialog modal é
     * exatamente o tipo de interação visual que esta atividade foi
     * instruída a pular).
     */
    void confirmDeleteDistributionForTest(Distribution distribution) {
        deleteDistributionById(distribution.getId());
    }

    private HBox buildDistributionsFooter(double totalYear, double avgMonthly, double yieldOnCost) {
        HBox row = new HBox();
        row.setId("distributionsFooter");
        row.setAlignment(Pos.CENTER_LEFT);
        row.setStyle("-fx-border-color: transparent transparent -fx-color-border-row transparent;"
                + " -fx-border-width: 1 0 0 0; -fx-padding: 13 0 0 0;");
        VBox col1 = footerStat("Recebido em " + LocalDate.now().getYear(), formatCurrency(totalYear));
        VBox col2 = footerStat("Média mensal", formatCurrency(avgMonthly));
        VBox col3 = footerStat("Yield sobre custo", formatDecimal(yieldOnCost, 1) + "% a.a.");
        Region s1 = new Region();
        HBox.setHgrow(s1, Priority.ALWAYS);
        Region s2 = new Region();
        HBox.setHgrow(s2, Priority.ALWAYS);
        row.getChildren().addAll(col1, s1, col2, s2, col3);
        return row;
    }

    private VBox footerStat(String label, String value) {
        Label labelNode = new Label(label);
        labelNode.setStyle("-fx-font-family: 'Manrope Medium'; -fx-font-size: 11px; -fx-text-fill: -fx-color-text-faint;");
        Label valueNode = new Label(value);
        valueNode.setStyle("-fx-font-family: 'IBM Plex Mono SemiBold'; -fx-font-size: 15px; -fx-text-fill: -fx-color-text-primary;");
        return new VBox(4, labelNode, valueNode);
    }

    /**
     * Junta os proventos de todos os ativos STOCKS/FIIS atualmente na
     * carteira (um {@code listByAsset} por ativo — {@link
     * DistributionRepository} não tem {@code listAll}, e a atividade não
     * pede essa mudança retroativa; o universo de ativos aqui já é pequeno o
     * bastante para isso ser barato). Ativo com posição zerada (removido de
     * {@code cachedPositions}) não aparece mais aqui — mesma limitação de
     * escopo da tabela de posições, documentada no CLAUDE.md.
     */
    private List<DistributionRow> loadDistributions() {
        List<DistributionRow> rows = new ArrayList<>();
        for (Position p : cachedPositions) {
            for (Distribution d : distributionRepository.listByAsset(p.asset().id())) {
                rows.add(new DistributionRow(p.asset(), d));
            }
        }
        rows.sort(Comparator.comparing((DistributionRow r) -> r.distribution().getPaymentDate()).reversed());
        return rows;
    }

    private record DistributionRow(AssetDTO asset, Distribution distribution) {
    }

    // =====================================================================
    // Helpers de layout
    // =====================================================================

    private VBox fieldGroupDark(String labelText, Node control) {
        Label label = new Label(labelText);
        label.getStyleClass().add("field-label-dark");
        if (control instanceof Region region) {
            region.setMaxWidth(Double.MAX_VALUE);
        }
        return new VBox(7, label, control);
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

    private ColumnConstraints pctColumn(double percent, HPos align) {
        ColumnConstraints c = new ColumnConstraints();
        c.setPercentWidth(percent);
        c.setHalignment(align);
        return c;
    }

    private GridPane evenColumnsGrid(int columns, double hgap) {
        GridPane grid = new GridPane();
        grid.setHgap(hgap);
        for (int i = 0; i < columns; i++) {
            ColumnConstraints c = new ColumnConstraints();
            c.setPercentWidth(100.0 / columns);
            c.setHgrow(Priority.ALWAYS);
            grid.getColumnConstraints().add(c);
        }
        return grid;
    }

    private static Label buildErrorLabel() {
        Label label = new Label();
        label.getStyleClass().add("form-error-label");
        label.setWrapText(true);
        return label;
    }

    private static int[] intRange(int n) {
        int[] result = new int[n];
        for (int i = 0; i < n; i++) {
            result[i] = i;
        }
        return result;
    }

    // =====================================================================
    // Filtro / rótulos
    // =====================================================================

    private enum PositionFilter {
        ALL("Todos"), STOCK("Ações"), FII("FIIs"), ETF_BDR("ETFs / BDRs");

        final String label;

        PositionFilter(String label) {
            this.label = label;
        }
    }

    private static String assetLabel(AssetDTO asset) {
        return asset.ticker() != null && !asset.ticker().isBlank() ? asset.ticker() : asset.displayName();
    }

    private static String assetTypeShortLabel(AssetType type) {
        return switch (type) {
            case STOCK -> "Ação";
            case FII -> "FII";
            case ETF -> "ETF";
            case BDR -> "BDR";
            default -> type.name();
        };
    }

    private static String distributionTypeLabel(DistributionType type) {
        return switch (type) {
            case DIVIDEND -> "Dividendo";
            case INTEREST_ON_EQUITY -> "JCP";
            case INCOME -> "Rendimento";
        };
    }

    private static String tickerAbbreviation(AssetDTO asset) {
        String base = asset.ticker() != null && !asset.ticker().isBlank() ? asset.ticker() : asset.displayName();
        if (base == null || base.isBlank()) {
            return "--";
        }
        String upper = base.trim().toUpperCase(PT_BR);
        return upper.length() >= 2 ? upper.substring(0, 2) : upper;
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

    private static String formatQuantity(double qty) {
        return formatDecimal(qty, qty == Math.floor(qty) ? 0 : 2);
    }

    /**
     * Valor monetario sem simbolo, ja convertido para a moeda principal
     * (Configuracoes > Preferencias). Todo valor que o app calcula e BRL — a
     * conversao acontece so aqui, na formatacao. Ver {@link CurrencyDisplay}.
     */
    private static String formatMoney(double brlValue, int fractionDigits) {
        return formatDecimal(CurrencyDisplay.convert(brlValue), fractionDigits);
    }

    private static String formatDecimal(double value, int fractionDigits) {
        NumberFormat nf = NumberFormat.getNumberInstance(PT_BR);
        nf.setMinimumFractionDigits(fractionDigits);
        nf.setMaximumFractionDigits(fractionDigits);
        return nf.format(value);
    }

    private static String formatCurrency(double value) {
        return CurrencyDisplay.symbol() + " " + formatMoney(value, 2);
    }

    private static String formatSignedCurrency(double value) {
        String sign = value < 0 ? "− " : "+ ";
        return sign + CurrencyDisplay.symbol() + " " + formatMoney(Math.abs(value), 2);
    }

    private static String formatSignedNumber(double value, int fractionDigits) {
        String sign = value < 0 ? "−" : "+";
        return sign + formatMoney(Math.abs(value), fractionDigits);
    }

    private static String formatSignedPercent(double value) {
        String sign = value < 0 ? "−" : "+";
        return sign + formatDecimal(Math.abs(value), 2) + "%";
    }

    private static String formatDate(LocalDate date) {
        return date == null ? "--" : "%02d/%02d/%04d".formatted(date.getDayOfMonth(), date.getMonthValue(), date.getYear());
    }

    private static String toHex(Color color) {
        return String.format("#%02x%02x%02x",
                (int) Math.round(color.getRed() * 255),
                (int) Math.round(color.getGreen() * 255),
                (int) Math.round(color.getBlue() * 255));
    }
}
