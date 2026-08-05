package com.investimento.app.ui.screens;

import com.investimento.app.api.hgbrasil.model.Currency;
import com.investimento.app.api.hgbrasil.model.DailyRate;
import com.investimento.app.api.hgbrasil.model.IndicatorPoint;
import com.investimento.app.api.hgbrasil.model.MacroSnapshot;
import com.investimento.app.api.hgbrasil.model.MarketIndex;
import com.investimento.app.data.model.Asset;
import com.investimento.app.data.model.Category;
import com.investimento.app.data.model.PortfolioSnapshot;
import com.investimento.app.dto.AssetDTO;
import com.investimento.app.dto.PortfolioSummary;
import com.investimento.app.dto.Position;
import com.investimento.app.repository.AssetRepository;
import com.investimento.app.repository.PortfolioSnapshotRepository;
import com.investimento.app.repository.RateHistoryRepository;
import com.investimento.app.service.MarketService;
import com.investimento.app.service.PositionService;
import com.investimento.app.ui.Screen;
import javafx.beans.binding.Bindings;
import javafx.concurrent.Task;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.Cursor;
import javafx.scene.Parent;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.ArcType;
import javafx.scene.text.Font;

import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Tela Dashboard inicial (ATV-12, RF04, planejamento 5.1) — indicadores
 * macro, resumo geral, evolução do patrimônio, diversificação, ganho/perda
 * por categoria, top 5 posições e maiores variações do dia. Substitui o
 * stub da ATV-07.
 *
 * <p>Nenhum dado é recalculado por frame/redraw — só em {@link #onShow()}
 * (entrada na tela) e depois que o {@link Task} de "Atualizar cotações
 * agora" termina (armadilha documentada na ATV-12).</p>
 */
public class DashboardView implements ScreenView {

    // Cores duplicadas de theme.css/paleta.md — necessario porque Canvas
    // desenha imperativamente e nao consegue ler variavel -fx-color-* do CSS.
    private static final Color COLOR_STOCKS = Color.web("#2f6f5e");
    private static final Color COLOR_FIIS = Color.web("#5cae92");
    private static final Color COLOR_FIXED_INCOME = Color.web("#14181a");
    private static final Color COLOR_CRYPTO = Color.web("#c9a227");
    private static final Color COLOR_FOREX = Color.web("#b3402f");
    private static final Color COLOR_CHART_GRID = Color.web("#eeebe5");
    private static final Color COLOR_CHART_AXIS_TEXT = Color.web("#a2a8ab");
    private static final Color COLOR_CHART_LINE_PRIMARY = Color.web("#2f6f5e");
    private static final Color COLOR_CHART_LINE_SECONDARY = Color.web("#c3bfb6");
    private static final Color COLOR_CHART_AREA = Color.web("#3d9c78", 0.1);

    private static final Locale PT_BR = new Locale("pt", "BR");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final String[] MONTH_ABBR =
            {"jan", "fev", "mar", "abr", "mai", "jun", "jul", "ago", "set", "out", "nov", "dez"};

    private final MarketService marketService;
    private final PositionService positionService;
    private final AssetRepository assetRepository;
    private final PortfolioSnapshotRepository portfolioSnapshotRepository;
    private final RateHistoryRepository rateHistoryRepository;
    private final Consumer<Screen> onNavigate;

    private final VBox root;
    private final VBox contentBody;
    private final Label subtitleLabel;

    public DashboardView(MarketService marketService,
                          PositionService positionService,
                          AssetRepository assetRepository,
                          PortfolioSnapshotRepository portfolioSnapshotRepository,
                          RateHistoryRepository rateHistoryRepository,
                          Consumer<Screen> onNavigate) {
        this.marketService = marketService;
        this.positionService = positionService;
        this.assetRepository = assetRepository;
        this.portfolioSnapshotRepository = portfolioSnapshotRepository;
        this.rateHistoryRepository = rateHistoryRepository;
        this.onNavigate = onNavigate;

        subtitleLabel = new Label("Cadastre ativos e clique em \"Atualizar cotações agora\".");
        subtitleLabel.getStyleClass().add("header-subtitle");

        HBox header = buildHeader();

        contentBody = new VBox(20);
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
    // Header + botao "Atualizar cotacoes agora"
    // =====================================================================

    private HBox buildHeader() {
        Label title = new Label("Dashboard");
        title.getStyleClass().add("header-title");
        VBox titleBox = new VBox(4, title, subtitleLabel);

        Label periodPill = new Label("Período: 12 meses");
        periodPill.getStyleClass().add("pill-secondary");

        Button updateButton = new Button("Atualizar cotações agora");
        updateButton.getStyleClass().add("button-primary");
        updateButton.setOnAction(e -> triggerUpdate(updateButton));

        HBox actions = new HBox(10, periodPill, updateButton);
        actions.setAlignment(Pos.CENTER_RIGHT);

        HBox header = new HBox(20, titleBox, actions);
        header.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(titleBox, Priority.ALWAYS);
        header.getStyleClass().add("header-bar");
        return header;
    }

    /**
     * Roda {@code MarketService.updateQuotes} em background (RT06) — o botão
     * fica desabilitado + "Atualizando..." apenas enquanto o {@link Task}
     * está rodando (bind a {@code runningProperty()}), a janela continua
     * respondendo a cliques/resize normalmente.
     */
    private void triggerUpdate(Button button) {
        List<Asset> assets = assetRepository.listAssets(false);
        Task<Void> task = marketService.updateQuotes(assets);

        button.disableProperty().bind(task.runningProperty());
        button.textProperty().bind(Bindings.when(task.runningProperty())
                .then("Atualizando...")
                .otherwise("Atualizar cotações agora"));

        task.setOnSucceeded(ev -> {
            button.disableProperty().unbind();
            button.textProperty().unbind();
            button.setDisable(false);
            button.setText("Atualizar cotações agora");
            refresh();
        });
        task.setOnFailed(ev -> {
            button.disableProperty().unbind();
            button.textProperty().unbind();
            button.setDisable(false);
            button.setText("Atualizar cotações agora");
        });

        Thread thread = new Thread(task, "market-update-dashboard");
        thread.setDaemon(true);
        thread.start();
    }

    // =====================================================================
    // Orquestracao de dados (RF04)
    // =====================================================================

    private void refresh() {
        List<Asset> assets = assetRepository.listAssets(false);
        PortfolioSummary summary = positionService.calculatePortfolioSummary();
        List<Position> positions = positionService.calculateAllPositions(false);

        ensureTodaySnapshot(summary);

        MacroSnapshot macroSnapshot = marketService.getMacroSnapshot();
        Map<String, List<IndicatorPoint>> indicators = marketService.getIndicators();
        Map<Long, Double> dailyChanges = marketService.getDailyChanges(assets);
        List<PortfolioSnapshot> snapshots = portfolioSnapshotRepository.listAll();

        subtitleLabel.setText("Última atualização de cotações às " + LocalTime.now().format(TIME_FMT) + " · hoje");

        contentBody.getChildren().setAll(
                buildKpiRow(macroSnapshot, indicators),
                buildSummaryAndChartRow(summary, snapshots),
                buildDiversificationAndGainLossRow(positions),
                buildTablesRow(positions, dailyChanges)
        );
    }

    /**
     * Grava (ou atualiza, se o app já foi aberto hoje) o snapshot do dia em
     * {@code portfolio_snapshot} — sem isso o gráfico de evolução do
     * patrimônio nunca ganha pontos novos (armadilha documentada na ATV-12).
     * {@code PortfolioSnapshotRepository} não tem {@code upsert} (nota da
     * ATV-02/CLAUDE.md) — checa {@code findByDate} antes para decidir entre
     * {@code insert}/{@code update} e evitar {@code UNIQUE constraint failed}.
     */
    private void ensureTodaySnapshot(PortfolioSummary summary) {
        LocalDate today = LocalDate.now();
        PortfolioSnapshot snapshot = PortfolioSnapshot.builder()
                .date(today)
                .totalValue(summary.totalCurrentValue())
                .investedValue(summary.totalInvestedValue())
                .fetchedAt(LocalDateTime.now())
                .build();
        if (portfolioSnapshotRepository.findByDate(today).isPresent()) {
            portfolioSnapshotRepository.update(snapshot);
        } else {
            portfolioSnapshotRepository.insert(snapshot);
        }
    }

    // =====================================================================
    // Bloco 1 — 6 KPI cards
    // =====================================================================

    private GridPane buildKpiRow(MacroSnapshot macroSnapshot, Map<String, List<IndicatorPoint>> indicators) {
        GridPane grid = evenColumnsGrid(6, 12);

        DailyRate rate = macroSnapshot != null ? macroSnapshot.todayRate() : null;
        List<VBox> cards = new ArrayList<>();
        cards.add(buildKpiCard("SELIC", rate != null ? formatDecimal(rate.selic(), 2) : "--",
                "% a.a.", "meta Copom", "kpi-footer-neutral"));
        cards.add(buildKpiCard("CDI", rate != null ? formatDecimal(rate.cdi(), 2) : "--",
                "% a.a.", "HG Brasil", "kpi-footer-neutral"));

        double ipca12m = ipca12mAccumulated(indicators);
        cards.add(buildKpiCard("IPCA 12M", formatDecimal(ipca12m, 2), "%", "indicadores", "kpi-footer-neutral"));

        cards.add(buildIndexCard("IBOVESPA", macroSnapshot != null ? macroSnapshot.indices().get("IBOVESPA") : null));
        cards.add(buildIndexCard("NASDAQ", macroSnapshot != null ? macroSnapshot.indices().get("NASDAQ") : null));
        cards.add(buildUsdCard(macroSnapshot));

        for (int i = 0; i < cards.size(); i++) {
            VBox card = cards.get(i);
            card.setMaxWidth(Double.MAX_VALUE);
            grid.add(card, i, 0);
        }
        return grid;
    }

    /**
     * IPCA acumulado dos últimos 12 meses a partir da série já em memória
     * (mesma origem de {@code MarketService.getIndicators()}) — mesma
     * aproximação (juros compostos mês a mês) já usada por
     * {@code PositionServiceImpl.ipca12mAccumulated} (ATV-10). Nota: a HG
     * Brasil também devolve {@code summary.last_12m} já calculado
     * (hgbrasil-api/SKILL.md secão 6) — não recalculado aqui porque o
     * cliente atual (ATV-03) não expõe esse campo; recalcular a partir da
     * série é a mesma aproximação já aceita em ATV-10, mantida por
     * consistência.
     */
    private double ipca12mAccumulated(Map<String, List<IndicatorPoint>> indicators) {
        List<IndicatorPoint> series = indicators.getOrDefault("IBGE:IPCA", List.of());
        if (series.isEmpty()) {
            return 0;
        }
        List<IndicatorPoint> last12 = series.size() > 12 ? series.subList(series.size() - 12, series.size()) : series;
        double factor = 1.0;
        for (IndicatorPoint point : last12) {
            factor *= (1 + point.value() / 100.0);
        }
        return (factor - 1) * 100;
    }

    private VBox buildIndexCard(String label, MarketIndex index) {
        if (index == null) {
            return buildKpiCard(label, "--", null, "sem dado", "kpi-footer-neutral");
        }
        String footerText = formatSignedPercent(index.changePercent());
        String footerClass = index.changePercent() >= 0 ? "kpi-footer-gain" : "kpi-footer-loss";
        return buildKpiCard(label, formatDecimal(index.points(), 0), null, footerText, footerClass);
    }

    private VBox buildUsdCard(MacroSnapshot macroSnapshot) {
        if (macroSnapshot == null) {
            return buildKpiCard("USD / BRL", "--", null, "sem dado", "kpi-footer-neutral");
        }
        Currency usd = macroSnapshot.currencies().get("USD");
        if (usd == null) {
            return buildKpiCard("USD / BRL", "--", null, "sem dado", "kpi-footer-neutral");
        }
        Currency eur = macroSnapshot.currencies().get("EUR");
        String footer = eur != null
                ? "EUR " + formatDecimal(eur.buy(), 2) + " · " + formatSignedPercent(usd.changePercent())
                : formatSignedPercent(usd.changePercent());
        return buildKpiCard("USD / BRL", formatDecimal(usd.buy(), 2), null, footer, "kpi-footer-neutral");
    }

    private VBox buildKpiCard(String label, String value, String suffix, String footerText, String footerStyleClass) {
        Label labelNode = new Label(label);
        labelNode.getStyleClass().add("kpi-label");

        Label valueNode = new Label(value);
        valueNode.getStyleClass().add("kpi-value");

        Label footer = new Label(footerText);
        footer.getStyleClass().add(footerStyleClass);

        VBox card = new VBox(9, labelNode);
        if (suffix != null && !suffix.isBlank()) {
            Label suffixNode = new Label(suffix);
            suffixNode.setStyle("-fx-font-size: 13px; -fx-text-fill: -fx-color-text-muted;");
            HBox valueBox = new HBox(2, valueNode, suffixNode);
            valueBox.setAlignment(Pos.BASELINE_LEFT);
            card.getChildren().addAll(valueBox, footer);
        } else {
            card.getChildren().addAll(valueNode, footer);
        }
        card.getStyleClass().add("kpi-card");
        return card;
    }

    // =====================================================================
    // Bloco 2 — Resumo geral (card escuro) + evolução do patrimônio
    // =====================================================================

    private GridPane buildSummaryAndChartRow(PortfolioSummary summary, List<PortfolioSnapshot> snapshots) {
        return twoColumnRow(buildSummaryCard(summary), buildPatrimonyChartCard(snapshots), 39, 61);
    }

    private VBox buildSummaryCard(PortfolioSummary summary) {
        Label eyebrow = new Label("RESUMO GERAL");
        eyebrow.getStyleClass().add("card-dark-eyebrow");

        Label valueCaption = new Label("Valor atual da carteira");
        valueCaption.getStyleClass().add("card-dark-label");
        Label heroValue = new Label(formatCurrency(summary.totalCurrentValue()));
        heroValue.getStyleClass().add("card-dark-hero");

        Label deltaValue = buildDarkValueLabel(formatSignedCurrency(summary.gainLossAmount()), summary.gainLossAmount() >= 0);
        Label deltaCaption = new Label("desde o início");
        deltaCaption.getStyleClass().add("card-dark-label");
        HBox deltaRow = new HBox(8, deltaValue, deltaCaption);
        deltaRow.setAlignment(Pos.CENTER_LEFT);

        VBox heroBox = new VBox(5, valueCaption, heroValue, deltaRow);

        Region divider = new Region();
        divider.getStyleClass().add("card-dark-divider");
        divider.setMaxWidth(Double.MAX_VALUE);

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(11);
        ColumnConstraints labelCol = new ColumnConstraints();
        labelCol.setHgrow(Priority.ALWAYS);
        ColumnConstraints valueCol = new ColumnConstraints();
        valueCol.setHalignment(HPos.RIGHT);
        grid.getColumnConstraints().addAll(labelCol, valueCol);

        double diffPp = summary.gainLossPercent() - summary.cdiReturnPct();
        addSummaryRow(grid, 0, "Total investido", buildDarkValueLabel(formatCurrency(summary.totalInvestedValue()), null));
        addSummaryRow(grid, 1, "Rentabilidade da carteira",
                buildDarkValueLabel(formatSignedPercent(summary.gainLossPercent()), summary.gainLossPercent() >= 0));
        addSummaryRow(grid, 2, "CDI do período", buildDarkValueLabel(formatSignedPercent(summary.cdiReturnPct()), null));
        addSummaryRow(grid, 3, "Diferença vs. CDI", buildDarkValueLabel(formatSignedPoints(diffPp), diffPp >= 0));

        VBox progressBox = buildCdiProgress(summary);

        VBox card = new VBox(18, eyebrow, heroBox, divider, grid, progressBox);
        card.getStyleClass().add("card-dark");
        return card;
    }

    /**
     * Barra decorativa "carteira rendendo X% do CDI" — proporção visual, não
     * uma regra de negócio nova (o cálculo de {@code gainLossPercent}/
     * {@code cdiReturnPct} já vem pronto do {@code PositionService}, ATV-10).
     */
    private VBox buildCdiProgress(PortfolioSummary summary) {
        double ratio = summary.cdiReturnPct() != 0 ? (summary.gainLossPercent() / summary.cdiReturnPct()) * 100 : 0;
        double barWidthFraction = clamp(ratio, 0, 200) / 200.0;

        Region track = new Region();
        track.getStyleClass().add("card-dark-progress-track");
        track.setMaxWidth(Double.MAX_VALUE);
        Region fill = new Region();
        fill.getStyleClass().add("card-dark-progress-fill");
        fill.setPrefHeight(8);
        fill.prefWidthProperty().bind(track.widthProperty().multiply(barWidthFraction));
        // StackPane, diferente de HBox, estica os filhos ao tamanho maximo
        // disponivel por padrao - sem isto a barra sempre aparenta 100%
        // independente do prefWidth calculado acima.
        fill.setMaxWidth(Region.USE_PREF_SIZE);
        StackPane progressStack = new StackPane(track, fill);
        progressStack.setAlignment(Pos.CENTER_LEFT);

        String legendText = summary.cdiReturnPct() != 0
                ? "Carteira rendendo " + formatDecimal(ratio, 0) + "% do CDI no período"
                : "Ainda sem histórico suficiente para comparar com o CDI";
        Label legend = new Label(legendText);
        legend.getStyleClass().add("card-dark-label");

        return new VBox(7, progressStack, legend);
    }

    private Label buildDarkValueLabel(String text, Boolean positive) {
        Label label = new Label(text);
        if (positive == null) {
            label.getStyleClass().add("card-dark-value");
        } else if (positive) {
            label.getStyleClass().add("card-dark-value-gain");
        } else {
            // Nao ha variante loss-on-dark no theme.css (armadilha documentada
            // em componentes.md) - usa danger-dark-text por cima do estilo base.
            label.getStyleClass().add("card-dark-value");
            label.setStyle("-fx-text-fill: -fx-color-danger-dark-text;");
        }
        return label;
    }

    private void addSummaryRow(GridPane grid, int rowIndex, String labelText, Label valueNode) {
        Label labelNode = new Label(labelText);
        labelNode.getStyleClass().add("card-dark-label");
        grid.add(labelNode, 0, rowIndex);
        grid.add(valueNode, 1, rowIndex);
    }

    private VBox buildPatrimonyChartCard(List<PortfolioSnapshot> snapshots) {
        Label title = new Label("Evolução do patrimônio");
        title.getStyleClass().add("content-card-title");
        Label subtitle = new Label("snapshots diários do banco local");
        subtitle.getStyleClass().add("content-card-subtitle");
        VBox titleBox = new VBox(4, title, subtitle);

        Label tab12m = new Label("12M");
        tab12m.getStyleClass().add("tab-pill-active");
        HBox tabs = new HBox(5, tab12m);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox headerRow = new HBox(titleBox, spacer, tabs);
        headerRow.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(14, headerRow);
        card.getStyleClass().add("content-card");

        if (snapshots.size() < 2) {
            Label placeholder = new Label(
                    "Ainda não há snapshots suficientes para o gráfico — um ponto novo é gravado a cada dia em que o app é aberto.");
            placeholder.getStyleClass().add("content-card-subtitle");
            placeholder.setWrapText(true);
            card.getChildren().add(placeholder);
            return card;
        }

        Canvas canvas = new Canvas(640, 220);
        drawPatrimonyChart(canvas, snapshots);
        card.getChildren().addAll(canvas, buildLineChartLegend());
        return card;
    }

    private HBox buildLineChartLegend() {
        return new HBox(18, legendItem("Patrimônio", COLOR_CHART_LINE_PRIMARY),
                legendItem("CDI acumulado", COLOR_CHART_LINE_SECONDARY));
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

    /**
     * Desenho imperativo (Canvas) do gráfico de linha, na mesma ordem
     * documentada em componentes.md ("Gráfico de linha"): grade, labels do
     * eixo Y, área sob a linha, linha secundária (CDI, tracejada), linha
     * principal, ponto final, labels do eixo X.
     *
     * <p>A linha "CDI acumulado" é uma aproximação (juros compostos a partir
     * da taxa CDI mais recente conhecida) — não há {@code rate_history}
     * diário para todo o período (só é gravado quando o app é aberto), mesma
     * aproximação já usada em {@code PositionServiceImpl.calculateCdiReturnSincePeriodStart}
     * (ATV-10).</p>
     */
    private void drawPatrimonyChart(Canvas canvas, List<PortfolioSnapshot> snapshots) {
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

        int n = snapshots.size();
        LocalDate firstDate = snapshots.get(0).getDate();
        double firstValue = snapshots.get(0).getTotalValue();
        double annualCdi = rateHistoryRepository.findMostRecent().map(r -> r.getCdi() / 100.0).orElse(0.0);

        double[] cdiValues = new double[n];
        double minValue = Double.MAX_VALUE;
        double maxValue = -Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            PortfolioSnapshot snap = snapshots.get(i);
            long elapsedDays = ChronoUnit.DAYS.between(firstDate, snap.getDate());
            double elapsedYears = Math.max(elapsedDays, 0) / 365.0;
            cdiValues[i] = firstValue * Math.pow(1 + annualCdi, elapsedYears);
            minValue = Math.min(minValue, Math.min(snap.getTotalValue(), cdiValues[i]));
            maxValue = Math.max(maxValue, Math.max(snap.getTotalValue(), cdiValues[i]));
        }
        if (maxValue <= minValue) {
            maxValue = minValue + 1;
        }
        double padding = (maxValue - minValue) * 0.15;
        double rangeMin = Math.max(0, minValue - padding);
        double rangeMax = maxValue + padding;

        double[] xs = new double[n];
        double[] ysMain = new double[n];
        double[] ysCdi = new double[n];
        for (int i = 0; i < n; i++) {
            xs[i] = leftPad + (n == 1 ? 0 : plotWidth * i / (double) (n - 1));
            ysMain[i] = topPad + plotHeight * (1 - (snapshots.get(i).getTotalValue() - rangeMin) / (rangeMax - rangeMin));
            ysCdi[i] = topPad + plotHeight * (1 - (cdiValues[i] - rangeMin) / (rangeMax - rangeMin));
        }

        // 1. grade horizontal + 2. labels eixo Y
        gc.setStroke(COLOR_CHART_GRID);
        gc.setLineWidth(1);
        gc.setFont(Font.font("IBM Plex Mono", 10));
        gc.setFill(COLOR_CHART_AXIS_TEXT);
        int gridLines = 5;
        for (int i = 0; i < gridLines; i++) {
            double y = topPad + plotHeight * i / (double) (gridLines - 1);
            gc.strokeLine(leftPad, y, width - rightPad, y);
            double value = rangeMax - (rangeMax - rangeMin) * i / (double) (gridLines - 1);
            gc.fillText(formatCompact(value), 0, y + 4);
        }

        // 3. area sob a linha principal
        double[] areaX = new double[n + 2];
        double[] areaY = new double[n + 2];
        System.arraycopy(xs, 0, areaX, 0, n);
        System.arraycopy(ysMain, 0, areaY, 0, n);
        areaX[n] = xs[n - 1];
        areaY[n] = topPad + plotHeight;
        areaX[n + 1] = xs[0];
        areaY[n + 1] = topPad + plotHeight;
        gc.setFill(COLOR_CHART_AREA);
        gc.fillPolygon(areaX, areaY, n + 2);

        // 5. linha secundaria (CDI acumulado, tracejada)
        gc.setStroke(COLOR_CHART_LINE_SECONDARY);
        gc.setLineWidth(2);
        gc.setLineDashes(5, 5);
        gc.strokePolyline(xs, ysCdi, n);
        gc.setLineDashes((double[]) null);

        // 4. linha principal
        gc.setStroke(COLOR_CHART_LINE_PRIMARY);
        gc.setLineWidth(2.5);
        gc.strokePolyline(xs, ysMain, n);

        // 6. ponto final destacado
        gc.setFill(COLOR_CHART_LINE_PRIMARY);
        gc.fillOval(xs[n - 1] - 4.5, ysMain[n - 1] - 4.5, 9, 9);

        // 7. labels eixo X (inicio, ~1/4, meio, ~3/4, fim)
        gc.setFill(COLOR_CHART_AXIS_TEXT);
        int[] labelIdx = n <= 5
                ? intRange(n)
                : new int[]{0, n / 4, n / 2, (3 * n) / 4, n - 1};
        for (int idx : labelIdx) {
            LocalDate d = snapshots.get(idx).getDate();
            gc.fillText(MONTH_ABBR[d.getMonthValue() - 1], xs[idx] - 8, height - 6);
        }
    }

    private static int[] intRange(int n) {
        int[] result = new int[n];
        for (int i = 0; i < n; i++) {
            result[i] = i;
        }
        return result;
    }

    // =====================================================================
    // Bloco 3 — Diversificação (donut) + Ganho/perda por categoria
    // =====================================================================

    private GridPane buildDiversificationAndGainLossRow(List<Position> positions) {
        return twoColumnRow(buildDiversificationCard(positions), buildGainLossCard(positions), 50, 50);
    }

    private VBox buildDiversificationCard(List<Position> positions) {
        Label title = new Label("Diversificação por categoria");
        title.getStyleClass().add("content-card-title");
        Label subtitle = new Label("% investido em cada tipo de ativo · clique para abrir");
        subtitle.getStyleClass().add("content-card-subtitle");
        VBox titleBox = new VBox(4, title, subtitle);

        VBox card = new VBox(16, titleBox);
        card.getStyleClass().add("content-card");

        Map<Category, Double> byCategory = new EnumMap<>(Category.class);
        for (Position p : positions) {
            byCategory.merge(p.asset().category(), p.currentValue(), Double::sum);
        }
        double total = byCategory.values().stream().mapToDouble(Double::doubleValue).sum();

        if (total <= 0) {
            Label empty = new Label("Cadastre ativos para ver a diversificação da carteira.");
            empty.getStyleClass().add("content-card-subtitle");
            card.getChildren().add(empty);
            return card;
        }

        Canvas donut = new Canvas(158, 158);
        drawDonut(donut, byCategory, total);

        Label countLabel = new Label(String.valueOf(positions.size()));
        countLabel.setStyle("-fx-font-family: 'Manrope ExtraBold'; -fx-font-size: 24px; -fx-text-fill: -fx-color-text-primary;");
        Label atLabel = new Label("ATIVOS");
        atLabel.setStyle("-fx-font-family: 'IBM Plex Mono SemiBold'; -fx-font-size: 9px; -fx-text-fill: -fx-color-text-faint;");
        VBox centerLabel = new VBox(2, atLabel, countLabel);
        centerLabel.setAlignment(Pos.CENTER);

        StackPane donutStack = new StackPane(donut, centerLabel);
        donutStack.setMaxSize(158, 158);
        donutStack.setMinSize(158, 158);

        VBox legend = new VBox(11);
        for (Map.Entry<Category, Double> entry : byCategory.entrySet()) {
            if (entry.getValue() <= 0) {
                continue;
            }
            double pct = entry.getValue() / total * 100;
            legend.getChildren().add(buildDiversificationLegendRow(entry.getKey(), pct, entry.getValue()));
        }
        HBox.setHgrow(legend, Priority.ALWAYS);

        HBox body = new HBox(26, donutStack, legend);
        body.setAlignment(Pos.CENTER_LEFT);
        card.getChildren().add(body);
        return card;
    }

    /**
     * Donut chart via {@code Canvas.strokeArc} (raio grande + lineWidth
     * largo) — {@code PieChart} nativo do JavaFX não é um donut, técnica
     * documentada em componentes.md ("Donut chart de diversificação"),
     * opção 2. Ordem fixa das categorias (Ações → FIIs → Renda fixa →
     * Cripto → Câmbio, ordem natural do enum {@link Category}) para a cor
     * de cada fatia nunca mudar de tela pra tela.
     */
    private void drawDonut(Canvas canvas, Map<Category, Double> byCategory, double total) {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        double size = canvas.getWidth();
        double lineWidth = 30;
        double margin = lineWidth / 2 + 1;
        gc.clearRect(0, 0, size, size);

        double startAngle = 90;
        for (Map.Entry<Category, Double> entry : byCategory.entrySet()) {
            if (entry.getValue() <= 0) {
                continue;
            }
            double sweep = -(entry.getValue() / total) * 360.0;
            gc.setStroke(categoryColor(entry.getKey()));
            gc.setLineWidth(lineWidth);
            gc.strokeArc(margin, margin, size - margin * 2, size - margin * 2, startAngle, sweep, ArcType.OPEN);
            startAngle += sweep;
        }
    }

    private HBox buildDiversificationLegendRow(Category category, double pct, double value) {
        Region swatch = new Region();
        swatch.setPrefSize(11, 11);
        swatch.setMaxSize(11, 11);
        swatch.setStyle("-fx-background-radius: 3; -fx-background-color: " + toHex(categoryColor(category)) + ";");

        Label name = new Label(categoryLabel(category));
        name.setStyle("-fx-font-family: 'Manrope SemiBold'; -fx-font-size: 13px; -fx-text-fill: -fx-color-text-secondary;");
        HBox.setHgrow(name, Priority.ALWAYS);

        Label pctLabel = new Label(formatDecimal(pct, 0) + "%");
        pctLabel.setStyle("-fx-font-family: 'Manrope'; -fx-font-weight: bold; -fx-font-size: 13px; -fx-text-fill: -fx-color-text-primary;");

        Label valueLabel = new Label(formatCompactCurrency(value));
        valueLabel.setStyle("-fx-font-family: 'IBM Plex Mono Medium'; -fx-font-size: 12px; -fx-text-fill: -fx-color-text-faint;");

        HBox row = new HBox(10, swatch, name, pctLabel, valueLabel);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setCursor(Cursor.HAND);
        row.setOnMouseClicked(e -> onNavigate.accept(categoryScreen(category)));
        return row;
    }

    private VBox buildGainLossCard(List<Position> positions) {
        Label title = new Label("Ganho / perda por categoria");
        title.getStyleClass().add("content-card-title");
        Label subtitle = new Label("resultado em R$ acumulado por categoria");
        subtitle.getStyleClass().add("content-card-subtitle");
        VBox titleBox = new VBox(4, title, subtitle);

        VBox card = new VBox(16, titleBox);
        card.getStyleClass().add("content-card");

        Map<Category, Double> byCategory = new EnumMap<>(Category.class);
        for (Position p : positions) {
            byCategory.merge(p.asset().category(), p.gainLossAmount(), Double::sum);
        }

        if (byCategory.isEmpty()) {
            Label empty = new Label("Cadastre ativos para ver o ganho/perda por categoria.");
            empty.getStyleClass().add("content-card-subtitle");
            card.getChildren().add(empty);
            return card;
        }

        double maxAbs = byCategory.values().stream().mapToDouble(Math::abs).max().orElse(1);
        if (maxAbs <= 0) {
            maxAbs = 1;
        }

        VBox rows = new VBox(14);
        double total = 0;
        for (Map.Entry<Category, Double> entry : byCategory.entrySet()) {
            rows.getChildren().add(buildGainLossRow(entry.getKey(), entry.getValue(), maxAbs));
            total += entry.getValue();
        }
        card.getChildren().add(rows);

        Region divider = new Region();
        divider.setStyle("-fx-background-color: -fx-color-border-row; -fx-pref-height: 1;");
        divider.setMaxWidth(Double.MAX_VALUE);
        card.getChildren().add(divider);

        HBox totalRow = new HBox();
        totalRow.setAlignment(Pos.CENTER_LEFT);
        Label totalLabel = new Label("Resultado consolidado");
        totalLabel.setStyle("-fx-font-family: 'Manrope SemiBold'; -fx-font-size: 13px; -fx-text-fill: -fx-color-text-secondary;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label totalValue = new Label(formatSignedCurrencyNoDecimals(total));
        totalValue.getStyleClass().add(total >= 0 ? "gainloss-value-gain" : "gainloss-value-loss");
        totalValue.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");
        totalRow.getChildren().addAll(totalLabel, spacer, totalValue);
        card.getChildren().add(totalRow);

        return card;
    }

    private GridPane buildGainLossRow(Category category, double value, double maxAbs) {
        GridPane grid = new GridPane();
        grid.setHgap(12);
        ColumnConstraints labelCol = new ColumnConstraints(92);
        ColumnConstraints barCol = new ColumnConstraints();
        barCol.setHgrow(Priority.ALWAYS);
        ColumnConstraints valueCol = new ColumnConstraints(96);
        valueCol.setHalignment(HPos.RIGHT);
        grid.getColumnConstraints().addAll(labelCol, barCol, valueCol);

        Label label = new Label(categoryLabel(category));
        label.setStyle("-fx-font-family: 'Manrope SemiBold'; -fx-font-size: 13px; -fx-text-fill: -fx-color-text-secondary;");

        Region track = new Region();
        track.getStyleClass().add("gainloss-bar-track");
        track.setMaxWidth(Double.MAX_VALUE);
        Region fill = new Region();
        fill.getStyleClass().add(value >= 0 ? "gainloss-bar-fill-gain" : "gainloss-bar-fill-loss");
        fill.setPrefHeight(20);
        double widthFraction = Math.min(1.0, Math.abs(value) / maxAbs);
        fill.prefWidthProperty().bind(track.widthProperty().multiply(widthFraction));
        // Mesmo ajuste de buildCdiProgress: StackPane estica filhos por
        // padrao, sem isto a barra sempre aparenta 100% de largura.
        fill.setMaxWidth(Region.USE_PREF_SIZE);
        StackPane barStack = new StackPane(track, fill);
        barStack.setAlignment(Pos.CENTER_LEFT);

        Label valueLabel = new Label(formatSignedNumber(value));
        valueLabel.getStyleClass().add(value >= 0 ? "gainloss-value-gain" : "gainloss-value-loss");

        grid.add(label, 0, 0);
        grid.add(barStack, 1, 0);
        grid.add(valueLabel, 2, 0);
        GridPane.setValignment(label, VPos.CENTER);
        GridPane.setValignment(barStack, VPos.CENTER);
        GridPane.setValignment(valueLabel, VPos.CENTER);
        return grid;
    }

    // =====================================================================
    // Bloco 4 — Top 5 maiores posições + Maiores variações do dia
    // =====================================================================

    private GridPane buildTablesRow(List<Position> positions, Map<Long, Double> dailyChanges) {
        return twoColumnRow(buildTopPositionsCard(positions), buildHighlightsCard(positions, dailyChanges), 50, 50);
    }

    private VBox buildTopPositionsCard(List<Position> positions) {
        Label title = new Label("Top 5 maiores posições");
        title.getStyleClass().add("content-card-title");
        Label seeAll = new Label("Ver todas");
        seeAll.setStyle("-fx-font-family: 'Manrope SemiBold'; -fx-font-size: 12px; -fx-text-fill: -fx-color-accent-strong; -fx-cursor: hand;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox headerRow = new HBox(title, spacer, seeAll);
        headerRow.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(14, headerRow);
        card.getStyleClass().add("content-card");

        List<Position> top5 = positions.stream()
                .sorted(Comparator.comparingDouble(Position::currentValue).reversed())
                .limit(5)
                .toList();

        if (top5.isEmpty()) {
            Label empty = new Label("Nenhuma posição ativa ainda.");
            empty.getStyleClass().add("content-card-subtitle");
            card.getChildren().add(empty);
            return card;
        }

        GridPane grid = new GridPane();
        grid.setHgap(12);
        ColumnConstraints c1 = new ColumnConstraints();
        c1.setPercentWidth(40);
        ColumnConstraints c2 = new ColumnConstraints();
        c2.setPercentWidth(20);
        ColumnConstraints c3 = new ColumnConstraints();
        c3.setPercentWidth(22);
        c3.setHalignment(HPos.RIGHT);
        ColumnConstraints c4 = new ColumnConstraints();
        c4.setPercentWidth(18);
        c4.setHalignment(HPos.RIGHT);
        grid.getColumnConstraints().addAll(c1, c2, c3, c4);

        grid.add(tableHeaderCell("ATIVO", HPos.LEFT), 0, 0);
        grid.add(tableHeaderCell("CATEG.", HPos.LEFT), 1, 0);
        grid.add(tableHeaderCell("VALOR ATUAL", HPos.RIGHT), 2, 0);
        grid.add(tableHeaderCell("RESULT.", HPos.RIGHT), 3, 0);

        for (int i = 0; i < top5.size(); i++) {
            Position position = top5.get(i);
            int row = i + 1;
            boolean last = i == top5.size() - 1;

            Label ticker = tableCellPrimary(assetLabel(position.asset()), last);
            Label categ = tableCellSecondary(categoryLabelShort(position.asset().category()), last);
            Label value = tableCellNumericNeutral(formatDecimal(position.currentValue(), 2), last);
            Label result = tableCellResult(position.gainLossPercent(), last);

            for (Label cell : List.of(ticker, categ, value, result)) {
                cell.setCursor(Cursor.HAND);
                cell.setMaxWidth(Double.MAX_VALUE);
                cell.setOnMouseClicked(e -> onNavigate.accept(categoryScreen(position.asset().category())));
            }

            grid.add(ticker, 0, row);
            grid.add(categ, 1, row);
            grid.add(value, 2, row);
            grid.add(result, 3, row);
        }

        card.getChildren().add(grid);
        return card;
    }

    private Label tableHeaderCell(String text, HPos align) {
        Label label = new Label(text);
        label.getStyleClass().add("table-header-cell");
        GridPane.setHalignment(label, align);
        return label;
    }

    private Label tableCellPrimary(String text, boolean last) {
        Label label = new Label(text);
        label.getStyleClass().add("table-row-primary");
        styleTableRowCell(label, last);
        return label;
    }

    private Label tableCellSecondary(String text, boolean last) {
        Label label = new Label(text);
        label.getStyleClass().add("table-row-secondary");
        styleTableRowCell(label, last);
        return label;
    }

    private Label tableCellNumericNeutral(String text, boolean last) {
        Label label = new Label(text);
        label.getStyleClass().add("table-cell-numeric");
        GridPane.setHalignment(label, HPos.RIGHT);
        styleTableRowCell(label, last);
        return label;
    }

    private Label tableCellResult(double percent, boolean last) {
        Label label = new Label(formatSignedPercent(percent));
        label.getStyleClass().add(percent >= 0 ? "table-cell-gain" : "table-cell-loss");
        GridPane.setHalignment(label, HPos.RIGHT);
        styleTableRowCell(label, last);
        return label;
    }

    private void styleTableRowCell(Label label, boolean last) {
        label.setPadding(new Insets(13, 0, 13, 0));
        if (!last) {
            label.getStyleClass().add("table-row-divider");
        }
    }

    private VBox buildHighlightsCard(List<Position> positions, Map<Long, Double> dailyChanges) {
        Label title = new Label("Maiores variações do dia");
        title.getStyleClass().add("content-card-title");
        Label subtitle = new Label("altas e baixas");
        subtitle.setStyle("-fx-font-family: 'Manrope SemiBold'; -fx-font-size: 12px; -fx-text-fill: -fx-color-text-muted;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox headerRow = new HBox(title, spacer, subtitle);
        headerRow.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(14, headerRow);
        card.getStyleClass().add("content-card");

        List<AssetChange> changed = new ArrayList<>();
        for (Position p : positions) {
            Double change = dailyChanges.get(p.asset().id());
            if (change != null) {
                changed.add(new AssetChange(p, change));
            }
        }

        if (changed.isEmpty()) {
            Label empty = new Label("Clique em \"Atualizar cotações agora\" para ver as variações do dia.");
            empty.getStyleClass().add("content-card-subtitle");
            empty.setWrapText(true);
            card.getChildren().add(empty);
            return card;
        }

        List<AssetChange> highlights = selectHighlights(changed);
        VBox rows = new VBox(9);
        for (AssetChange change : highlights) {
            rows.getChildren().add(buildHighlightRow(change.position(), change.changePct()));
        }
        card.getChildren().add(rows);
        return card;
    }

    private record AssetChange(Position position, double changePct) {
    }

    /** Até 5 linhas, priorizando até 3 altas e 2 baixas (mesma proporção do template), completando com o que houver. */
    private List<AssetChange> selectHighlights(List<AssetChange> changed) {
        List<AssetChange> gains = changed.stream()
                .filter(c -> c.changePct() >= 0)
                .sorted(Comparator.comparingDouble(AssetChange::changePct).reversed())
                .toList();
        List<AssetChange> losses = changed.stream()
                .filter(c -> c.changePct() < 0)
                .sorted(Comparator.comparingDouble(AssetChange::changePct))
                .toList();

        List<AssetChange> result = new ArrayList<>();
        int gainSlots = Math.min(3, gains.size());
        result.addAll(gains.subList(0, gainSlots));
        int lossSlots = Math.min(5 - result.size(), losses.size());
        result.addAll(losses.subList(0, lossSlots));
        int remaining = 5 - result.size();
        if (remaining > 0 && gains.size() > gainSlots) {
            result.addAll(gains.subList(gainSlots, Math.min(gains.size(), gainSlots + remaining)));
        }
        return result;
    }

    private HBox buildHighlightRow(Position position, double changePct) {
        boolean gain = changePct >= 0;
        HBox row = new HBox();
        row.getStyleClass().add(gain ? "highlight-row-gain" : "highlight-row-loss");
        row.setAlignment(Pos.CENTER_LEFT);
        row.setCursor(Cursor.HAND);
        row.setOnMouseClicked(e -> onNavigate.accept(categoryScreen(position.asset().category())));

        Label arrow = new Label(gain ? "↑" : "↓");
        arrow.setStyle("-fx-text-fill: white; -fx-font-family: 'IBM Plex Mono SemiBold'; -fx-font-size: 11px;");
        StackPane badge = new StackPane(arrow);
        badge.getStyleClass().add(gain ? "highlight-badge-gain" : "highlight-badge-loss");

        Label ticker = new Label(assetLabel(position.asset()));
        ticker.setStyle("-fx-font-family: 'Manrope'; -fx-font-weight: bold; -fx-font-size: 13px; -fx-text-fill: -fx-color-text-primary;");
        Label quantityLabel = new Label(categoryLabelShort(position.asset().category()) + " · " + formatQuantity(position));
        quantityLabel.setStyle("-fx-font-family: 'Manrope Medium'; -fx-font-size: 11px; -fx-text-fill: -fx-color-text-muted;");
        VBox nameBox = new VBox(3, ticker, quantityLabel);

        HBox left = new HBox(11, badge, nameBox);
        left.setAlignment(Pos.CENTER_LEFT);

        Label pctLabel = new Label(formatSignedPercent(changePct));
        pctLabel.setStyle("-fx-font-family: 'IBM Plex Mono SemiBold'; -fx-font-size: 13px; -fx-text-fill: "
                + (gain ? "-fx-color-gain;" : "-fx-color-loss;"));
        double deltaValue = position.currentValue() - position.currentValue() / (1 + changePct / 100.0);
        Label deltaLabel = new Label(formatSignedCurrencyNoDecimals(deltaValue));
        deltaLabel.setStyle("-fx-font-family: 'IBM Plex Mono Medium'; -fx-font-size: 11px; -fx-text-fill: -fx-color-text-muted;");
        VBox right = new VBox(3, pctLabel, deltaLabel);
        right.setAlignment(Pos.CENTER_RIGHT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        row.getChildren().addAll(left, spacer, right);
        return row;
    }

    // =====================================================================
    // Helpers de layout
    // =====================================================================

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

    // =====================================================================
    // Categoria: rotulo, cor, navegacao
    // =====================================================================

    private static String assetLabel(AssetDTO asset) {
        if (asset.category() == Category.FIXED_INCOME) {
            return asset.displayName() != null && !asset.displayName().isBlank() ? asset.displayName() : asset.ticker();
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

    private static String categoryLabelShort(Category category) {
        return switch (category) {
            case STOCKS -> "Ações";
            case FIIS -> "FIIs";
            case FIXED_INCOME -> "R. fixa";
            case CRYPTO -> "Cripto";
            case FOREX -> "Câmbio";
        };
    }

    private static Color categoryColor(Category category) {
        return switch (category) {
            case STOCKS -> COLOR_STOCKS;
            case FIIS -> COLOR_FIIS;
            case FIXED_INCOME -> COLOR_FIXED_INCOME;
            case CRYPTO -> COLOR_CRYPTO;
            case FOREX -> COLOR_FOREX;
        };
    }

    /** Ações/FIIs → tela Ações e FIIs; Renda fixa → tela Renda Fixa; Câmbio/Cripto → tela Câmbio e Cripto (ATV-12). */
    private static Screen categoryScreen(Category category) {
        return switch (category) {
            case STOCKS, FIIS -> Screen.STOCKS_FIIS;
            case FIXED_INCOME -> Screen.FIXED_INCOME;
            case CRYPTO, FOREX -> Screen.FOREX_CRYPTO;
        };
    }

    private static String formatQuantity(Position position) {
        double qty = position.currentQuantity();
        return switch (position.asset().category()) {
            case STOCKS -> formatDecimal(qty, 0) + " papéis";
            case FIIS -> formatDecimal(qty, 0) + " cotas";
            case CRYPTO -> formatDecimal(qty, qty == Math.floor(qty) ? 0 : 4) + " un.";
            case FOREX -> formatDecimal(qty, 2) + " un.";
            case FIXED_INCOME -> formatDecimal(qty, 0) + " un.";
        };
    }

    // =====================================================================
    // Formatacao (pt-BR)
    // =====================================================================

    private static String formatDecimal(double value, int fractionDigits) {
        NumberFormat nf = NumberFormat.getNumberInstance(PT_BR);
        nf.setMinimumFractionDigits(fractionDigits);
        nf.setMaximumFractionDigits(fractionDigits);
        return nf.format(value);
    }

    private static String formatCurrency(double value) {
        return "R$ " + formatDecimal(value, 2);
    }

    private static String formatSignedCurrency(double value) {
        String sign = value < 0 ? "− " : "+ ";
        return sign + "R$ " + formatDecimal(Math.abs(value), 2);
    }

    private static String formatSignedCurrencyNoDecimals(double value) {
        String sign = value < 0 ? "− " : "+ ";
        return sign + "R$ " + formatDecimal(Math.abs(value), 0);
    }

    private static String formatSignedNumber(double value) {
        String sign = value < 0 ? "−" : "+";
        return sign + formatDecimal(Math.abs(value), 0);
    }

    private static String formatSignedPercent(double value) {
        String sign = value < 0 ? "−" : "+";
        return sign + formatDecimal(Math.abs(value), 2) + "%";
    }

    private static String formatSignedPoints(double value) {
        String sign = value < 0 ? "−" : "+";
        return sign + formatDecimal(Math.abs(value), 2) + " p.p.";
    }

    private static String formatCompact(double value) {
        if (Math.abs(value) >= 1000) {
            return formatDecimal(value / 1000.0, 0) + "k";
        }
        return formatDecimal(value, 0);
    }

    private static String formatCompactCurrency(double value) {
        if (Math.abs(value) >= 1000) {
            return "R$ " + formatDecimal(value / 1000.0, 1) + "k";
        }
        return formatCurrency(value);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String toHex(Color color) {
        return String.format("#%02x%02x%02x",
                (int) Math.round(color.getRed() * 255),
                (int) Math.round(color.getGreen() * 255),
                (int) Math.round(color.getBlue() * 255));
    }
}
