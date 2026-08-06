package com.investimento.app.ui.screens;

import com.investimento.app.data.model.Asset;
import com.investimento.app.data.model.Category;
import com.investimento.app.data.model.QuoteHistory;
import com.investimento.app.dto.AssetDTO;
import com.investimento.app.dto.PortfolioSummary;
import com.investimento.app.dto.Position;
import com.investimento.app.repository.AssetRepository;
import com.investimento.app.repository.QuoteHistoryRepository;
import com.investimento.app.service.MarketService;
import com.investimento.app.service.PositionService;
import javafx.beans.binding.Bindings;
import javafx.concurrent.Task;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
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
import javafx.scene.text.Font;

import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Tela Câmbio e Cripto (ATV-16, planejamento 5.5) — posições de moeda
 * estrangeira e criptomoeda, **fontes diferentes por categoria** (decisão já
 * tomada no planejamento seção 2): câmbio via HG Brasil (sem histórico
 * gratuito — série construída localmente), cripto via CoinGecko (incluindo
 * Bitcoin, para manter uma fonte única de preço/variação dentro da carteira
 * cripto — nunca o BTC da HG Brasil). Substitui o stub da ATV-07.
 *
 * <p>{@link PositionService}/{@link MarketService} já fazem toda a conversão
 * de moeda estrangeira para BRL (ATV-10) e o roteamento por fonte (ATV-06) —
 * esta tela só filtra por {@link Category#FOREX}/{@link Category#CRYPTO} e
 * exibe, nunca chama {@code HgBrasilClient}/{@code CoinGeckoClient}
 * diretamente.</p>
 */
public class ForexCryptoView implements ScreenView {

    // Cores duplicadas de theme.css/paleta.md — Canvas desenha imperativamente
    // e não lê variável -fx-color-* do CSS (mesma nota das ATV-12/14/15).
    private static final Color COLOR_CHART_GRID = Color.web("#eeebe5");
    private static final Color COLOR_CHART_AXIS_TEXT = Color.web("#a2a8ab");
    private static final Color COLOR_CHART_LINE_FOREX = Color.web("#2f6f5e"); // accent-strong — sem exemplo de câmbio no template, mesma cor padrão das demais telas
    private static final Color COLOR_CHART_LINE_CRYPTO = Color.web("#c9a227"); // neutral-warn — cor da linha "Cotação (CoinGecko)" no template (tela 05)
    // Linha de referência "preço médio de compra": no template desta tela ela é
    // cinza neutro (text-faint), diferente das telas 03/04 que usam âmbar — e
    // o gráfico do template aqui NÃO tem área preenchida sob a linha principal
    // (ver Telas.dc.html linhas 861-869), por isso nenhuma constante de área.
    private static final Color COLOR_AVG_PRICE_LINE = Color.web("#8a9196");
    // Tokens de categoria usados na barra proporcional cripto/câmbio do card de
    // resumo escuro — NÃO são os mesmos tokens fixos de categoria do donut de
    // diversificação (paleta.md): o template desta tela (linha 895) mostra o
    // segmento de câmbio em accent puro (#3d9c78), não em category-cambio/loss
    // (#b3402f) — confirmado também pelo badge "Câmbio" da tabela desta mesma
    // tela (verde/accent-strong, nunca vermelho).
    private static final Color COLOR_CATEGORY_CRIPTO = Color.web("#c9a227");
    private static final Color COLOR_CATEGORY_CAMBIO = Color.web("#3d9c78");

    private static final Locale PT_BR = new Locale("pt", "BR");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final String[] MONTH_ABBR =
            {"jan", "fev", "mar", "abr", "mai", "jun", "jul", "ago", "set", "out", "nov", "dez"};

    private final PositionService positionService;
    private final AssetRepository assetRepository;
    private final QuoteHistoryRepository quoteHistoryRepository;
    private final MarketService marketService;

    private final VBox root;
    private final VBox contentBody;
    private final Label subtitleLabel;

    private final Map<PositionFilter, Label> filterPills = new EnumMap<>(PositionFilter.class);
    private PositionFilter currentFilter = PositionFilter.ALL;

    private final GridPane kpiRow;
    private final VBox chartCardBody = new VBox(16);
    private final VBox chartCard;
    private final VBox decompositionCardBody = new VBox(16);
    private final VBox positionsCardBody = new VBox(14);
    private final VBox positionsCard;

    // Posições FOREX+CRYPTO, sem filtro de aba — a aba Ambos/Câmbio/Cripto só
    // afeta a tabela (bloco 3), não os KPIs nem o resumo escuro (bloco 2),
    // mesmo comportamento do template (Telas.dc.html, tela 05).
    private List<Position> cachedPositions = List.of();
    private PortfolioSummary cachedSummary;
    private Map<Long, Double> cachedDailyChanges = Map.of();
    private Long selectedAssetId;

    public ForexCryptoView(PositionService positionService,
                            AssetRepository assetRepository,
                            QuoteHistoryRepository quoteHistoryRepository,
                            MarketService marketService) {
        this.positionService = positionService;
        this.assetRepository = assetRepository;
        this.quoteHistoryRepository = quoteHistoryRepository;
        this.marketService = marketService;

        subtitleLabel = new Label("Cadastre ativos de câmbio ou criptomoeda para começar.");
        subtitleLabel.getStyleClass().add("header-subtitle");

        HBox header = buildHeader();

        Label sourceNote = new Label(
                "Câmbio: cotação atual via HG Brasil, sem histórico inicial gratuito — a série cresce a cada dia de "
                        + "uso do app. Cripto (incl. Bitcoin): cotação via CoinGecko, com até 365 dias de histórico "
                        + "já no cadastro.");
        sourceNote.getStyleClass().add("content-card-subtitle");
        sourceNote.setWrapText(true);

        kpiRow = evenColumnsGrid(4, 12);

        chartCard = new VBox(chartCardBody);
        chartCard.getStyleClass().add("content-card");

        VBox decompositionCard = new VBox(decompositionCardBody);
        decompositionCard.getStyleClass().add("card-dark");

        GridPane chartRow = twoColumnRow(chartCard, decompositionCard, 60, 40);

        positionsCard = buildPositionsCardWrapper();

        contentBody = new VBox(20);
        contentBody.setPadding(new Insets(24, 28, 28, 28));
        contentBody.getChildren().setAll(sourceNote, kpiRow, chartRow, positionsCard);

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
        Label title = new Label("Câmbio e Cripto");
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

    /** Mesmo padrão RT06 já usado nas ATV-12/14 — roda em background, nunca bloqueia a FX thread. */
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

        Thread thread = new Thread(task, "market-update-forex-crypto");
        thread.setDaemon(true);
        thread.start();
    }

    // =====================================================================
    // Orquestração de dados
    // =====================================================================

    private void refresh() {
        List<Asset> assets = assetRepository.listAssets(false);
        List<Position> allPositions = positionService.calculateAllPositions(false);
        cachedPositions = allPositions.stream()
                .filter(p -> p.asset().category() == Category.FOREX || p.asset().category() == Category.CRYPTO)
                .sorted(Comparator.comparing((Position p) -> p.asset().category())
                        .thenComparing(Comparator.comparingDouble(Position::currentValue).reversed()))
                .toList();
        cachedSummary = positionService.calculatePortfolioSummary();
        cachedDailyChanges = marketService.getDailyChanges(assets);

        if (selectedAssetId == null || cachedPositions.stream().noneMatch(p -> p.asset().id().equals(selectedAssetId))) {
            selectedAssetId = cachedPositions.isEmpty() ? null : cachedPositions.get(0).asset().id();
        }

        double totalExposed = cachedPositions.stream().mapToDouble(Position::currentValue).sum();
        double pctOfPortfolio = cachedSummary != null && cachedSummary.totalCurrentValue() > 0
                ? totalExposed / cachedSummary.totalCurrentValue() * 100 : 0;
        subtitleLabel.setText(formatCurrency(totalExposed) + " expostos à variação cambial e cripto · "
                + formatDecimal(pctOfPortfolio, 0) + "% da carteira · atualizado às " + LocalTime.now().format(TIME_FMT));

        renderAll();
    }

    private void renderAll() {
        renderKpiRow();
        renderChartCard();
        renderDecompositionCard();
        renderPositionsTable();
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
        return switch (currentFilter) {
            case ALL -> true;
            case FOREX -> p.asset().category() == Category.FOREX;
            case CRYPTO -> p.asset().category() == Category.CRYPTO;
        };
    }

    // =====================================================================
    // Bloco 1 — até 4 KPI cards (maiores posições, câmbio + cripto)
    // =====================================================================

    private void renderKpiRow() {
        kpiRow.getChildren().clear();

        List<Position> top = cachedPositions.stream()
                .sorted(Comparator.comparingDouble(Position::currentValue).reversed())
                .limit(4)
                .toList();

        if (top.isEmpty()) {
            Label empty = new Label("Cadastre um ativo de câmbio ou criptomoeda para ver cotações aqui.");
            empty.getStyleClass().add("content-card-subtitle");
            empty.setWrapText(true);
            GridPane.setColumnSpan(empty, 4);
            kpiRow.add(empty, 0, 0);
            return;
        }

        for (int i = 0; i < top.size(); i++) {
            Position p = top.get(i);
            AssetDTO asset = p.asset();
            double unitPrice = p.currentQuantity() > 0 ? p.currentValue() / p.currentQuantity() : 0;
            Double change = cachedDailyChanges.get(asset.id());

            VBox card = buildKpiCard(assetLabel(asset) + " / BRL", formatQuotePrice(unitPrice),
                    change != null ? change >= 0 : null,
                    change != null ? formatSignedPercent(change) + " no dia" : "sem dado do dia (atualize as cotações)",
                    change == null ? "kpi-footer-neutral" : (change >= 0 ? "kpi-footer-gain" : "kpi-footer-loss"));
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
    // Bloco 2a — Gráfico do ativo selecionado
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
                    "Cadastre e selecione um ativo de câmbio ou criptomoeda na tabela abaixo para ver a evolução do preço.");
            subtitle.getStyleClass().add("content-card-subtitle");
            subtitle.setWrapText(true);
            chartCardBody.getChildren().addAll(title, subtitle);
            return;
        }

        AssetDTO asset = selected.asset();
        boolean crypto = asset.category() == Category.CRYPTO;
        double unitPrice = selected.currentQuantity() > 0 ? selected.currentValue() / selected.currentQuantity() : 0;
        Double changePct = cachedDailyChanges.get(asset.id());

        Label icon = new Label(tickerAbbreviation(asset));
        icon.setStyle("-fx-text-fill: " + (crypto ? toHex(COLOR_CATEGORY_CRIPTO) : "-fx-color-accent") + ";"
                + " -fx-font-family: 'IBM Plex Mono SemiBold'; -fx-font-size: 12px;");
        StackPane iconBox = new StackPane(icon);
        iconBox.setPrefSize(46, 46);
        iconBox.setMaxSize(46, 46);
        iconBox.setMinSize(46, 46);
        iconBox.setStyle("-fx-background-color: -fx-color-text-primary; -fx-background-radius: 12;");

        Label tickerLabel = new Label(assetLabel(asset));
        tickerLabel.setId("chartTickerLabel");
        tickerLabel.setStyle("-fx-font-family: 'Manrope ExtraBold'; -fx-font-size: 19px; -fx-text-fill: -fx-color-text-primary;");
        String subtitleText = (asset.displayName() != null && !asset.displayName().isBlank() ? asset.displayName() + " · " : "")
                + sourceLabel(asset) + " · " + formatQuantity(selected.currentQuantity(), asset.category()) + " un.";
        Label typeLabel = new Label(subtitleText);
        typeLabel.setStyle("-fx-font-family: 'Manrope Medium'; -fx-font-size: 13px; -fx-text-fill: -fx-color-text-muted;");
        HBox nameRow = new HBox(9, tickerLabel, typeLabel);
        nameRow.setAlignment(Pos.BASELINE_LEFT);

        Label priceLabel = new Label(formatQuotePrice(unitPrice));
        priceLabel.setStyle("-fx-font-family: 'IBM Plex Mono SemiBold'; -fx-font-size: 24px; -fx-text-fill: -fx-color-text-primary;");
        Label changeLabel = new Label(changePct != null ? formatSignedPercent(changePct) : "--");
        changeLabel.setStyle("-fx-font-family: 'IBM Plex Mono SemiBold'; -fx-font-size: 13px; -fx-text-fill: "
                + (changePct == null ? "-fx-color-text-muted;" : (changePct >= 0 ? "-fx-color-gain;" : "-fx-color-loss;")));
        Label avgLabel = new Label("preço médio " + formatQuotePrice(selected.averagePrice()));
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
            String placeholderText = crypto
                    ? "Ainda não há histórico de preço suficiente para desenhar o gráfico — o histórico inicial "
                    + "(até 365 dias) é importado no cadastro pela CoinGecko e complementado pelas consultas periódicas."
                    : "Câmbio ainda não tem histórico local suficiente — a HG Brasil não oferece histórico gratuito "
                    + "de câmbio no plano usado por este app; o gráfico cresce a cada dia em que o app é aberto com "
                    + "este ativo cadastrado (isso é esperado, não é um erro).";
            Label placeholder = new Label(placeholderText);
            placeholder.getStyleClass().add("content-card-subtitle");
            placeholder.setWrapText(true);
            chartCardBody.getChildren().add(placeholder);
            return;
        }

        Color lineColor = crypto ? COLOR_CHART_LINE_CRYPTO : COLOR_CHART_LINE_FOREX;
        Canvas canvas = new Canvas(640, 220);
        canvas.setId("assetChartCanvas");
        drawAssetChart(canvas, history, selected.averagePrice(), lineColor);
        chartCardBody.getChildren().addAll(canvas, buildChartLegend(crypto, lineColor));
    }

    /**
     * Mesma técnica de {@code Canvas}/{@code GraphicsContext} já usada nas
     * ATV-12/14/15 — grade, linha secundária (tracejada, preço médio de
     * compra), linha principal, ponto final, labels de eixo. Diferente das
     * outras telas, o template desta tela (Telas.dc.html linhas 861-869) não
     * preenche área sob a linha principal — por isso não há esse passo aqui.
     */
    private void drawAssetChart(Canvas canvas, List<QuoteHistory> history, double averagePrice, Color lineColor) {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        double width = canvas.getWidth();
        double height = canvas.getHeight();
        gc.clearRect(0, 0, width, height);

        double leftPad = 50;
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

        gc.setStroke(COLOR_AVG_PRICE_LINE);
        gc.setLineWidth(2);
        gc.setLineDashes(6, 5);
        gc.strokeLine(leftPad, avgY, width - rightPad, avgY);
        gc.setLineDashes((double[]) null);
        gc.setFill(COLOR_AVG_PRICE_LINE);
        gc.fillText("PM " + formatCompact(averagePrice), width - rightPad - 60, avgY - 6);

        gc.setStroke(lineColor);
        gc.setLineWidth(2.5);
        gc.strokePolyline(xs, ys, n);

        gc.setFill(lineColor);
        gc.fillOval(xs[n - 1] - 4.5, ys[n - 1] - 4.5, 9, 9);

        gc.setFill(COLOR_CHART_AXIS_TEXT);
        int[] labelIdx = n <= 5 ? intRange(n) : new int[]{0, n / 4, n / 2, (3 * n) / 4, n - 1};
        for (int idx : labelIdx) {
            LocalDate d = history.get(idx).getDate();
            gc.fillText(MONTH_ABBR[d.getMonthValue() - 1], xs[idx] - 8, height - 6);
        }
    }

    private HBox buildChartLegend(boolean crypto, Color lineColor) {
        HBox item1 = legendItem("Cotação (" + (crypto ? "CoinGecko" : "HG Brasil") + ")", lineColor);
        HBox item2 = legendItem("Preço médio de compra", COLOR_AVG_PRICE_LINE);
        Label caption = new Label(crypto
                ? "Primeiros até 365 dias vindos da CoinGecko; depois, histórico local."
                : "Série 100% construída localmente — sem histórico inicial gratuito de câmbio.");
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
    // Bloco 2b — Resumo escuro (câmbio x cripto, exposição fora do real)
    // =====================================================================

    private void renderDecompositionCard() {
        decompositionCardBody.getChildren().clear();

        Label eyebrow = new Label("RESUMO CÂMBIO E CRIPTO");
        eyebrow.getStyleClass().add("card-dark-eyebrow");

        double totalCurrent = cachedPositions.stream().mapToDouble(Position::currentValue).sum();
        double totalInvested = cachedPositions.stream().mapToDouble(Position::investedValue).sum();
        double gainLoss = totalCurrent - totalInvested;
        double gainLossPct = totalInvested > 0 ? gainLoss / totalInvested * 100 : 0;

        Label valueLabel = new Label("Valor atual convertido");
        valueLabel.getStyleClass().add("card-dark-label");
        Label heroValue = new Label(formatCurrency(totalCurrent));
        heroValue.setStyle("-fx-font-family: 'Manrope ExtraBold'; -fx-font-size: 27px; -fx-text-fill: white;");
        Label deltaLabel = new Label(formatSignedCurrency(gainLoss) + " · " + formatSignedPercent(gainLossPct));
        deltaLabel.getStyleClass().add(gainLoss >= 0 ? "card-dark-value-gain" : "card-dark-value-loss");
        VBox heroBox = new VBox(5, valueLabel, heroValue, deltaLabel);

        decompositionCardBody.getChildren().addAll(eyebrow, heroBox, darkDivider());

        double totalCrypto = cachedPositions.stream()
                .filter(p -> p.asset().category() == Category.CRYPTO).mapToDouble(Position::currentValue).sum();
        double totalForex = cachedPositions.stream()
                .filter(p -> p.asset().category() == Category.FOREX).mapToDouble(Position::currentValue).sum();

        GridPane detailsGrid = new GridPane();
        detailsGrid.setHgap(12);
        detailsGrid.setVgap(11);
        addDecompositionRow(detailsGrid, 0, "Investido (convertido)", formatCurrency(totalInvested));
        addDecompositionRow(detailsGrid, 1, "Cripto (incl. Bitcoin)", formatCurrency(totalCrypto));
        addDecompositionRow(detailsGrid, 2, "Câmbio", formatCurrency(totalForex));
        decompositionCardBody.getChildren().addAll(detailsGrid, darkDivider());

        double cryptoPct = totalCurrent > 0 ? totalCrypto / totalCurrent * 100 : 0;
        double forexPct = totalCurrent > 0 ? totalForex / totalCurrent * 100 : 0;
        decompositionCardBody.getChildren().addAll(buildCategoryBar(cryptoPct, forexPct), darkDivider());

        double pctOfPortfolio = cachedSummary != null && cachedSummary.totalCurrentValue() > 0
                ? totalCurrent / cachedSummary.totalCurrentValue() * 100 : 0;
        HBox footerRow = new HBox();
        footerRow.setAlignment(Pos.CENTER_LEFT);
        Label footerLabel = new Label("Exposição fora do real");
        footerLabel.getStyleClass().add("card-dark-label");
        Region footerSpacer = new Region();
        HBox.setHgrow(footerSpacer, Priority.ALWAYS);
        Label footerValue = new Label(formatDecimal(pctOfPortfolio, 0) + "% da carteira");
        footerValue.setStyle("-fx-font-family: 'IBM Plex Mono SemiBold'; -fx-font-size: 15px; -fx-text-fill: white;");
        footerRow.getChildren().addAll(footerLabel, footerSpacer, footerValue);
        decompositionCardBody.getChildren().add(footerRow);
    }

    private Region darkDivider() {
        Region divider = new Region();
        divider.getStyleClass().add("card-dark-divider");
        return divider;
    }

    private void addDecompositionRow(GridPane grid, int row, String label, String value) {
        Label labelNode = new Label(label);
        labelNode.getStyleClass().add("card-dark-label");
        Label valueNode = new Label(value);
        valueNode.getStyleClass().add("card-dark-value");
        grid.add(labelNode, 0, row);
        grid.add(valueNode, 1, row);
        GridPane.setHalignment(valueNode, HPos.RIGHT);
    }

    /**
     * Barra proporcional cripto/câmbio — mesma técnica das ATV-12/15
     * ({@code prefWidthProperty().bind(...)} + {@code
     * Region.USE_PREF_SIZE}), mas com 2 preenchimentos lado a lado num {@code
     * HBox} (que, ao contrário de {@code StackPane}, respeita o {@code
     * prefWidth} dos filhos sem precisar do ajuste de {@code maxWidth}).
     */
    private VBox buildCategoryBar(double cryptoPct, double forexPct) {
        HBox labelsRow = new HBox();
        labelsRow.setAlignment(Pos.CENTER_LEFT);
        Label cryptoLabel = new Label("CRIPTO " + formatDecimal(cryptoPct, 0) + "%");
        cryptoLabel.getStyleClass().add("card-dark-label");
        Label forexLabel = new Label("CÂMBIO " + formatDecimal(forexPct, 0) + "%");
        forexLabel.getStyleClass().add("card-dark-label");
        Region labelsSpacer = new Region();
        HBox.setHgrow(labelsSpacer, Priority.ALWAYS);
        labelsRow.getChildren().addAll(cryptoLabel, labelsSpacer, forexLabel);

        HBox track = new HBox();
        track.setPrefHeight(9);
        track.setMaxWidth(Double.MAX_VALUE);
        track.setStyle("-fx-background-radius: 5; -fx-background-color: -fx-color-sidebar-divider;");

        Region cryptoFill = new Region();
        cryptoFill.setPrefHeight(9);
        cryptoFill.setStyle("-fx-background-radius: 5 0 0 5; -fx-background-color: " + toHex(COLOR_CATEGORY_CRIPTO) + ";");
        Region forexFill = new Region();
        forexFill.setPrefHeight(9);
        forexFill.setStyle("-fx-background-radius: 0 5 5 0; -fx-background-color: " + toHex(COLOR_CATEGORY_CAMBIO) + ";");

        cryptoFill.prefWidthProperty().bind(track.widthProperty().multiply(cryptoPct / 100.0));
        forexFill.prefWidthProperty().bind(track.widthProperty().multiply(forexPct / 100.0));

        track.getChildren().addAll(cryptoFill, forexFill);

        return new VBox(8, labelsRow, track);
    }

    // =====================================================================
    // Bloco 3 — Tabela de posições
    // =====================================================================

    private VBox buildPositionsCardWrapper() {
        Label title = new Label("Posições");
        title.getStyleClass().add("content-card-title");
        Label hint = new Label("ganho/perda já considera variação cambial ou de cripto · clique numa linha para ver o gráfico");
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
                    ? "Nenhum ativo de câmbio ou criptomoeda cadastrado ainda — cadastre em \"Cadastro e transações\"."
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
                pctColumn(24, HPos.LEFT), pctColumn(11, HPos.LEFT), pctColumn(13, HPos.RIGHT),
                pctColumn(13, HPos.RIGHT), pctColumn(13, HPos.RIGHT), pctColumn(13, HPos.RIGHT), pctColumn(13, HPos.RIGHT));

        grid.add(tableHeaderCell("ATIVO", HPos.LEFT), 0, 0);
        grid.add(tableHeaderCell("TIPO", HPos.LEFT), 1, 0);
        grid.add(tableHeaderCell("QUANTIDADE", HPos.RIGHT), 2, 0);
        grid.add(tableHeaderCell("COTAÇÃO ATUAL", HPos.RIGHT), 3, 0);
        grid.add(tableHeaderCell("INVESTIDO (R$)", HPos.RIGHT), 4, 0);
        grid.add(tableHeaderCell("ATUAL (R$)", HPos.RIGHT), 5, 0);
        grid.add(tableHeaderCell("GANHO / PERDA", HPos.RIGHT), 6, 0);

        for (int i = 0; i < filtered.size(); i++) {
            Position p = filtered.get(i);
            int row = i + 1;
            boolean last = i == filtered.size() - 1;
            boolean selected = selectedAssetId != null && selectedAssetId.equals(p.asset().id());
            AssetDTO asset = p.asset();

            double unitPrice = p.currentQuantity() > 0 ? p.currentValue() / p.currentQuantity() : 0;

            Node tickerNode = tickerCell(p, last, selected);
            Node badgeNode = categoryBadgeCell(asset.category(), last, selected);
            Label qtyNode = tableCell(formatQuantity(p.currentQuantity(), asset.category()), "table-cell-numeric", HPos.RIGHT, last, selected);
            Label priceNode = tableCell(formatQuotePrice(unitPrice), "table-cell-numeric", HPos.RIGHT, last, selected);
            Label investedNode = tableCell(formatDecimal(p.investedValue(), 2), "table-cell-numeric", HPos.RIGHT, last, selected);
            Label currentNode = tableCell(formatDecimal(p.currentValue(), 2), "table-cell-numeric", HPos.RIGHT, last, selected);
            Node gainLossNode = gainLossCell(p, last, selected);

            long assetId = asset.id();
            for (Node n : List.of(tickerNode, badgeNode, qtyNode, priceNode, investedNode, currentNode, gainLossNode)) {
                n.setCursor(Cursor.HAND);
                n.setOnMouseClicked(e -> selectAsset(assetId));
            }

            grid.add(tickerNode, 0, row);
            grid.add(badgeNode, 1, row);
            grid.add(qtyNode, 2, row);
            grid.add(priceNode, 3, row);
            grid.add(investedNode, 4, row);
            grid.add(currentNode, 5, row);
            grid.add(gainLossNode, 6, row);
        }

        positionsCardBody.getChildren().add(grid);

        double totalInvested = filtered.stream().mapToDouble(Position::investedValue).sum();
        double totalCurrent = filtered.stream().mapToDouble(Position::currentValue).sum();
        positionsCardBody.getChildren().add(buildPositionsFooter(totalInvested, totalCurrent));
    }

    /**
     * Linha "ativo": nome de exibição + ticker/moeda (main) e a fonte de dado
     * (sub) — deixa explícito, linha a linha, que câmbio vem da HG Brasil e
     * cripto (incluindo Bitcoin) vem da CoinGecko, conforme critério de
     * aceite da ATV-16.
     */
    private VBox tickerCell(Position p, boolean last, boolean selected) {
        AssetDTO asset = p.asset();
        String main = asset.displayName() != null && !asset.displayName().isBlank()
                ? asset.displayName() + " · " + assetLabel(asset)
                : assetLabel(asset);
        Label ticker = new Label(main);
        ticker.getStyleClass().add("table-row-primary");
        Label sub = new Label(sourceLabel(asset));
        sub.getStyleClass().add("table-row-secondary");
        VBox box = new VBox(3, ticker, sub);
        box.setAlignment(Pos.CENTER_LEFT);
        styleRowCell(box, last, selected);
        return box;
    }

    private HBox categoryBadgeCell(Category category, boolean last, boolean selected) {
        Label badge = new Label(category == Category.CRYPTO ? "Cripto" : "Câmbio");
        if (category == Category.CRYPTO) {
            badge.setStyle("-fx-background-color: -fx-color-neutral-warn-bg; -fx-background-radius: 6; -fx-padding: 4 9;"
                    + " -fx-font-family: 'Manrope'; -fx-font-weight: bold; -fx-font-size: 11px; -fx-text-fill: -fx-color-neutral-warn-dark;");
        } else {
            badge.setStyle("-fx-background-color: -fx-color-gain-bg; -fx-background-radius: 6; -fx-padding: 4 9;"
                    + " -fx-font-family: 'Manrope'; -fx-font-weight: bold; -fx-font-size: 11px; -fx-text-fill: -fx-color-accent-strong;");
        }
        HBox box = new HBox(badge);
        box.setAlignment(Pos.CENTER_LEFT);
        styleRowCell(box, last, selected);
        return box;
    }

    private VBox gainLossCell(Position p, boolean last, boolean selected) {
        Label pctLabel = new Label(formatSignedPercent(p.gainLossPercent()));
        pctLabel.getStyleClass().add(p.gainLossPercent() >= 0 ? "table-cell-gain" : "table-cell-loss");
        Label amtLabel = new Label(formatSignedNumber(p.gainLossAmount(), 2));
        amtLabel.setStyle("-fx-font-family: 'IBM Plex Mono'; -fx-font-size: 11px; -fx-text-fill: "
                + (p.gainLossAmount() >= 0 ? "-fx-color-gain;" : "-fx-color-loss;"));
        VBox box = new VBox(3, pctLabel, amtLabel);
        box.setAlignment(Pos.CENTER_RIGHT);
        box.setMaxWidth(Double.MAX_VALUE);
        GridPane.setHalignment(box, HPos.RIGHT);
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

    private HBox buildPositionsFooter(double totalInvested, double totalCurrent) {
        double gainLossAmount = totalCurrent - totalInvested;
        double gainLossPct = totalInvested > 0 ? gainLossAmount / totalInvested * 100 : 0;

        HBox row = new HBox();
        row.setId("positionsFooter");
        row.setAlignment(Pos.CENTER_LEFT);
        row.setStyle("-fx-border-color: transparent transparent -fx-color-border-row transparent;"
                + " -fx-border-width: 1 0 0 0; -fx-padding: 13 0 0 0;");
        Label totalLabel = new Label("Total (filtro atual)");
        totalLabel.setStyle("-fx-font-family: 'Manrope SemiBold'; -fx-font-size: 13px; -fx-text-fill: -fx-color-text-secondary;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label investedLabel = new Label("investido " + formatDecimal(totalInvested, 2));
        investedLabel.setStyle("-fx-font-family: 'IBM Plex Mono SemiBold'; -fx-font-size: 13px; -fx-text-fill: -fx-color-text-primary;");
        Label currentLabel = new Label("atual " + formatDecimal(totalCurrent, 2));
        currentLabel.setStyle("-fx-font-family: 'IBM Plex Mono SemiBold'; -fx-font-size: 13px; -fx-text-fill: -fx-color-text-primary;");
        Label gainLossLabel = new Label(formatSignedNumber(gainLossAmount, 2) + " (" + formatSignedPercent(gainLossPct) + ")");
        gainLossLabel.setStyle("-fx-font-family: 'IBM Plex Mono SemiBold'; -fx-font-size: 14px; -fx-text-fill: "
                + (gainLossAmount >= 0 ? "-fx-color-gain;" : "-fx-color-loss;"));
        HBox values = new HBox(22, investedLabel, currentLabel, gainLossLabel);
        row.getChildren().addAll(totalLabel, spacer, values);
        return row;
    }

    // =====================================================================
    // Helpers de layout
    // =====================================================================

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
        ALL("Ambos"), FOREX("Câmbio"), CRYPTO("Cripto");

        final String label;

        PositionFilter(String label) {
            this.label = label;
        }
    }

    /**
     * Rótulo curto do ativo: ticker (cripto, ex.: {@code BTC}) ou o código
     * ISO da moeda (câmbio, ex.: {@code USD}) — {@code AssetDTO.ticker()} é
     * sempre {@code null} para {@link Category#FOREX} (ATV-08), então não dá
     * para reaproveitar {@code ticker != null ? ticker : displayName} como as
     * demais telas fazem.
     */
    private static String assetLabel(AssetDTO asset) {
        if (asset.category() == Category.FOREX) {
            return asset.currency();
        }
        return asset.ticker() != null && !asset.ticker().isBlank() ? asset.ticker() : asset.displayName();
    }

    private static String sourceLabel(AssetDTO asset) {
        return asset.category() == Category.CRYPTO ? "CoinGecko" : "HG Brasil";
    }

    private static String tickerAbbreviation(AssetDTO asset) {
        String base = assetLabel(asset);
        if (base == null || base.isBlank()) {
            return "--";
        }
        String upper = base.trim().toUpperCase(PT_BR);
        return upper.length() >= 3 ? upper.substring(0, 3) : upper;
    }

    // =====================================================================
    // Parsing / formatação (pt-BR)
    // =====================================================================

    /** 0 decimais para cotações "grandes" (ex.: BTC/ETH), 2 para as demais (ex.: USD/EUR). */
    private static String formatQuotePrice(double value) {
        return formatDecimal(value, Math.abs(value) >= 100 ? 0 : 2);
    }

    /** Quantidade de cripto usa mais casas decimais (unidades fracionárias) que câmbio. */
    private static String formatQuantity(double qty, Category category) {
        return formatDecimal(qty, category == Category.CRYPTO ? 6 : 2);
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

    private static String formatCompact(double value) {
        if (Math.abs(value) >= 1000) {
            return formatDecimal(value / 1000.0, 0) + "k";
        }
        return formatDecimal(value, 0);
    }

    private static String formatSignedCurrency(double value) {
        String sign = value < 0 ? "− " : "+ ";
        return sign + "R$ " + formatDecimal(Math.abs(value), 2);
    }

    private static String formatSignedNumber(double value, int fractionDigits) {
        String sign = value < 0 ? "−" : "+";
        return sign + formatDecimal(Math.abs(value), fractionDigits);
    }

    private static String formatSignedPercent(double value) {
        String sign = value < 0 ? "−" : "+";
        return sign + formatDecimal(Math.abs(value), 2) + "%";
    }

    private static String toHex(Color color) {
        return String.format("#%02x%02x%02x",
                (int) Math.round(color.getRed() * 255),
                (int) Math.round(color.getGreen() * 255),
                (int) Math.round(color.getBlue() * 255));
    }
}
