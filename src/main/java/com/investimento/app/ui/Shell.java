package com.investimento.app.ui;

import com.investimento.app.api.brapi.BrapiClient;
import com.investimento.app.api.coingecko.CoinGeckoClient;
import com.investimento.app.api.hgbrasil.HgBrasilClient;
import com.investimento.app.repository.AssetRepository;
import com.investimento.app.repository.DistributionRepository;
import com.investimento.app.repository.PortfolioSnapshotRepository;
import com.investimento.app.repository.QuoteHistoryRepository;
import com.investimento.app.repository.RateHistoryRepository;
import com.investimento.app.repository.SettingRepository;
import com.investimento.app.service.AssetService;
import com.investimento.app.service.BackupService;
import com.investimento.app.service.IncomeTaxService;
import com.investimento.app.service.MarketService;
import com.investimento.app.service.PositionService;
import com.investimento.app.service.TransactionService;
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
 *
 * <p>Recebe do {@code App} (composition root, ATV-12) as dependencias que as
 * telas reais precisam - {@code DashboardView} (ATV-12) e {@code
 * RegistrationView} (ATV-13); as demais 5 telas continuam stubs sem
 * dependencia (ATV-14 em diante passam a receber o que precisarem, do mesmo
 * jeito).</p>
 */
public class Shell extends BorderPane {

    private final Map<Screen, ScreenView> views = new EnumMap<>(Screen.class);
    private final Sidebar sidebar;

    public Shell(MarketService marketService,
                 PositionService positionService,
                 AssetService assetService,
                 TransactionService transactionService,
                 IncomeTaxService incomeTaxService,
                 HgBrasilClient hgBrasilClient,
                 BrapiClient brapiClient,
                 CoinGeckoClient coinGeckoClient,
                 AssetRepository assetRepository,
                 PortfolioSnapshotRepository portfolioSnapshotRepository,
                 RateHistoryRepository rateHistoryRepository,
                 QuoteHistoryRepository quoteHistoryRepository,
                 DistributionRepository distributionRepository,
                 SettingRepository settingRepository,
                 BackupService backupService,
                 Screen initialScreen,
                 Runnable onDataRestored) {
        views.put(Screen.DASHBOARD, new DashboardView(marketService, positionService, assetRepository,
                portfolioSnapshotRepository, rateHistoryRepository, settingRepository, this::select));
        views.put(Screen.STOCKS_FIIS, new StocksFiisView(positionService, assetRepository, quoteHistoryRepository,
                distributionRepository, marketService));
        views.put(Screen.FIXED_INCOME, new FixedIncomeView(positionService, marketService));
        views.put(Screen.FOREX_CRYPTO, new ForexCryptoView(positionService, assetRepository,
                quoteHistoryRepository, marketService, settingRepository));
        views.put(Screen.REGISTRATION, new RegistrationView(assetService, transactionService, brapiClient, coinGeckoClient));
        views.put(Screen.TAX_HISTORY, new TaxHistoryView(transactionService, incomeTaxService, positionService, assetService));
        views.put(Screen.SETTINGS, new SettingsView(settingRepository, backupService, onDataRestored,
                hgBrasilClient, brapiClient, coinGeckoClient, marketService));

        sidebar = new Sidebar(this::select);
        setLeft(sidebar);

        select(initialScreen != null ? initialScreen : Screen.DASHBOARD);
    }

    private void select(Screen screen) {
        setCenter(views.get(screen).getRoot());
        views.get(screen).onShow();
        sidebar.markActive(screen);
    }
}
