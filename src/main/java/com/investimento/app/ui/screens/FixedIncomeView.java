package com.investimento.app.ui.screens;

import com.investimento.app.data.model.Benchmark;
import com.investimento.app.data.model.Category;
import com.investimento.app.dto.AssetDTO;
import com.investimento.app.dto.FixedIncomeProjectionPoint;
import com.investimento.app.dto.Position;
import com.investimento.app.service.MarketService;
import com.investimento.app.service.PositionService;
import com.investimento.app.ui.Theme;
import com.investimento.app.ui.ThemeManager;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Label;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Tela Renda Fixa (ATV-15, planejamento 5.4) — não depende de cotação de
 * mercado: todo valor vem de cálculo por juros compostos sobre a taxa
 * contratada/indexador (mesma fórmula da ATV-10). Adiciona o valor líquido
 * projetado (IR regressivo, tabela definida nesta atividade) ao valor bruto
 * que a ATV-10 já calculava. Substitui o stub da ATV-07.
 *
 * <p><strong>Limitação conhecida, documentada em vez de resolvida (decisão em
 * aberto da própria ATV-15)</strong>: o schema (ATV-01) não distingue LCI/LCA
 * (isentas de IR para pessoa física) de CDB/Tesouro Direto (tributados) — não
 * há campo {@code tax_exempt} em {@code assets}. Esta tela **sempre** aplica
 * a tabela de IR regressivo a todo título de renda fixa, mesmo que o usuário
 * cadastre uma LCI/LCA (nesse caso o valor líquido fica subestimado). Uma
 * nota fixa é exibida na tela alertando sobre essa limitação, conforme a
 * própria atividade autoriza como alternativa a adicionar o campo agora.</p>
 */
public class FixedIncomeView implements ScreenView {

    /**
     * Cores de grafico do tema ativo (ver {@link ThemeManager}) — linha do
     * valor bruto em {@code accentStrong}, linha do liquido em
     * {@code neutralWarn}, marcador vertical de "hoje" em
     * {@code chartLineSecondary}.
     */
    private static Theme theme() {
        return ThemeManager.current();
    }

    private static final Locale PT_BR = new Locale("pt", "BR");
    private static final String[] MONTH_ABBR =
            {"jan", "fev", "mar", "abr", "mai", "jun", "jul", "ago", "set", "out", "nov", "dez"};

    private final PositionService positionService;
    private final MarketService marketService;

    private final VBox root;
    private final Label subtitleLabel;

    private final GridPane kpiRow;
    private final VBox projectionCardBody = new VBox(16);
    private final VBox projectionCard;
    private final VBox positionsCardBody = new VBox(14);
    private final VBox positionsCard;
    private final VBox maturityByYearBody = new VBox(13);
    private final VBox irCardBody = new VBox(14);

    private List<Position> cachedPositions = List.of();
    private final Map<Long, Double> cachedNetValues = new HashMap<>();
    private Long selectedAssetId;

    public FixedIncomeView(PositionService positionService, MarketService marketService) {
        this.positionService = positionService;
        this.marketService = marketService;

        subtitleLabel = new Label("Cadastre títulos de renda fixa para começar.");
        subtitleLabel.getStyleClass().add("header-subtitle");

        HBox header = buildHeader();

        kpiRow = evenColumnsGrid(4, 12);

        projectionCard = new VBox(projectionCardBody);
        projectionCard.getStyleClass().add("content-card");

        positionsCard = buildPositionsCardWrapper();

        VBox maturityByYearCard = buildMaturityByYearCardWrapper();
        VBox irCard = new VBox(irCardBody);
        irCard.getStyleClass().add("card-dark");
        GridPane bottomRow = twoColumnRow(maturityByYearCard, irCard, 50, 50);

        Label limitationNote = new Label(
                "Cálculo assume tributação regular (IR regressivo) para todos os títulos — ainda não distingue "
                        + "LCI/LCA isentas de IR (limitação conhecida, ver ATV-15).");
        limitationNote.getStyleClass().add("content-card-subtitle");
        limitationNote.setWrapText(true);

        VBox contentBody = new VBox(20);
        contentBody.setPadding(new Insets(24, 28, 28, 28));
        contentBody.getChildren().setAll(kpiRow, projectionCard, positionsCard, bottomRow, limitationNote);

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
    // Header + "Recalcular projeções"
    // =====================================================================

    private HBox buildHeader() {
        Label title = new Label("Renda Fixa");
        title.getStyleClass().add("header-title");
        VBox titleBox = new VBox(4, title, subtitleLabel);
        HBox.setHgrow(titleBox, Priority.ALWAYS);

        Button recalculateButton = new Button("Recalcular projeções");
        recalculateButton.getStyleClass().add("button-primary");
        recalculateButton.setId("recalculateButton");
        recalculateButton.setOnAction(e -> refresh());

        HBox header = new HBox(20, titleBox, recalculateButton);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("header-bar");
        return header;
    }

    // =====================================================================
    // Orquestração de dados
    // =====================================================================

    private void refresh() {
        // Atualiza CDI/SELIC (rate_history) e IPCA (indicator_history), usados
        // pela formula de juros compostos - sincrono, mesmo padrao ja usado
        // por DashboardView.refresh() (ATV-12): cada metodo respeita seu
        // proprio TTL em memoria (5 min / 6h), entao cliques repetidos em
        // "Recalcular projeções" nao custam requisicao de rede toda vez.
        marketService.getMacroSnapshot();
        marketService.getIndicators();

        cachedPositions = positionService.calculateAllPositions(false).stream()
                .filter(p -> p.asset().category() == Category.FIXED_INCOME)
                .sorted(Comparator.comparing(
                        (Position p) -> p.asset().maturityDate(), Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        cachedNetValues.clear();
        for (Position p : cachedPositions) {
            cachedNetValues.put(p.asset().id(), positionService.calculateFixedIncomeNetValue(p.asset().id()));
        }

        if (selectedAssetId == null || cachedPositions.stream().noneMatch(p -> p.asset().id().equals(selectedAssetId))) {
            selectedAssetId = cachedPositions.isEmpty() ? null : cachedPositions.get(0).asset().id();
        }

        int totalCount = cachedPositions.size();
        subtitleLabel.setText(totalCount + (totalCount == 1 ? " título ativo" : " títulos ativos")
                + " · projeção recalculada com Selic e CDI de hoje");

        renderAll();
    }

    private void renderAll() {
        renderKpiRow();
        renderProjectionCard();
        renderPositionsTable();
        renderMaturityByYear();
        renderIrCard();
    }

    private void selectAsset(long assetId) {
        this.selectedAssetId = assetId;
        renderProjectionCard();
        renderPositionsTable();
        renderIrCard();
    }

    /** Seam de teste (mesmo pacote) — simula clicar numa linha da tabela de posições. */
    void selectAssetForTest(long assetId) {
        selectAsset(assetId);
    }

    private double netValueOf(long assetId) {
        return cachedNetValues.getOrDefault(assetId, 0.0);
    }

    private Optional<Position> selectedPosition() {
        return cachedPositions.stream()
                .filter(p -> selectedAssetId != null && selectedAssetId.equals(p.asset().id()))
                .findFirst();
    }

    // =====================================================================
    // Bloco 1 — 4 KPI cards
    // =====================================================================

    private void renderKpiRow() {
        kpiRow.getChildren().clear();

        double totalInvested = cachedPositions.stream().mapToDouble(Position::investedValue).sum();
        double totalGross = cachedPositions.stream().mapToDouble(Position::currentValue).sum();
        double totalNet = cachedPositions.stream().mapToDouble(p -> netValueOf(p.asset().id())).sum();
        double grossGainPct = totalInvested > 0 ? (totalGross - totalInvested) / totalInvested * 100 : 0;

        List<VBox> cards = new ArrayList<>();
        cards.add(buildKpiCard("VALOR APLICADO", formatCurrency(totalInvested), null,
                cachedPositions.size() + (cachedPositions.size() == 1 ? " título" : " títulos"), "kpi-footer-neutral"));
        cards.add(buildKpiCard("VALOR ATUAL BRUTO", formatCurrency(totalGross), grossGainPct >= 0,
                formatSignedPercent(grossGainPct), grossGainPct >= 0 ? "kpi-footer-gain" : "kpi-footer-loss"));
        cards.add(buildKpiCard("VALOR ATUAL LÍQUIDO", formatCurrency(totalNet), null,
                "IR regressivo aplicado", "kpi-footer-neutral"));

        Optional<Position> nextMaturity = cachedPositions.stream()
                .filter(p -> p.asset().maturityDate() != null)
                .min(Comparator.comparing(p -> p.asset().maturityDate()));
        if (nextMaturity.isPresent()) {
            LocalDate maturity = nextMaturity.get().asset().maturityDate();
            long daysUntil = ChronoUnit.DAYS.between(LocalDate.now(), maturity);
            VBox card = buildKpiCard("PRÓXIMO VENCIMENTO", formatDate(maturity), null,
                    assetTitleLabel(nextMaturity.get().asset()) + " · " + daysUntil + " dias", "kpi-footer-warn");
            cards.add(card);
        } else {
            cards.add(buildKpiCard("PRÓXIMO VENCIMENTO", "--", null,
                    "cadastre um título com vencimento", "kpi-footer-neutral"));
        }

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
    // Bloco 2 — Gráfico de projeção do ativo selecionado
    // =====================================================================

    private void renderProjectionCard() {
        projectionCardBody.getChildren().clear();
        Optional<Position> selected = selectedPosition();

        if (selected.isEmpty()) {
            Label title = new Label("Projeção até o vencimento");
            title.getStyleClass().add("content-card-title");
            Label subtitle = new Label(
                    "Cadastre e selecione um título de renda fixa na tabela abaixo para ver a projeção.");
            subtitle.getStyleClass().add("content-card-subtitle");
            subtitle.setWrapText(true);
            projectionCardBody.getChildren().addAll(title, subtitle);
            return;
        }

        AssetDTO asset = selected.get().asset();
        Label title = new Label("Projeção até o vencimento — " + assetTitleLabel(asset));
        title.setId("projectionTitleLabel");
        title.getStyleClass().add("content-card-title");
        String subtitleText = contractedRateLabel(asset)
                + (asset.investmentDate() != null ? " · aplicado em " + formatDate(asset.investmentDate()) : "")
                + (asset.maturityDate() != null ? " · vence em " + formatDate(asset.maturityDate()) : "");
        Label subtitle = new Label(subtitleText);
        subtitle.getStyleClass().add("content-card-subtitle");
        projectionCardBody.getChildren().addAll(title, subtitle);

        List<FixedIncomeProjectionPoint> points = positionService.calculateFixedIncomeProjection(asset.id());
        if (points.size() < 2) {
            Label placeholder = new Label(
                    "Sem data de aplicação e/ou vencimento suficientes para desenhar a projeção deste título.");
            placeholder.getStyleClass().add("content-card-subtitle");
            placeholder.setWrapText(true);
            projectionCardBody.getChildren().add(placeholder);
            return;
        }

        Canvas canvas = new Canvas(640, 220);
        canvas.setId("projectionChartCanvas");
        drawProjectionChart(canvas, points);

        FixedIncomeProjectionPoint atMaturity = points.get(points.size() - 1);
        projectionCardBody.getChildren().addAll(canvas, buildProjectionLegend(atMaturity));
    }

    /**
     * Mesma técnica de {@code Canvas}/{@code GraphicsContext} já usada nas
     * ATV-12/14 — grade, área sob a linha bruta, linha líquida (tracejada,
     * amarela), linha bruta (verde), linha vertical "hoje" quando o ativo
     * ainda não venceu, pontos finais, labels de eixo. Posicionamento no eixo
     * X é proporcional à data (não ao índice do ponto), para que o vértice
     * final caia exatamente em {@code maturityDate} mesmo quando o último
     * intervalo é menor que um mês (critério de aceite: "gráfico termina na
     * maturityDate, não continua além dela").
     */
    private void drawProjectionChart(Canvas canvas, List<FixedIncomeProjectionPoint> points) {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        double width = canvas.getWidth();
        double height = canvas.getHeight();
        gc.clearRect(0, 0, width, height);

        double leftPad = 50;
        double rightPad = 14;
        double topPad = 20;
        double bottomPad = 34;
        double plotWidth = width - leftPad - rightPad;
        double plotHeight = height - topPad - bottomPad;

        LocalDate start = points.get(0).date();
        LocalDate end = points.get(points.size() - 1).date();
        long totalDays = Math.max(1, ChronoUnit.DAYS.between(start, end));

        double minValue = Double.MAX_VALUE;
        double maxValue = -Double.MAX_VALUE;
        for (FixedIncomeProjectionPoint p : points) {
            minValue = Math.min(minValue, Math.min(p.grossValue(), p.netValue()));
            maxValue = Math.max(maxValue, Math.max(p.grossValue(), p.netValue()));
        }
        if (maxValue <= minValue) {
            maxValue = minValue + 1;
        }
        double padding = (maxValue - minValue) * 0.12;
        double rangeMin = Math.max(0, minValue - padding);
        double rangeMax = maxValue + padding;

        int n = points.size();
        double[] xs = new double[n];
        double[] grossYs = new double[n];
        double[] netYs = new double[n];
        for (int i = 0; i < n; i++) {
            FixedIncomeProjectionPoint p = points.get(i);
            long daysFromStart = ChronoUnit.DAYS.between(start, p.date());
            xs[i] = leftPad + plotWidth * daysFromStart / (double) totalDays;
            grossYs[i] = topPad + plotHeight * (1 - (p.grossValue() - rangeMin) / (rangeMax - rangeMin));
            netYs[i] = topPad + plotHeight * (1 - (p.netValue() - rangeMin) / (rangeMax - rangeMin));
        }

        // 1. grade horizontal + labels eixo Y
        gc.setStroke(theme().chartGrid());
        gc.setLineWidth(1);
        gc.setFont(Font.font("IBM Plex Mono", 10));
        gc.setFill(theme().chartAxisText());
        int gridLines = 5;
        for (int i = 0; i < gridLines; i++) {
            double y = topPad + plotHeight * i / (double) (gridLines - 1);
            gc.strokeLine(leftPad, y, width - rightPad, y);
            double value = rangeMax - (rangeMax - rangeMin) * i / (double) (gridLines - 1);
            gc.fillText(formatCompact(value), 0, y + 4);
        }

        // 2. area sob a linha bruta
        double[] areaX = new double[n + 2];
        double[] areaY = new double[n + 2];
        System.arraycopy(xs, 0, areaX, 0, n);
        System.arraycopy(grossYs, 0, areaY, 0, n);
        areaX[n] = xs[n - 1];
        areaY[n] = topPad + plotHeight;
        areaX[n + 1] = xs[0];
        areaY[n + 1] = topPad + plotHeight;
        gc.setFill(theme().chartArea());
        gc.fillPolygon(areaX, areaY, n + 2);

        // 3. linha vertical "hoje", so se o titulo ainda nao venceu
        LocalDate today = LocalDate.now();
        if (!today.isBefore(start) && !today.isAfter(end)) {
            double todayX = leftPad + plotWidth * ChronoUnit.DAYS.between(start, today) / (double) totalDays;
            gc.setStroke(theme().chartLineSecondary());
            gc.setLineWidth(1.5);
            gc.strokeLine(todayX, topPad, todayX, topPad + plotHeight);
            gc.setFill(theme().chartAxisText());
            gc.fillText("hoje", todayX - 12, topPad - 6);
        }

        // 4. linha liquida (tracejada)
        gc.setStroke(theme().neutralWarn());
        gc.setLineWidth(2);
        gc.setLineDashes(7, 5);
        gc.strokePolyline(xs, netYs, n);
        gc.setLineDashes((double[]) null);

        // 5. linha bruta (solida)
        gc.setStroke(theme().accentStrong());
        gc.setLineWidth(2.5);
        gc.strokePolyline(xs, grossYs, n);

        // 6. pontos finais destacados
        gc.setFill(theme().accentStrong());
        gc.fillOval(xs[n - 1] - 4.5, grossYs[n - 1] - 4.5, 9, 9);
        gc.setFill(theme().neutralWarn());
        gc.fillOval(xs[n - 1] - 4.5, netYs[n - 1] - 4.5, 9, 9);

        // 7. labels eixo X (inicio, ~1/4, meio, ~3/4, fim)
        gc.setFill(theme().chartAxisText());
        int[] labelIdx = n <= 5 ? intRange(n) : new int[]{0, n / 4, n / 2, (3 * n) / 4, n - 1};
        for (int idx : labelIdx) {
            LocalDate d = points.get(idx).date();
            String yy = String.valueOf(d.getYear());
            gc.fillText(MONTH_ABBR[d.getMonthValue() - 1] + "/" + yy.substring(2), xs[idx] - 14, height - 6);
        }
    }

    private HBox buildProjectionLegend(FixedIncomeProjectionPoint atMaturity) {
        HBox item1 = legendItem("Valor bruto projetado", theme().accentStrong());
        HBox item2 = legendItem("Valor líquido (IR regressivo)", theme().neutralWarn());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        VBox grossStat = footerStat("No vencimento (bruto)", formatCurrency(atMaturity.grossValue()), "-fx-color-text-primary;");
        VBox netStat = footerStat("No vencimento (líquido)", formatCurrency(atMaturity.netValue()), "-fx-color-accent-strong;");

        HBox box = new HBox(18, item1, item2, spacer, grossStat, netStat);
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

    private VBox footerStat(String label, String value, String valueColor) {
        Label labelNode = new Label(label);
        labelNode.setStyle("-fx-font-family: 'Manrope Medium'; -fx-font-size: 11px; -fx-text-fill: -fx-color-text-faint;");
        Label valueNode = new Label(value);
        valueNode.setStyle("-fx-font-family: 'IBM Plex Mono SemiBold'; -fx-font-size: 15px; -fx-text-fill: " + valueColor);
        return new VBox(4, labelNode, valueNode);
    }

    // =====================================================================
    // Bloco 3 — Tabela de posições
    // =====================================================================

    private VBox buildPositionsCardWrapper() {
        Label title = new Label("Posições de renda fixa");
        title.getStyleClass().add("content-card-title");
        Label hint = new Label("valores líquidos consideram IR regressivo · clique em uma linha para ver a projeção");
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

        if (cachedPositions.isEmpty()) {
            Label empty = new Label(
                    "Nenhum título de renda fixa cadastrado ainda — cadastre em \"Cadastro e transações\".");
            empty.getStyleClass().add("content-card-subtitle");
            empty.setWrapText(true);
            positionsCardBody.getChildren().add(empty);
            return;
        }

        GridPane grid = new GridPane();
        grid.setId("positionsGrid");
        grid.setHgap(14);
        grid.getColumnConstraints().addAll(
                pctColumn(23, HPos.LEFT), pctColumn(10, HPos.LEFT), pctColumn(14, HPos.LEFT),
                pctColumn(12, HPos.LEFT), pctColumn(12, HPos.LEFT), pctColumn(14, HPos.RIGHT), pctColumn(15, HPos.RIGHT));

        grid.add(tableHeaderCell("INSTITUIÇÃO / TÍTULO", HPos.LEFT), 0, 0);
        grid.add(tableHeaderCell("INDEXADOR", HPos.LEFT), 1, 0);
        grid.add(tableHeaderCell("TAXA CONTRATADA", HPos.LEFT), 2, 0);
        grid.add(tableHeaderCell("APLICAÇÃO", HPos.LEFT), 3, 0);
        grid.add(tableHeaderCell("VENCIMENTO", HPos.LEFT), 4, 0);
        grid.add(tableHeaderCell("BRUTO PROJET.", HPos.RIGHT), 5, 0);
        grid.add(tableHeaderCell("LÍQUIDO PROJET.", HPos.RIGHT), 6, 0);

        for (int i = 0; i < cachedPositions.size(); i++) {
            Position p = cachedPositions.get(i);
            int row = i + 1;
            boolean last = i == cachedPositions.size() - 1;
            boolean selected = selectedAssetId != null && selectedAssetId.equals(p.asset().id());
            AssetDTO asset = p.asset();

            Node titleNode = titleCell(asset, last, selected);
            Node indexerNode = indexerBadgeCell(asset.benchmark(), last, selected);
            Label rateNode = tableCell(contractedRateLabel(asset), "table-cell-numeric", HPos.LEFT, last, selected);
            Label appliedNode = tableCell(formatDate(asset.investmentDate()), "table-row-secondary", HPos.LEFT, last, selected);
            Label maturityNode = tableCell(formatDate(asset.maturityDate()), "table-row-secondary", HPos.LEFT, last, selected);
            Label grossNode = tableCell(formatDecimal(p.currentValue(), 2), "table-cell-numeric", HPos.RIGHT, last, selected);
            Label netNode = tableCell(formatDecimal(netValueOf(asset.id()), 2), "table-cell-net", HPos.RIGHT, last, selected);

            long assetId = asset.id();
            for (Node n : List.of(titleNode, indexerNode, rateNode, appliedNode, maturityNode, grossNode, netNode)) {
                n.setCursor(Cursor.HAND);
                n.setOnMouseClicked(e -> selectAsset(assetId));
            }

            grid.add(titleNode, 0, row);
            grid.add(indexerNode, 1, row);
            grid.add(rateNode, 2, row);
            grid.add(appliedNode, 3, row);
            grid.add(maturityNode, 4, row);
            grid.add(grossNode, 5, row);
            grid.add(netNode, 6, row);
        }

        positionsCardBody.getChildren().add(grid);

        double totalGross = cachedPositions.stream().mapToDouble(Position::currentValue).sum();
        double totalNet = cachedPositions.stream().mapToDouble(p -> netValueOf(p.asset().id())).sum();
        positionsCardBody.getChildren().add(buildPositionsFooter(totalGross, totalNet));
    }

    private VBox titleCell(AssetDTO asset, boolean last, boolean selected) {
        Label titleLabel = new Label(assetTitleLabel(asset));
        titleLabel.getStyleClass().add("table-row-primary");
        String institution = asset.financialInstitution();
        Label sub = new Label(institution != null && !institution.isBlank() ? institution : "Renda fixa");
        sub.getStyleClass().add("table-row-secondary");
        VBox box = new VBox(3, titleLabel, sub);
        box.setAlignment(Pos.CENTER_LEFT);
        styleRowCell(box, last, selected);
        return box;
    }

    private HBox indexerBadgeCell(Benchmark benchmark, boolean last, boolean selected) {
        Label badge = new Label(indexerBadgeLabel(benchmark));
        if (benchmark == Benchmark.FIXED_RATE) {
            badge.setStyle("-fx-background-color: -fx-color-bg-muted; -fx-background-radius: 6; -fx-padding: 4 9;"
                    + " -fx-font-family: 'Manrope'; -fx-font-weight: bold; -fx-font-size: 11px; -fx-text-fill: -fx-color-text-secondary;");
        } else {
            badge.getStyleClass().add("badge-buy");
        }
        HBox box = new HBox(badge);
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

    private HBox buildPositionsFooter(double totalGross, double totalNet) {
        HBox row = new HBox();
        row.setId("positionsFooter");
        row.setAlignment(Pos.CENTER_LEFT);
        row.setStyle("-fx-border-color: transparent transparent -fx-color-border-row transparent;"
                + " -fx-border-width: 1 0 0 0; -fx-padding: 13 0 0 0;");
        Label totalLabel = new Label("Total projetado");
        totalLabel.setStyle("-fx-font-family: 'Manrope SemiBold'; -fx-font-size: 13px; -fx-text-fill: -fx-color-text-secondary;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label grossLabel = new Label("bruto " + formatDecimal(totalGross, 2));
        grossLabel.setStyle("-fx-font-family: 'IBM Plex Mono SemiBold'; -fx-font-size: 13px; -fx-text-fill: -fx-color-text-primary;");
        Label netLabel = new Label("líquido " + formatDecimal(totalNet, 2));
        netLabel.setStyle("-fx-font-family: 'IBM Plex Mono SemiBold'; -fx-font-size: 14px; -fx-text-fill: -fx-color-accent-strong;");
        HBox values = new HBox(26, grossLabel, netLabel);
        row.getChildren().addAll(totalLabel, spacer, values);
        return row;
    }

    // =====================================================================
    // Bloco 4 — Vencimentos por ano + IR regressivo do ativo selecionado
    // =====================================================================

    private VBox buildMaturityByYearCardWrapper() {
        Label title = new Label("Vencimentos por ano");
        title.getStyleClass().add("content-card-title");
        maturityByYearBody.setId("maturityByYearBody");
        VBox card = new VBox(14, title, maturityByYearBody);
        card.getStyleClass().add("content-card");
        return card;
    }

    private void renderMaturityByYear() {
        maturityByYearBody.getChildren().clear();

        Map<Integer, Double> byYear = new TreeMap<>();
        for (Position p : cachedPositions) {
            LocalDate maturity = p.asset().maturityDate();
            if (maturity == null) {
                continue;
            }
            byYear.merge(maturity.getYear(), p.currentValue(), Double::sum);
        }

        if (byYear.isEmpty()) {
            Label empty = new Label("Sem datas de vencimento cadastradas ainda.");
            empty.getStyleClass().add("content-card-subtitle");
            empty.setWrapText(true);
            maturityByYearBody.getChildren().add(empty);
            return;
        }

        double maxValue = byYear.values().stream().mapToDouble(Double::doubleValue).max().orElse(1);
        int currentYear = LocalDate.now().getYear();
        for (Map.Entry<Integer, Double> entry : byYear.entrySet()) {
            maturityByYearBody.getChildren().add(
                    buildMaturityYearRow(entry.getKey(), entry.getValue(), maxValue, entry.getKey() == currentYear));
        }
    }

    private GridPane buildMaturityYearRow(int year, double value, double maxValue, boolean highlight) {
        GridPane grid = new GridPane();
        grid.setHgap(12);
        ColumnConstraints yearCol = new ColumnConstraints(52);
        ColumnConstraints barCol = new ColumnConstraints();
        barCol.setHgrow(Priority.ALWAYS);
        ColumnConstraints valueCol = new ColumnConstraints(104);
        valueCol.setHalignment(HPos.RIGHT);
        grid.getColumnConstraints().addAll(yearCol, barCol, valueCol);

        Label yearLabel = new Label(String.valueOf(year));
        yearLabel.setStyle("-fx-font-family: 'Manrope SemiBold'; -fx-font-size: 13px; -fx-text-fill: -fx-color-text-secondary;");

        Region track = new Region();
        track.setPrefHeight(18);
        track.setMaxWidth(Double.MAX_VALUE);
        Region fill = new Region();
        fill.setPrefHeight(18);
        fill.setStyle("-fx-background-radius: 4; -fx-background-color: "
                + (highlight ? toHex(theme().neutralWarn()) : toHex(theme().accentStrong())) + ";");
        double widthFraction = maxValue > 0 ? Math.min(1.0, value / maxValue) : 0;
        fill.prefWidthProperty().bind(track.widthProperty().multiply(widthFraction));
        // StackPane estica os filhos ao tamanho maximo por padrao - sem isto a
        // barra sempre aparenta 100% de largura (gotcha ja documentado na
        // ATV-12/CLAUDE.md).
        fill.setMaxWidth(Region.USE_PREF_SIZE);
        StackPane barStack = new StackPane(track, fill);
        barStack.setAlignment(Pos.CENTER_LEFT);

        Label valueLabel = new Label(formatDecimal(value, 2));
        valueLabel.setStyle("-fx-font-family: 'IBM Plex Mono SemiBold'; -fx-font-size: 13px; -fx-text-fill: -fx-color-text-primary;");

        grid.add(yearLabel, 0, 0);
        grid.add(barStack, 1, 0);
        grid.add(valueLabel, 2, 0);
        return grid;
    }

    private void renderIrCard() {
        irCardBody.getChildren().clear();
        Optional<Position> selected = selectedPosition();

        Label title = new Label(selected.isPresent()
                ? "IR regressivo — " + assetTitleLabel(selected.get().asset())
                : "IR regressivo");
        title.setStyle("-fx-font-family: 'Manrope'; -fx-font-weight: bold; -fx-font-size: 15px; -fx-text-fill: white;");
        Label subtitle = new Label("alíquota cai conforme o prazo da aplicação");
        subtitle.getStyleClass().add("card-dark-label");
        VBox titleBox = new VBox(4, title, subtitle);
        irCardBody.getChildren().add(titleBox);

        if (selected.isEmpty()) {
            Label placeholder = new Label("Selecione um título na tabela para ver a faixa de IR aplicável.");
            placeholder.getStyleClass().add("card-dark-label");
            placeholder.setWrapText(true);
            irCardBody.getChildren().add(placeholder);
            return;
        }

        AssetDTO asset = selected.get().asset();
        long elapsedDays = asset.investmentDate() != null
                ? Math.max(0, ChronoUnit.DAYS.between(asset.investmentDate(), LocalDate.now()))
                : -1;

        GridPane bracketsGrid = new GridPane();
        bracketsGrid.setId("irBracketsGrid");
        bracketsGrid.setHgap(12);
        bracketsGrid.setVgap(11);
        addBracketRow(bracketsGrid, 0, "Até 180 dias", "22,5%", elapsedDays >= 0 && elapsedDays <= 180);
        addBracketRow(bracketsGrid, 1, "181 a 360 dias", "20,0%", elapsedDays > 180 && elapsedDays <= 360);
        addBracketRow(bracketsGrid, 2, "361 a 720 dias", "17,5%", elapsedDays > 360 && elapsedDays <= 720);
        addBracketRow(bracketsGrid, 3, "Acima de 720 dias", "15,0%", elapsedDays > 720);
        irCardBody.getChildren().add(bracketsGrid);

        Region divider = new Region();
        divider.getStyleClass().add("card-dark-divider");
        irCardBody.getChildren().add(divider);

        List<FixedIncomeProjectionPoint> points = positionService.calculateFixedIncomeProjection(asset.id());
        HBox estimateRow = new HBox();
        estimateRow.setAlignment(Pos.CENTER_LEFT);
        Label estimateLabel = new Label("IR estimado no vencimento");
        estimateLabel.getStyleClass().add("card-dark-label");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        double irEstimate = points.isEmpty() ? 0 : points.get(points.size() - 1).grossValue() - points.get(points.size() - 1).netValue();
        Label estimateValue = new Label(formatCurrency(irEstimate));
        estimateValue.setStyle("-fx-font-family: 'IBM Plex Mono SemiBold'; -fx-font-size: 17px; -fx-text-fill: white;");
        estimateRow.getChildren().addAll(estimateLabel, spacer, estimateValue);
        irCardBody.getChildren().add(estimateRow);
    }

    private void addBracketRow(GridPane grid, int row, String label, String rateText, boolean current) {
        Label labelNode = new Label(label);
        labelNode.getStyleClass().add("card-dark-label");
        Label rateNode = new Label(current ? rateText + " · faixa atual" : rateText);
        rateNode.getStyleClass().add(current ? "card-dark-value-gain" : "card-dark-value");
        grid.add(labelNode, 0, row);
        grid.add(rateNode, 1, row);
        GridPane.setHalignment(rateNode, HPos.RIGHT);
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
    // Rótulos / formatação de domínio
    // =====================================================================

    private static String assetTitleLabel(AssetDTO asset) {
        return asset.displayName() != null && !asset.displayName().isBlank() ? asset.displayName() : "Título #" + asset.id();
    }

    private static String indexerBadgeLabel(Benchmark benchmark) {
        if (benchmark == null) {
            return "--";
        }
        return switch (benchmark) {
            case CDI -> "CDI";
            case SELIC -> "SELIC";
            case IPCA -> "IPCA";
            case FIXED_RATE -> "PRÉ";
        };
    }

    /**
     * Rótulo da taxa contratada, coerente com a fórmula de juros compostos
     * REALMENTE usada por {@code PositionServiceImpl.equivalentAnnualRate}
     * (ATV-10) — não com a formatação do template visual, que mostra "Selic +
     * X%" para SELIC (spread) quando o código trata SELIC como multiplicador
     * ("X% da Selic"), igual a CDI. Mostrar uma fórmula que não bate com o
     * valor de fato calculado seria enganoso; ver nota no CLAUDE.md (ATV-15).
     */
    private static String contractedRateLabel(AssetDTO asset) {
        Double rate = asset.contractedRatePct();
        if (asset.benchmark() == null || rate == null) {
            return "--";
        }
        return switch (asset.benchmark()) {
            case FIXED_RATE -> formatDecimal(rate, 2) + "% a.a.";
            case CDI -> formatDecimal(rate, 2) + "% do CDI";
            case SELIC -> formatDecimal(rate, 2) + "% da Selic";
            case IPCA -> "IPCA + " + formatDecimal(rate, 2) + "%";
        };
    }

    // =====================================================================
    // Parsing / formatação (pt-BR)
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

    private static String formatCompact(double value) {
        if (Math.abs(value) >= 1000) {
            return formatDecimal(value / 1000.0, 0) + "k";
        }
        return formatDecimal(value, 0);
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
