package com.investimento.app.ui;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Teste manual headless do tema escuro (pendencia 1 de
 * {@code documentacao/pendencias.md}).
 *
 * <p>Mesmo padrao dos demais {@code *ManualTest} do projeto: {@code main()},
 * sem JUnit, {@code Platform.startup} para ter o toolkit sem abrir janela
 * nenhuma. Cobre os 3 pontos que a pendencia exigia — paleta escura completa,
 * mecanismo de troca em tempo de execucao, e cores de {@code Canvas} (que nao
 * saem do CSS) — inclusive o unico que realmente prova que a troca funciona:
 * a cor renderizada de um {@code Label} muda depois de
 * {@link ThemeManager#apply}.</p>
 *
 * <p>Rodar (a partir de {@code target/classes} + jars do JavaFX copiados para
 * um caminho sem espaco — ver gotchas no CLAUDE.md):
 * {@code java -cp "C:/tmp/...;..." com.investimento.app.ui.ThemeManagerManualTest}</p>
 */
public final class ThemeManagerManualTest {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        Platform.startup(() -> {
        });
        try {
            scenario1TokenParity();
            scenario2ChartColorsDiffer();
            scenario3SettingValueRoundTrip();
            scenario4StylesheetSwap();
            scenario5RenderedColorChanges();
            scenario6FormFieldLegibilityInDark();
        } finally {
            Platform.exit();
        }

        if (failures > 0) {
            System.out.println("\nFALHAS: " + failures);
            System.exit(1);
        }
        System.out.println("\nTodos os cenarios passaram.");
    }

    // =====================================================================
    // Cenario 1 — todo token do tema claro tem par no escuro
    // =====================================================================

    /**
     * A pendencia dizia que o tema escuro exigia "todas as ~40 cores... com
     * um par claro/escuro". Este cenario e o que garante que nenhuma ficou
     * para tras: extrai os nomes de token do bloco {@code .root} de cada
     * stylesheet e compara os conjuntos.
     *
     * <p>O tema escuro pode declarar tokens a MAIS (os do Modena, que o tema
     * claro nao precisa redefinir), mas nunca a menos — um token sem par
     * escuro fica com a cor clara no meio da tela escura.</p>
     */
    private static void scenario1TokenParity() throws IOException {
        System.out.println("== Cenario 1: paridade de tokens claro x escuro ==");

        Set<String> light = colorTokensOfRootBlock("/theme.css");
        Set<String> dark = colorTokensOfRootBlock("/theme-dark.css");

        System.out.println("  tokens -fx-color-* no theme.css:      " + light.size());
        System.out.println("  tokens -fx-color-* no theme-dark.css: " + dark.size());

        Set<String> missing = new LinkedHashSet<>(light);
        missing.removeAll(dark);
        check(missing.isEmpty(), "todo token do tema claro tem par no escuro (faltando: " + missing + ")");

        Set<String> extra = new LinkedHashSet<>(dark);
        extra.removeAll(light);
        check(extra.isEmpty(), "tema escuro nao inventa token -fx-color-* que o claro nao tem (extras: " + extra + ")");

        // Guarda contra o oposto do bug acima: um par declarado mas copiado
        // igual, que nao mudaria nada na tela.
        int identical = 0;
        for (String token : light) {
            String lightValue = colorValue("/theme.css", token);
            String darkValue = colorValue("/theme-dark.css", token);
            if (lightValue.equalsIgnoreCase(darkValue)) {
                identical++;
                System.out.println("  (token identico nos dois temas, proposital? " + token + " = " + lightValue + ")");
            }
        }
        // Alguns tokens SAO iguais de proposito (a sidebar ja era escura, o
        // texto sobre accent continua quase-preto). O que nao pode e a
        // maioria ser igual — isso significaria um theme-dark.css inerte.
        check(identical < light.size() / 2,
                "a maioria dos tokens muda de valor entre os temas (" + identical + " de " + light.size() + " iguais)");
    }

    // =====================================================================
    // Cenario 2 — cores de Canvas (Theme) mudam entre os temas
    // =====================================================================

    /**
     * Os graficos nao leem CSS: quem define a cor deles e o enum
     * {@link Theme}. Se {@code DARK} tivesse sido criado copiando
     * {@code LIGHT}, os stylesheets trocariam e os graficos ficariam com a
     * cor clara — o caso mais grave e o "renda fixa", que no claro e
     * quase-preto e sumiria contra o fundo escuro.
     */
    private static void scenario2ChartColorsDiffer() {
        System.out.println("\n== Cenario 2: cores de grafico (Canvas) por tema ==");

        Theme light = Theme.LIGHT;
        Theme dark = Theme.DARK;

        checkDifferent("chartGrid", light.chartGrid(), dark.chartGrid());
        checkDifferent("chartAxisText", light.chartAxisText(), dark.chartAxisText());
        checkDifferent("chartLinePrimary", light.chartLinePrimary(), dark.chartLinePrimary());
        checkDifferent("chartLineSecondary", light.chartLineSecondary(), dark.chartLineSecondary());
        checkDifferent("accent", light.accent(), dark.accent());
        checkDifferent("accentStrong", light.accentStrong(), dark.accentStrong());
        checkDifferent("neutralWarn", light.neutralWarn(), dark.neutralWarn());
        checkDifferent("neutralWarnDark", light.neutralWarnDark(), dark.neutralWarnDark());
        checkDifferent("textFaint", light.textFaint(), dark.textFaint());
        checkDifferent("categoryStocks", light.categoryStocks(), dark.categoryStocks());
        checkDifferent("categoryFiis", light.categoryFiis(), dark.categoryFiis());
        checkDifferent("categoryFixedIncome", light.categoryFixedIncome(), dark.categoryFixedIncome());
        checkDifferent("categoryCrypto", light.categoryCrypto(), dark.categoryCrypto());
        checkDifferent("categoryForex", light.categoryForex(), dark.categoryForex());

        // O caso que mais importa: a categoria "Renda fixa" e #14181a no
        // claro (quase preto). No escuro ela precisa ser CLARA, senao o donut
        // e as barras perdem essa fatia inteira contra o fundo.
        check(light.categoryFixedIncome().getBrightness() < 0.2,
                "renda fixa e escura no tema claro (brilho " + light.categoryFixedIncome().getBrightness() + ")");
        check(dark.categoryFixedIncome().getBrightness() > 0.6,
                "renda fixa e clara no tema escuro (brilho " + dark.categoryFixedIncome().getBrightness() + ")");

        // Area sob a linha e sempre o accent com 10% de opacidade (paleta.md,
        // "chart-area-fill") — derivada, nao um hex separado que poderia
        // divergir do accent.
        // Tolerancia de 1e-6, nao 1e-9: Color guarda os componentes como
        // float, entao 0.1 volta como 0.10000000149011612 — e o mesmo valor
        // que a constante Color.web("#3d9c78", 0.1) de antes produzia.
        check(Math.abs(light.chartArea().getOpacity() - 0.1) < 1e-6
                        && Math.abs(dark.chartArea().getOpacity() - 0.1) < 1e-6,
                "area sob a linha tem 10% de opacidade nos dois temas");
        check(sameRgb(light.chartArea(), light.accent()) && sameRgb(dark.chartArea(), dark.accent()),
                "area sob a linha usa o accent do proprio tema");
    }

    // =====================================================================
    // Cenario 3 — valor persistido em settings
    // =====================================================================

    private static void scenario3SettingValueRoundTrip() {
        System.out.println("\n== Cenario 3: conversao para/de settings ==");

        check(ThemeManager.fromSettingValue("Escuro") == Theme.DARK, "\"Escuro\" -> DARK");
        check(ThemeManager.fromSettingValue("Claro") == Theme.LIGHT, "\"Claro\" -> LIGHT");
        // Banco novo/valor corrompido nunca pode quebrar a abertura do app.
        check(ThemeManager.fromSettingValue(null) == Theme.LIGHT, "null (setting ausente) -> LIGHT");
        check(ThemeManager.fromSettingValue("") == Theme.LIGHT, "\"\" -> LIGHT");
        check(ThemeManager.fromSettingValue("escuro") == Theme.LIGHT, "\"escuro\" minusculo -> LIGHT (comparacao exata)");

        check(ThemeManager.fromSettingValue(ThemeManager.toSettingValue(Theme.DARK)) == Theme.DARK,
                "round-trip DARK");
        check(ThemeManager.fromSettingValue(ThemeManager.toSettingValue(Theme.LIGHT)) == Theme.LIGHT,
                "round-trip LIGHT");
    }

    // =====================================================================
    // Cenario 4 — troca da lista de stylesheets da Scene
    // =====================================================================

    private static void scenario4StylesheetSwap() throws Exception {
        System.out.println("\n== Cenario 4: troca de stylesheet em tempo de execucao ==");

        runOnFxAndWait(() -> {
            Scene scene = new Scene(new VBox(), 400, 300);

            ThemeManager.install(scene, Theme.LIGHT);
            check(ThemeManager.current() == Theme.LIGHT, "install(LIGHT) deixa o tema claro ativo");
            check(scene.getStylesheets().size() == 1, "tema claro carrega 1 stylesheet");
            check(scene.getStylesheets().get(0).endsWith("theme.css"), "o unico stylesheet e o theme.css");

            ThemeManager.apply(Theme.DARK);
            check(scene.getStylesheets().size() == 2, "tema escuro carrega 2 stylesheets");
            check(scene.getStylesheets().get(0).endsWith("theme.css")
                            && scene.getStylesheets().get(1).endsWith("theme-dark.css"),
                    "theme-dark.css vem DEPOIS do theme.css (e o que faz ele vencer o cascade)");

            // Salvar duas vezes seguidas no tema escuro nao pode empilhar o
            // stylesheet (a lista e ordenada e aceitaria duplicata).
            ThemeManager.apply(Theme.DARK);
            check(scene.getStylesheets().size() == 2, "aplicar DARK duas vezes nao duplica o stylesheet");

            ThemeManager.apply(Theme.LIGHT);
            check(scene.getStylesheets().size() == 1, "voltar para LIGHT remove o theme-dark.css");

            // Abrir o app ja no escuro (App.start le settings antes do show).
            ThemeManager.install(scene, Theme.DARK);
            check(scene.getStylesheets().size() == 2, "install(DARK) ja abre com os 2 stylesheets");
            check(ThemeManager.current() == Theme.DARK, "install(DARK) deixa o tema escuro ativo");
        });
    }

    // =====================================================================
    // Cenario 5 — a cor renderizada realmente muda
    // =====================================================================

    /**
     * Os cenarios anteriores testam a mecanica (lista de stylesheets, valores
     * de token). Este e o unico que prova o efeito: um {@code Label} com a
     * classe {@code .kpi-value} (cujo {@code -fx-text-fill} e
     * {@code -fx-color-text-primary}) tem que sair escuro no tema claro e
     * claro no tema escuro, com o CSS resolvido de verdade pelo JavaFX.
     */
    private static void scenario5RenderedColorChanges() throws Exception {
        System.out.println("\n== Cenario 5: cor renderizada de um Label muda com o tema ==");

        runOnFxAndWait(() -> {
            Label kpi = new Label("R$ 15.757,58");
            kpi.getStyleClass().add("kpi-value");
            VBox card = new VBox(kpi);
            card.getStyleClass().add("content-card");
            VBox root = new VBox(card);
            Scene scene = new Scene(root, 400, 300);

            ThemeManager.install(scene, Theme.LIGHT);
            root.applyCss();
            Color lightText = (Color) kpi.getTextFill();
            System.out.println("  texto do KPI no tema claro:  " + toHex(lightText));

            ThemeManager.apply(Theme.DARK);
            root.applyCss();
            Color darkText = (Color) kpi.getTextFill();
            System.out.println("  texto do KPI no tema escuro: " + toHex(darkText));

            check(!sameRgb(lightText, darkText), "a cor do texto mudou ao trocar de tema");
            check(lightText.getBrightness() < 0.3, "texto escuro sobre fundo claro no tema claro");
            check(darkText.getBrightness() > 0.7, "texto claro sobre fundo escuro no tema escuro");

            // E volta: trocar de novo nao pode deixar residuo do tema escuro.
            ThemeManager.apply(Theme.LIGHT);
            root.applyCss();
            check(sameRgb((Color) kpi.getTextFill(), lightText), "voltar para o tema claro restaura a cor original");
        });
    }

    // =====================================================================
    // Cenario 6 — legibilidade de campo de formulario no tema escuro
    // =====================================================================

    /**
     * Guarda de regressao para um bug real do tema escuro: o placeholder dos
     * campos saia quase preto.
     *
     * <p>Causa: {@code -fx-prompt-text-fill} do Modena e
     * {@code derive(-fx-control-inner-background, -30%)} — "o proprio fundo,
     * 30% mais escuro". Sobre o branco do tema claro isso da um cinza claro
     * correto por acaso; sobre o fundo escuro dava quase preto. A correcao foi
     * declarar {@code -fx-prompt-text-fill} explicitamente via token, em vez de
     * herdar a derivacao do Modena.</p>
     *
     * <p>O teste nao mede o {@code promptTextFill} renderizado porque ele vive
     * no {@code TextInputControlSkin}, sem API publica no {@code TextField}.
     * Em vez disso afirma a <b>hierarquia de brilho</b> dos 4 tokens
     * envolvidos, que e o que o bug quebrava: fundo &lt; placeholder &lt;
     * texto digitado &lt; texto de maior enfase.</p>
     */
    private static void scenario6FormFieldLegibilityInDark() throws IOException {
        System.out.println("\n== Cenario 6: campo de formulario legivel no tema escuro ==");

        double surface = brightnessOf("/theme-dark.css", "-fx-color-bg-surface");
        double placeholder = brightnessOf("/theme-dark.css", "-fx-color-text-placeholder");
        double typed = brightnessOf("/theme-dark.css", "-fx-color-text-disabled");
        double primary = brightnessOf("/theme-dark.css", "-fx-color-text-primary");

        System.out.println("  brilho: fundo=" + round(surface) + " placeholder=" + round(placeholder)
                + " digitado=" + round(typed) + " primario=" + round(primary));

        // O bug: placeholder mais escuro que o proprio fundo do campo.
        check(placeholder > surface + 0.3,
                "placeholder e bem mais claro que o fundo do campo (era quase preto sobre fundo escuro)");
        check(placeholder > 0.6, "placeholder e claro o bastante para ser lido (brilho " + round(placeholder) + ")");
        // O texto que o usuario digitou nunca pode ser menos visivel que a
        // dica que ele substituiu.
        check(typed > placeholder, "texto digitado e mais claro que o placeholder");
        check(primary > typed, "texto de maior enfase continua acima do texto de campo");

        // No tema claro a ordem e a inversa (texto escuro sobre fundo claro) —
        // confirma que o par nao foi copiado do escuro por engano.
        double lightSurface = brightnessOf("/theme.css", "-fx-color-bg-surface");
        double lightPlaceholder = brightnessOf("/theme.css", "-fx-color-text-placeholder");
        check(lightPlaceholder < lightSurface - 0.2,
                "no tema claro o placeholder e mais escuro que o fundo branco");
    }

    private static double brightnessOf(String resource, String token) throws IOException {
        String value = colorValue(resource, token);
        if (value.isEmpty()) {
            throw new IOException("token " + token + " nao encontrado em " + resource);
        }
        return Color.web(value).getBrightness();
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }

    // =====================================================================
    // Apoio
    // =====================================================================

    /** Nomes de token {@code -fx-color-*} declarados no bloco {@code .root} de um stylesheet. */
    private static Set<String> colorTokensOfRootBlock(String resource) throws IOException {
        Set<String> tokens = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("(-fx-color-[a-z0-9-]+)\\s*:").matcher(rootBlock(resource));
        while (matcher.find()) {
            tokens.add(matcher.group(1));
        }
        return tokens;
    }

    private static String colorValue(String resource, String token) throws IOException {
        Matcher matcher = Pattern.compile(Pattern.quote(token) + "\\s*:\\s*([^;]+);").matcher(rootBlock(resource));
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    /**
     * Conteudo do primeiro bloco {@code .root { ... }} do arquivo. Extrai so
     * esse bloco de proposito: os tokens fora dele (nenhum hoje) nao fariam
     * parte do contrato de paridade entre os temas.
     */
    private static String rootBlock(String resource) throws IOException {
        try (InputStream is = ThemeManagerManualTest.class.getResourceAsStream(resource)) {
            if (is == null) {
                throw new IOException("stylesheet nao encontrado no classpath: " + resource);
            }
            String css = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            Matcher matcher = Pattern.compile("\\.root\\s*\\{([^}]*)}", Pattern.DOTALL).matcher(css);
            if (!matcher.find()) {
                throw new IOException("bloco .root nao encontrado em " + resource);
            }
            return matcher.group(1);
        }
    }

    private static boolean sameRgb(Color a, Color b) {
        return toHex(a).equals(toHex(b));
    }

    private static String toHex(Color color) {
        return String.format("#%02x%02x%02x",
                (int) Math.round(color.getRed() * 255),
                (int) Math.round(color.getGreen() * 255),
                (int) Math.round(color.getBlue() * 255));
    }

    private static void checkDifferent(String name, Color light, Color dark) {
        check(!sameRgb(light, dark), name + " difere entre os temas (" + toHex(light) + " -> " + toHex(dark) + ")");
    }

    private static void check(boolean condition, String description) {
        if (condition) {
            System.out.println("  [ok] " + description);
        } else {
            System.out.println("  [FALHOU] " + description);
            failures++;
        }
    }

    /**
     * Roda na FX Application Thread e espera terminar — {@code Scene}/
     * {@code applyCss} so podem ser tocados de la.
     */
    private static void runOnFxAndWait(Runnable action) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Throwable[] error = new Throwable[1];
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                error[0] = t;
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(30, TimeUnit.SECONDS)) {
            throw new IllegalStateException("timeout esperando a FX Application Thread");
        }
        if (error[0] != null) {
            throw new IllegalStateException(error[0]);
        }
    }

    private ThemeManagerManualTest() {
    }
}
