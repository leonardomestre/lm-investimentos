package com.investimento.app.ui;

/**
 * As 7 telas navegaveis do app. Ordem e agrupamento copiados exatamente do
 * template ({@code documentação/templates/Telas.dc.html}, linhas 36-47) -
 * nao reordenar por conta propria.
 */
public enum Screen {

    DASHBOARD("Dashboard", "PORTFOLIO"),
    STOCKS_FIIS("Ações e FIIs", "PORTFOLIO"),
    FIXED_INCOME("Renda Fixa", "PORTFOLIO"),
    FOREX_CRYPTO("Câmbio e Cripto", "PORTFOLIO"),
    REGISTRATION("Cadastro e transações", "MANAGEMENT"),
    TAX_HISTORY("Histórico e IR", "MANAGEMENT"),
    SETTINGS("Configurações", "MANAGEMENT");

    /** Rotulo em portugues, exibido na sidebar (UI e' PT-BR). */
    public final String label;

    /** "PORTFOLIO" ou "MANAGEMENT" - separa os 2 blocos da sidebar. */
    public final String group;

    Screen(String label, String group) {
        this.label = label;
        this.group = group;
    }
}
