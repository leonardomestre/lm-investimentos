package com.investimento.app.ui;

import javafx.scene.paint.Color;

/**
 * Os dois temas visuais do app e as cores que <b>nao</b> podem vir do CSS.
 *
 * <p>Quase toda cor da aplicacao vem de um token {@code -fx-color-*} do
 * {@code theme.css}/{@code theme-dark.css} (ver {@link ThemeManager}). A
 * excecao sao os graficos: eles sao desenhados em {@code Canvas} via
 * {@code GraphicsContext}, que pinta com {@link Color} de codigo Java e nao
 * enxerga looked-up color nenhuma do CSS. Antes desta classe, cada
 * {@code *View} tinha suas proprias constantes {@code Color.web("#...")}
 * duplicadas — o que funcionava com um tema so, mas travava a existencia de
 * um segundo.</p>
 *
 * <p>Por isso os hex abaixo sao <b>duplicatas deliberadas</b> dos tokens dos
 * dois stylesheets: mesma cor, declarada nos dois lugares porque cada motor
 * de desenho le de um lugar diferente. Ao mexer numa cor de grafico, mude nos
 * dois (aqui e no CSS correspondente) — e em
 * {@code design-system/references/paleta.md}, que documenta a origem de
 * cada uma.</p>
 */
public enum Theme {

    /** Tema padrao — paleta clara do template (`theme.css`). */
    LIGHT(
            "#eeebe5", // chart-grid
            "#a2a8ab", // chart-axis-text
            "#2f6f5e", // chart-line-primary (= accent-strong)
            "#c3bfb6", // chart-line-secondary
            "#3d9c78", // accent (base do preenchimento de area)
            "#2f6f5e", // accent-strong
            "#c9a227", // neutral-warn
            "#a08316", // neutral-warn-dark
            "#8a9196", // text-faint
            "#2f6f5e", // category-acoes
            "#5cae92", // category-fiis
            "#14181a", // category-renda-fixa
            "#c9a227", // category-cripto
            "#b3402f"  // category-cambio
    ),

    /** Tema escuro (`theme.css` + `theme-dark.css`). */
    DARK(
            "#252d31",
            "#7f888d",
            "#4fb890",
            "#5c666b",
            "#4fb890",
            "#3d9c78",
            "#d9b445",
            "#e0c46a",
            "#828b90",
            "#3d9c78",
            "#7fd4b3",
            "#c6cccf",
            "#d9b445",
            "#e08a76"
    );

    /**
     * Opacidade do preenchimento sob a linha principal do grafico —
     * {@code chart-area-fill} da paleta ("accent com opacidade .1"), igual
     * nos dois temas.
     */
    private static final double AREA_FILL_OPACITY = 0.1;

    private final Color chartGrid;
    private final Color chartAxisText;
    private final Color chartLinePrimary;
    private final Color chartLineSecondary;
    private final Color accent;
    private final Color accentStrong;
    private final Color neutralWarn;
    private final Color neutralWarnDark;
    private final Color textFaint;
    private final Color categoryStocks;
    private final Color categoryFiis;
    private final Color categoryFixedIncome;
    private final Color categoryCrypto;
    private final Color categoryForex;

    Theme(String chartGrid, String chartAxisText, String chartLinePrimary, String chartLineSecondary,
          String accent, String accentStrong, String neutralWarn, String neutralWarnDark, String textFaint,
          String categoryStocks, String categoryFiis, String categoryFixedIncome, String categoryCrypto,
          String categoryForex) {
        this.chartGrid = Color.web(chartGrid);
        this.chartAxisText = Color.web(chartAxisText);
        this.chartLinePrimary = Color.web(chartLinePrimary);
        this.chartLineSecondary = Color.web(chartLineSecondary);
        this.accent = Color.web(accent);
        this.accentStrong = Color.web(accentStrong);
        this.neutralWarn = Color.web(neutralWarn);
        this.neutralWarnDark = Color.web(neutralWarnDark);
        this.textFaint = Color.web(textFaint);
        this.categoryStocks = Color.web(categoryStocks);
        this.categoryFiis = Color.web(categoryFiis);
        this.categoryFixedIncome = Color.web(categoryFixedIncome);
        this.categoryCrypto = Color.web(categoryCrypto);
        this.categoryForex = Color.web(categoryForex);
    }

    /** Linhas de grade horizontais do grafico. */
    public Color chartGrid() {
        return chartGrid;
    }

    /** Texto dos eixos (mono, 10px). */
    public Color chartAxisText() {
        return chartAxisText;
    }

    /** Linha principal (patrimonio, cotacao, valor bruto projetado). */
    public Color chartLinePrimary() {
        return chartLinePrimary;
    }

    /** Linha de comparacao tracejada (CDI acumulado, marcador de "hoje"). */
    public Color chartLineSecondary() {
        return chartLineSecondary;
    }

    /** Verde de marca — bolinha ativa, barra de progresso. */
    public Color accent() {
        return accent;
    }

    /** Verde mais forte — 1a fatia do donut, preenchimento de destaque. */
    public Color accentStrong() {
        return accentStrong;
    }

    /** Ambar — linha de preco medio, linha de valor liquido. */
    public Color neutralWarn() {
        return neutralWarn;
    }

    /** Variante do ambar usada em <b>texto</b> (mais legivel que a da linha). */
    public Color neutralWarnDark() {
        return neutralWarnDark;
    }

    /** Cinza terciario — usado como linha auxiliar discreta em grafico. */
    public Color textFaint() {
        return textFaint;
    }

    /** Preenchimento translucido sob a linha principal. */
    public Color chartArea() {
        return accent.deriveColor(0, 1, 1, AREA_FILL_OPACITY);
    }

    /** Cor fixa de "Acoes" no donut/legenda/barras. */
    public Color categoryStocks() {
        return categoryStocks;
    }

    /** Cor fixa de "FIIs". */
    public Color categoryFiis() {
        return categoryFiis;
    }

    /** Cor fixa de "Renda fixa". */
    public Color categoryFixedIncome() {
        return categoryFixedIncome;
    }

    /** Cor fixa de "Criptomoedas". */
    public Color categoryCrypto() {
        return categoryCrypto;
    }

    /** Cor fixa de "Cambio". */
    public Color categoryForex() {
        return categoryForex;
    }
}
