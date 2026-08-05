package com.investimento.app.ui;

import com.investimento.app.ui.screens.DashboardView;
import com.investimento.app.ui.screens.FixedIncomeView;
import com.investimento.app.ui.screens.ForexCryptoView;
import com.investimento.app.ui.screens.RegistrationView;
import com.investimento.app.ui.screens.ScreenView;
import com.investimento.app.ui.screens.SettingsView;
import com.investimento.app.ui.screens.StocksFiisView;
import com.investimento.app.ui.screens.TaxHistoryView;
import javafx.scene.layout.BorderPane;

import java.util.EnumMap;
import java.util.Map;

/**
 * BorderPane raiz do app: sidebar fixa (left) + area de conteudo que troca de
 * tela (center) conforme o item de navegacao clicado.
 *
 * <p>As 7 {@code *View} sao instanciadas uma unica vez e reaproveitadas entre
 * trocas de tela ({@link #select(Screen)} so troca qual {@code Node} fica
 * visivel) - preserva estado de formulario/scroll quando as telas reais
 * forem implementadas nas proximas atividades.</p>
 */
public class Shell extends BorderPane {

    private final Map<Screen, ScreenView> views = new EnumMap<>(Screen.class);
    private final Sidebar sidebar;

    public Shell() {
        views.put(Screen.DASHBOARD, new DashboardView());
        views.put(Screen.STOCKS_FIIS, new StocksFiisView());
        views.put(Screen.FIXED_INCOME, new FixedIncomeView());
        views.put(Screen.FOREX_CRYPTO, new ForexCryptoView());
        views.put(Screen.REGISTRATION, new RegistrationView());
        views.put(Screen.TAX_HISTORY, new TaxHistoryView());
        views.put(Screen.SETTINGS, new SettingsView());

        sidebar = new Sidebar(this::select);
        setLeft(sidebar);

        select(Screen.DASHBOARD);
    }

    private void select(Screen screen) {
        setCenter(views.get(screen).getRoot());
        views.get(screen).onShow();
        sidebar.markActive(screen);
    }
}
