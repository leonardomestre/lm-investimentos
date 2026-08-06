package com.investimento.app;

import com.investimento.app.api.brapi.BrapiClient;
import com.investimento.app.api.brapi.BrapiClientImpl;
import com.investimento.app.api.coingecko.CoinGeckoClient;
import com.investimento.app.api.coingecko.CoinGeckoClientImpl;
import com.investimento.app.api.hgbrasil.HgBrasilClient;
import com.investimento.app.api.hgbrasil.HgBrasilClientImpl;
import com.investimento.app.data.Database;
import com.investimento.app.repository.AssetRepository;
import com.investimento.app.repository.AssetRepositoryImpl;
import com.investimento.app.repository.DistributionRepository;
import com.investimento.app.repository.DistributionRepositoryImpl;
import com.investimento.app.repository.IndicatorHistoryRepository;
import com.investimento.app.repository.IndicatorHistoryRepositoryImpl;
import com.investimento.app.repository.PortfolioSnapshotRepository;
import com.investimento.app.repository.PortfolioSnapshotRepositoryImpl;
import com.investimento.app.repository.QuoteHistoryRepository;
import com.investimento.app.repository.QuoteHistoryRepositoryImpl;
import com.investimento.app.repository.RateHistoryRepository;
import com.investimento.app.repository.RateHistoryRepositoryImpl;
import com.investimento.app.repository.SettingRepository;
import com.investimento.app.repository.SettingRepositoryImpl;
import com.investimento.app.repository.TransactionRepository;
import com.investimento.app.repository.TransactionRepositoryImpl;
import com.investimento.app.service.ApiKeyResolver;
import com.investimento.app.service.AssetService;
import com.investimento.app.service.AssetServiceImpl;
import com.investimento.app.service.BackupService;
import com.investimento.app.service.BackupServiceImpl;
import com.investimento.app.service.IncomeTaxService;
import com.investimento.app.service.IncomeTaxServiceImpl;
import com.investimento.app.service.MarketService;
import com.investimento.app.service.MarketServiceImpl;
import com.investimento.app.service.PositionService;
import com.investimento.app.service.PositionServiceImpl;
import com.investimento.app.service.TransactionService;
import com.investimento.app.service.TransactionServiceImpl;
import com.investimento.app.ui.CurrencyDisplay;
import com.investimento.app.ui.Screen;
import com.investimento.app.ui.Shell;
import com.investimento.app.ui.Theme;
import com.investimento.app.ui.ThemeManager;
import com.investimento.app.ui.screens.SettingsView;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.text.Font;
import javafx.stage.Stage;

import java.io.InputStream;
import java.sql.Connection;

/**
 * Ponto de entrada do app: carrega fontes, monta o {@link Shell} (sidebar +
 * area de conteudo navegavel - ATV-07) e aplica o theme.css.
 *
 * <p>Tambem funciona como composition root (ATV-12): monta os 3 clientes de
 * API, os repositories e os services (sem framework de DI - projeto nao usa
 * um) e injeta no {@link Shell}, que repassa para as telas reais que
 * precisarem.</p>
 *
 * <p>ATV-18: a chave da HG Brasil/token da brapi.dev agora sao resolvidos via
 * {@link ApiKeyResolver} (prioridade {@code settings} &gt; variavel de
 * ambiente &gt; fallback do projeto) — ver {@code SettingsView}. Tambem expoe
 * {@link #rebuildShell(Stage)}, usado como callback pela tela de
 * Configuracoes depois de um {@code BackupService.restoreBackup} bem
 * sucedido: como {@code restoreBackup} fecha/reabre a {@link Connection}
 * unica de {@link Database} (ATV-19), qualquer repository construido com a
 * conexao antiga fica obsoleto — reconstruir o {@link Shell} inteiro com uma
 * conexao nova evita exigir reinicio manual do processo.</p>
 */
public class App extends Application {

    private static final String[] FONT_FILES = {
        "/fonts/Manrope-Regular.ttf",
        "/fonts/Manrope-Medium.ttf",
        "/fonts/Manrope-SemiBold.ttf",
        "/fonts/Manrope-Bold.ttf",
        "/fonts/Manrope-ExtraBold.ttf",
        "/fonts/IBMPlexMono-Regular.ttf",
        "/fonts/IBMPlexMono-Medium.ttf",
        "/fonts/IBMPlexMono-SemiBold.ttf",
    };

    @Override
    public void start(Stage stage) {
        // Abre/cria o banco SQLite local e garante o schema (ATV-02) -
        // precisa acontecer cedo, antes de qualquer tela real existir.
        Connection connection = Database.getConnection();

        loadFonts();

        Shell shell = buildShell(connection, Screen.DASHBOARD, () -> rebuildShell(stage));

        Scene scene = new Scene(shell, 1440, 900);
        // Tema lido de settings ANTES do primeiro show: abrir sempre no claro
        // e so depois trocar faria a janela piscar em branco para quem usa o
        // tema escuro. ThemeManager e quem monta a lista de stylesheets
        // (theme.css + theme-dark.css opcional) — nao adicionar theme.css
        // aqui por fora, senao ele entra duplicado.
        Theme theme = ThemeManager.fromSettingValue(
                new SettingRepositoryImpl(connection).get(SettingsView.SETTING_THEME, null));
        ThemeManager.install(scene, theme);

        stage.setTitle("Carteira — LM Investimentos");
        stage.setScene(scene);
        stage.show();
    }

    /**
     * Reconstroi o {@link Shell} inteiro com uma {@link Connection} nova
     * (chamado pela tela de Configuracoes depois de {@code
     * BackupService.restoreBackup}) e substitui a raiz da {@link Scene} ja
     * aberta — mantem o usuario na tela de Configuracoes em vez de voltar
     * para o Dashboard.
     */
    private void rebuildShell(Stage stage) {
        Connection connection = Database.getConnection();
        Shell newShell = buildShell(connection, Screen.SETTINGS, () -> rebuildShell(stage));
        stage.getScene().setRoot(newShell);
    }

    private Shell buildShell(Connection connection, Screen initialScreen, Runnable onDataRestored) {
        AssetRepository assetRepository = new AssetRepositoryImpl(connection);
        TransactionRepository transactionRepository = new TransactionRepositoryImpl(connection);
        QuoteHistoryRepository quoteHistoryRepository = new QuoteHistoryRepositoryImpl(connection);
        RateHistoryRepository rateHistoryRepository = new RateHistoryRepositoryImpl(connection);
        IndicatorHistoryRepository indicatorHistoryRepository = new IndicatorHistoryRepositoryImpl(connection);
        PortfolioSnapshotRepository portfolioSnapshotRepository = new PortfolioSnapshotRepositoryImpl(connection);
        DistributionRepository distributionRepository = new DistributionRepositoryImpl(connection);
        SettingRepository settingRepository = new SettingRepositoryImpl(connection);

        // ATV-18: settings (banco) > variavel de ambiente > fallback do
        // projeto - mesmos fallbacks ja usados pelos construtores sem
        // argumento de HgBrasilClientImpl/BrapiClientImpl (ATV-03/04).
        String hgBrasilKey = ApiKeyResolver.resolve(settingRepository, "hgbrasil.apiKey", "HG_BRASIL_KEY", "ee3b78db");
        String brapiToken = ApiKeyResolver.resolve(settingRepository, "brapi.token", "BRAPI_TOKEN", "jS1ByoxrxvQAaFBZmcZMU6");

        HgBrasilClient hgBrasilClient = new HgBrasilClientImpl(hgBrasilKey);
        BrapiClient brapiClient = new BrapiClientImpl(brapiToken);
        CoinGeckoClient coinGeckoClient = new CoinGeckoClientImpl();

        MarketService marketService = new MarketServiceImpl(hgBrasilClient, brapiClient, coinGeckoClient,
                rateHistoryRepository, indicatorHistoryRepository, quoteHistoryRepository, settingRepository);

        PositionService positionService = new PositionServiceImpl(assetRepository, transactionRepository,
                marketService, quoteHistoryRepository, rateHistoryRepository, indicatorHistoryRepository);

        AssetService assetService = new AssetServiceImpl(assetRepository, brapiClient, coinGeckoClient, marketService);
        TransactionService transactionService = new TransactionServiceImpl(transactionRepository, assetRepository);
        IncomeTaxService incomeTaxService = new IncomeTaxServiceImpl(assetRepository, positionService);
        BackupService backupService = new BackupServiceImpl();

        // Moeda principal de exibicao (Configuracoes > Preferencias) — resolve
        // a taxa uma vez aqui para as telas ja abrirem na moeda certa; o
        // Shell reconfigura a cada navegacao. Sem custo nenhum quando a moeda
        // e BRL (o padrao): nao ha chamada de rede. Ver CurrencyDisplay.
        CurrencyDisplay.configure(settingRepository, marketService);

        return new Shell(marketService, positionService, assetService, transactionService, incomeTaxService,
                hgBrasilClient, brapiClient, coinGeckoClient, assetRepository, portfolioSnapshotRepository,
                rateHistoryRepository, quoteHistoryRepository, distributionRepository, settingRepository,
                backupService, initialScreen, onDataRestored);
    }

    private void loadFonts() {
        for (String path : FONT_FILES) {
            try (InputStream is = getClass().getResourceAsStream(path)) {
                if (is == null) {
                    System.err.println("Fonte nao encontrada no classpath: " + path);
                    continue;
                }
                Font.loadFont(is, 0);
            } catch (Exception e) {
                System.err.println("Falha ao carregar fonte " + path + ": " + e.getMessage());
            }
        }
    }

    @Override
    public void stop() {
        Database.close();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
