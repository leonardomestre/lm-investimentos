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
import com.investimento.app.repository.IndicatorHistoryRepository;
import com.investimento.app.repository.IndicatorHistoryRepositoryImpl;
import com.investimento.app.repository.PortfolioSnapshotRepository;
import com.investimento.app.repository.PortfolioSnapshotRepositoryImpl;
import com.investimento.app.repository.QuoteHistoryRepository;
import com.investimento.app.repository.QuoteHistoryRepositoryImpl;
import com.investimento.app.repository.RateHistoryRepository;
import com.investimento.app.repository.RateHistoryRepositoryImpl;
import com.investimento.app.repository.TransactionRepository;
import com.investimento.app.repository.TransactionRepositoryImpl;
import com.investimento.app.service.MarketService;
import com.investimento.app.service.MarketServiceImpl;
import com.investimento.app.service.PositionService;
import com.investimento.app.service.PositionServiceImpl;
import com.investimento.app.ui.Shell;
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
 * precisarem (por enquanto so {@code DashboardView}).</p>
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

        Shell shell = buildShell(connection);

        Scene scene = new Scene(shell, 1440, 900);
        scene.getStylesheets().add(getClass().getResource("/theme.css").toExternalForm());

        stage.setTitle("Carteira — LM Investimentos");
        stage.setScene(scene);
        stage.show();
    }

    private Shell buildShell(Connection connection) {
        AssetRepository assetRepository = new AssetRepositoryImpl(connection);
        TransactionRepository transactionRepository = new TransactionRepositoryImpl(connection);
        QuoteHistoryRepository quoteHistoryRepository = new QuoteHistoryRepositoryImpl(connection);
        RateHistoryRepository rateHistoryRepository = new RateHistoryRepositoryImpl(connection);
        IndicatorHistoryRepository indicatorHistoryRepository = new IndicatorHistoryRepositoryImpl(connection);
        PortfolioSnapshotRepository portfolioSnapshotRepository = new PortfolioSnapshotRepositoryImpl(connection);

        HgBrasilClient hgBrasilClient = new HgBrasilClientImpl();
        BrapiClient brapiClient = new BrapiClientImpl();
        CoinGeckoClient coinGeckoClient = new CoinGeckoClientImpl();

        MarketService marketService = new MarketServiceImpl(hgBrasilClient, brapiClient, coinGeckoClient,
                rateHistoryRepository, indicatorHistoryRepository, quoteHistoryRepository);

        PositionService positionService = new PositionServiceImpl(assetRepository, transactionRepository,
                marketService, quoteHistoryRepository, rateHistoryRepository, indicatorHistoryRepository);

        return new Shell(marketService, positionService, assetRepository, portfolioSnapshotRepository,
                rateHistoryRepository);
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
