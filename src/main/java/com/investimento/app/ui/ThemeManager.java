package com.investimento.app.ui;

import javafx.scene.Scene;

/**
 * Tema ativo da aplicacao e a troca dele em tempo de execucao.
 *
 * <p><b>Mecanismo</b>: a {@link Scene} carrega sempre {@code /theme.css}
 * (estrutura + paleta clara). O tema escuro e o {@code /theme-dark.css}
 * adicionado <b>depois</b> na mesma lista — como as duas folhas declaram os
 * tokens {@code -fx-color-*} no mesmo seletor {@code .root}, a ultima vence o
 * desempate do cascade e a aplicacao inteira reciclando esses tokens muda de
 * cor sozinha. Trocar de tema e literalmente adicionar/remover uma entrada de
 * {@code scene.getStylesheets()}; nao ha reconstrucao de tela nem reinicio.</p>
 *
 * <p><b>Graficos ficam de fora desse mecanismo</b>: {@code Canvas} pinta com
 * {@link javafx.scene.paint.Color} de codigo Java, nao com CSS (ver
 * {@link Theme}). As {@code *View} leem {@link #current()} no momento de
 * desenhar, e o {@code Shell} chama {@code onShow()} — que redesenha a tela
 * inteira — a cada navegacao. Como o tema so muda pela tela de Configuracoes
 * (que nao tem grafico nenhum), qualquer grafico ja aparece com a cor nova na
 * primeira vez que o usuario volta para a tela dele. Se algum dia o tema
 * puder ser trocado de dentro de uma tela com {@code Canvas}, essa tela vai
 * precisar redesenhar explicitamente na hora da troca.</p>
 *
 * <p>Estado estatico porque o app tem uma unica janela/{@code Scene} (mesma
 * premissa que {@code App.start} ja assume) e porque as {@code *View}
 * precisam consultar o tema no meio do desenho, sem uma referencia de
 * instancia para carregar por todo o construtor de cada uma.</p>
 */
public final class ThemeManager {

    private static final String BASE_STYLESHEET = "/theme.css";
    private static final String DARK_STYLESHEET = "/theme-dark.css";

    /** Valores gravados em {@code settings} pela tela de Configuracoes. */
    private static final String SETTING_VALUE_DARK = "Escuro";
    private static final String SETTING_VALUE_LIGHT = "Claro";

    private static Theme current = Theme.LIGHT;
    private static Scene scene;

    private ThemeManager() {
    }

    /** Tema em vigor agora — consultado pelas {@code *View} ao desenhar graficos. */
    public static Theme current() {
        return current;
    }

    /**
     * Registra a {@link Scene} do app e aplica {@code theme} nela. Chamado uma
     * unica vez por {@code App.start}, ja com o tema lido de {@code settings}
     * — assim o app abre direto no tema escolhido, sem piscar no claro antes.
     */
    public static void install(Scene targetScene, Theme theme) {
        scene = targetScene;
        current = theme == null ? Theme.LIGHT : theme;
        scene.getStylesheets().setAll(resource(BASE_STYLESHEET));
        if (current == Theme.DARK) {
            scene.getStylesheets().add(resource(DARK_STYLESHEET));
        }
    }

    /**
     * Troca o tema em vigor. Sem efeito se a {@link Scene} ainda nao foi
     * registrada por {@link #install} — e o caso dos testes headless, que
     * constroem {@code *View} sem nenhuma {@code Scene}; ali o tema continua
     * valendo para as cores de grafico de {@link Theme}, so nao ha stylesheet
     * para trocar.
     */
    public static void apply(Theme theme) {
        current = theme == null ? Theme.LIGHT : theme;
        if (scene == null) {
            return;
        }
        String dark = resource(DARK_STYLESHEET);
        scene.getStylesheets().remove(dark);
        if (current == Theme.DARK) {
            scene.getStylesheets().add(dark);
        }
    }

    /** {@code "Escuro"} → {@link Theme#DARK}; qualquer outro valor → {@link Theme#LIGHT}. */
    public static Theme fromSettingValue(String value) {
        return SETTING_VALUE_DARK.equals(value) ? Theme.DARK : Theme.LIGHT;
    }

    /** Inverso de {@link #fromSettingValue} — o que vai para {@code settings}. */
    public static String toSettingValue(Theme theme) {
        return theme == Theme.DARK ? SETTING_VALUE_DARK : SETTING_VALUE_LIGHT;
    }

    private static String resource(String path) {
        return ThemeManager.class.getResource(path).toExternalForm();
    }
}
