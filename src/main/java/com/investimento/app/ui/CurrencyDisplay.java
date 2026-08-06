package com.investimento.app.ui;

import com.investimento.app.api.hgbrasil.model.Currency;
import com.investimento.app.api.hgbrasil.model.MacroSnapshot;
import com.investimento.app.repository.SettingRepository;
import com.investimento.app.service.MarketService;
import com.investimento.app.ui.screens.SettingsView;

import java.util.Arrays;
import java.util.List;

/**
 * Moeda principal de exibicao (Configuracoes &gt; Preferencias &gt; MOEDA
 * PRINCIPAL) e a conversao a partir do BRL.
 *
 * <h2>A conversao acontece so na exibicao — o calculo continua todo em BRL</h2>
 *
 * <p>Das duas alternativas que {@code documentacao/pendencias.md} deixava em
 * aberto ("converter em {@code PositionService}, uma vez no calculo" ou "so na
 * camada de exibicao, mantendo o calculo interno sempre em BRL"), esta e a
 * segunda. Motivos:</p>
 *
 * <ul>
 *   <li>As 3 fontes de cotacao do app so devolvem preco em BRL (brapi.dev, HG
 *       Brasil, CoinGecko com {@code vs_currencies=brl}) e {@code transactions}
 *       nao tem coluna de moeda — o preco unitario que o usuario digitou e
 *       sempre BRL. O dado <b>nasce</b> em BRL; converter no calculo seria
 *       converter e desconverter.</li>
 *   <li>A taxa de cambio muda a cada atualizacao do painel macro. Se a
 *       conversao entrasse em {@code PositionService}, o custo medio e o
 *       ganho/perda mudariam junto com o dolar do dia — e o
 *       {@code portfolio_snapshot} gravado diariamente passaria a misturar
 *       moedas ao longo do tempo, sem nenhuma coluna que registre em qual
 *       moeda cada linha foi gravada.</li>
 *   <li>O relatorio de IR (RF07) tem que continuar em BRL de qualquer forma —
 *       os tetos de isencao de R$ 20.000/R$ 35.000 sao valores legais em
 *       real.</li>
 * </ul>
 *
 * <p>Ou seja: {@code PositionService}, {@code PortfolioSummary},
 * {@code portfolio_snapshot} e {@code IncomeTaxService} <b>nao mudaram nada</b>
 * — continuam em BRL. Quem converte e cada {@code *View}, no momento de
 * formatar, chamando {@link #convert(double)} e {@link #symbol()}.</p>
 *
 * <h2>Telas que ficam em BRL de proposito</h2>
 *
 * <ul>
 *   <li><b>Historico e IR</b>: e um relatorio fiscal brasileiro. A propria
 *       tela mostra os tetos "R$ 20.000/mes" e "R$ 35.000/mes" ao lado dos
 *       valores — converter os valores e deixar os tetos em real faria a tela
 *       se contradizer. O CSV exportado tambem fica em BRL.</li>
 *   <li><b>Cadastro e transacoes</b>: e entrada de dado. O usuario digita
 *       preco unitario e taxas em BRL (nao ha coluna de moeda em
 *       {@code transactions}); mostrar o total em outra moeda ao lado de
 *       campos que esperam real induziria a erro de digitacao.</li>
 * </ul>
 *
 * <p>As duas mostram um aviso explicito quando a moeda principal nao e o real,
 * em vez de deixar a inconsistencia silenciosa.</p>
 *
 * <p><b>Estado estatico</b> pelo mesmo motivo do {@link ThemeManager}: e uma
 * preferencia global de exibicao, consultada no meio da formatacao de 4 telas,
 * e o app tem uma unica janela. O default ({@link Primary#BRL}, taxa 1.0)
 * mantem os testes headless existentes — que constroem {@code *View}
 * diretamente, sem passar por {@link #configure} — funcionando sem alteracao
 * nenhuma.</p>
 */
public final class CurrencyDisplay {

    /**
     * Moedas oferecidas no dropdown. Sao as 8 que {@code AssetServiceImpl}
     * ja aceita como ativo de cambio (portanto garantidamente presentes em
     * {@code MacroSnapshot.currencies()}, a unica fonte de taxa do app), mais
     * o proprio real.
     *
     * <p>O {@code label} e o texto persistido em {@code settings} — "Real
     * (BRL)" e exatamente o valor que a ATV-18 ja gravava, entao um banco
     * existente continua carregando certo.</p>
     */
    public enum Primary {
        BRL("Real (BRL)", "BRL", "R$"),
        USD("Dólar norte-americano (USD)", "USD", "US$"),
        EUR("Euro (EUR)", "EUR", "€"),
        GBP("Libra esterlina (GBP)", "GBP", "£"),
        JPY("Iene japonês (JPY)", "JPY", "¥"),
        ARS("Peso argentino (ARS)", "ARS", "AR$"),
        AUD("Dólar australiano (AUD)", "AUD", "A$"),
        CAD("Dólar canadense (CAD)", "CAD", "C$"),
        CNY("Yuan chinês (CNY)", "CNY", "CN¥");

        private final String label;
        private final String iso;
        private final String symbol;

        Primary(String label, String iso, String symbol) {
            this.label = label;
            this.iso = iso;
            this.symbol = symbol;
        }

        /** Texto do dropdown e valor gravado em {@code settings}. */
        public String label() {
            return label;
        }

        /** Codigo ISO — chave em {@code MacroSnapshot.currencies()}. */
        public String iso() {
            return iso;
        }

        /** Simbolo exibido antes do valor ({@code R$}, {@code US$}, ...). */
        public String symbol() {
            return symbol;
        }

        /** Resolve pelo texto persistido; qualquer valor desconhecido vira {@link #BRL}. */
        public static Primary fromLabel(String label) {
            for (Primary candidate : values()) {
                if (candidate.label.equals(label)) {
                    return candidate;
                }
            }
            return BRL;
        }
    }

    private static Primary current = Primary.BRL;

    /** Quantos BRL vale 1 unidade de {@link #current}. Sempre 1.0 para o real. */
    private static double brlPerUnit = 1.0;

    /** Verdadeiro quando a moeda escolhida nao e BRL mas a taxa nao pode ser obtida. */
    private static boolean rateUnavailable = false;

    private CurrencyDisplay() {
    }

    /**
     * Le a moeda principal de {@code settings} e resolve a taxa de conversao.
     *
     * <p>Chamado no composition root ({@code App.buildShell}), a cada
     * navegacao ({@code Shell.select}, para uma troca de moeda valer sem
     * reiniciar) e logo apos salvar em Configuracoes.</p>
     *
     * <p><b>Com a moeda em BRL — o padrao — nao ha chamada de rede nenhuma</b>:
     * o metodo devolve na hora, sem tocar o {@link MarketService}. So quando a
     * moeda e outra ele consulta {@code getMacroSnapshot()}, que tem cache de 5
     * minutos em memoria (ATV-06) — a chamada e sincrona na FX thread, mesmo
     * precedente ja aceito em {@code DashboardView.refresh()} e no botao
     * "Recalcular projecoes" da ATV-15.</p>
     *
     * <p>Se a taxa nao vier (API fora do ar, ISO ausente do snapshot), cai
     * para BRL em vez de exibir valor convertido por uma taxa inventada, e
     * marca {@link #isRateUnavailable()} para a tela de Configuracoes avisar.</p>
     */
    public static void configure(SettingRepository settingRepository, MarketService marketService) {
        Primary chosen = settingRepository == null
                ? Primary.BRL
                : Primary.fromLabel(settingRepository.get(SettingsView.SETTING_PRIMARY_CURRENCY, null));

        if (chosen == Primary.BRL || marketService == null) {
            current = Primary.BRL;
            brlPerUnit = 1.0;
            rateUnavailable = false;
            return;
        }

        Double rate = resolveRate(marketService, chosen.iso());
        if (rate == null || rate <= 0) {
            // Sem taxa confiavel, exibir em BRL e melhor do que exibir um
            // numero convertido por uma taxa antiga/inventada com o simbolo
            // de outra moeda na frente.
            current = Primary.BRL;
            brlPerUnit = 1.0;
            rateUnavailable = true;
            return;
        }
        current = chosen;
        brlPerUnit = rate;
        rateUnavailable = false;
    }

    private static Double resolveRate(MarketService marketService, String iso) {
        MacroSnapshot snapshot = marketService.getMacroSnapshot();
        if (snapshot == null || snapshot.currencies() == null) {
            return null;
        }
        Currency currency = snapshot.currencies().get(iso);
        return currency == null ? null : currency.buy();
    }

    /** Moeda de exibicao em vigor. */
    public static Primary current() {
        return current;
    }

    /** Simbolo a colocar antes do valor — {@code "R$"}, {@code "US$"}, ... */
    public static String symbol() {
        return current.symbol();
    }

    /** Codigo ISO em vigor — usado em rotulo de coluna ({@code "ATUAL (US$)"}). */
    public static String code() {
        return current.iso();
    }

    /** {@code true} quando nada precisa ser convertido (moeda principal e o real). */
    public static boolean isBrl() {
        return current == Primary.BRL;
    }

    /**
     * A moeda escolhida nao pode ser aplicada porque a taxa de cambio nao
     * estava disponivel — a exibicao caiu de volta para BRL.
     */
    public static boolean isRateUnavailable() {
        return rateUnavailable;
    }

    /** Quantos BRL vale 1 unidade da moeda em vigor (1.0 para o real). */
    public static double rate() {
        return brlPerUnit;
    }

    /**
     * Converte um valor <b>em BRL</b> (todo valor calculado pelo app) para a
     * moeda de exibicao. Identidade quando a moeda e o real.
     */
    public static double convert(double brlValue) {
        return brlPerUnit == 1.0 ? brlValue : brlValue / brlPerUnit;
    }

    /** Rotulos do dropdown de Configuracoes, na ordem do enum. */
    public static List<String> labels() {
        return Arrays.stream(Primary.values()).map(Primary::label).toList();
    }

    /**
     * Reseta para o padrao (BRL, taxa 1.0). Existe para os testes headless
     * poderem isolar um cenario do outro — o app nunca chama isto.
     */
    static void resetForTest() {
        current = Primary.BRL;
        brlPerUnit = 1.0;
        rateUnavailable = false;
    }

    /**
     * Fixa a moeda e a taxa sem passar por {@code settings}/{@code MarketService}.
     * Seam de teste (mesmo padrao dos {@code *ForTest} das ATV-13 a 18) — o
     * app sempre usa {@link #configure}.
     */
    static void setForTest(Primary primary, double brlPerUnitRate) {
        current = primary;
        brlPerUnit = brlPerUnitRate;
        rateUnavailable = false;
    }
}
