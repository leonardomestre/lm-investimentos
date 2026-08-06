package com.investimento.app.ui.screens;

import com.investimento.app.api.brapi.BrapiClient;
import com.investimento.app.api.coingecko.CoinGeckoClient;
import com.investimento.app.api.hgbrasil.HgBrasilClient;
import com.investimento.app.dto.SyncEvent;
import com.investimento.app.repository.SettingRepository;
import com.investimento.app.service.BackupService;
import com.investimento.app.service.MarketService;
import com.investimento.app.ui.CurrencyDisplay;
import com.investimento.app.ui.Theme;
import com.investimento.app.ui.ThemeManager;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Tela Configurações (ATV-18, planejamento 5.7) — substitui o stub da
 * ATV-07. 4 blocos exigidos pelo "Conteúdo" da atividade: (1) chave da HG
 * Brasil + token da brapi.dev, persistidos em {@code settings}
 * ({@link SettingRepository}, ATV-02); (2) intervalo de atualização
 * automática de cotações (minutos), lido pelo {@code MarketService}
 * (ATV-06/ATV-18) em vez do TTL fixo; (3) backup/restauração
 * ({@link BackupService}, ATV-19); (4) zona de perigo "Apagar todos os
 * dados", com confirmação reforçada (digitar a palavra {@value #CONFIRM_WORD}).
 *
 * <p><b>Chaves não ficam em texto plano visível por padrão</b> (RT05) — os
 * campos de HG Brasil/brapi.dev usam {@link PasswordField} com um botão
 * "Mostrar/Ocultar" que alterna para um {@link TextField} equivalente
 * (JavaFX não tem um "reveal" nativo em {@code PasswordField}).</p>
 *
 * <p><b>Reinício necessário para novas chaves de API</b>: seguindo o próprio
 * critério de aceite da atividade ("trocar a chave... e reiniciar o app usa
 * a nova chave"), salvar aqui só grava em {@code settings} — o
 * {@code HgBrasilClient}/{@code BrapiClient} usados pelo resto do app só são
 * reconstruídos no próximo {@code App.buildShell} (próxima abertura do
 * app). Já o <b>intervalo de atualização</b> passa a valer imediatamente,
 * sem reinício, porque {@code MarketServiceImpl} lê {@code settings} a cada
 * verificação de TTL (não guarda o valor em cache na inicialização).</p>
 */
public class SettingsView implements ScreenView {

    static final String SETTING_HGBRASIL_API_KEY = "hgbrasil.apiKey";
    static final String SETTING_BRAPI_TOKEN = "brapi.token";
    static final String SETTING_UPDATE_INTERVAL_MINUTES = "updateIntervalMinutes";

    // Preferencias (bloco "Preferências" do template, Telas.dc.html linhas
    // 1256-1271) - persistidas em settings como as demais chaves desta tela,
    // mas SEM nenhuma tela do app lendo esses valores ainda (nenhuma
    // atividade cabeou "moeda principal"/"tema"/"periodo padrao dos
    // graficos"/"casas decimais em cripto"/"ocultar valores" em nenhum outro
    // lugar) - documentado aqui em vez de escondido, mesma categoria de
    // limitacao ja registrada para LCI/LCA na ATV-15. Se uma atividade futura
    // precisar que esses valores realmente afetem outra tela, o dado ja esta
    // disponivel em settings, so falta o consumidor.
    // Publicas (as demais desta tela sao package-private) porque o App
    // precisa ler as duas ANTES de montar o Shell: o tema define os
    // stylesheets da Scene e a moeda define a formatacao de todas as telas —
    // as duas valem no primeiro frame, nao so quando o usuario abre
    // Configuracoes.
    public static final String SETTING_PRIMARY_CURRENCY = "preferences.primaryCurrency";
    public static final String SETTING_THEME = "preferences.theme";
    static final String SETTING_DEFAULT_CHART_PERIOD = "preferences.defaultChartPeriod";
    static final String SETTING_CRYPTO_DECIMALS = "preferences.cryptoDecimals";
    static final String SETTING_HIDE_DASHBOARD_VALUES = "preferences.hideDashboardValues";

    // Toggles do card "Atualização de cotações" (Telas.dc.html linhas
    // 1227-1240) - mesma limitação já documentada acima para "preferences.*":
    // persistidos, mas nenhuma outra parte do app lê esses 3 valores ainda.
    static final String SETTING_SNAPSHOT_ENABLED = "updateSchedule.snapshotEnabled";
    static final String SETTING_PAUSE_WEEKENDS = "updateSchedule.pauseWeekends";
    static final String SETTING_ALERT_ON_FAILURE = "updateSchedule.alertOnFailure";

    private static final String CONFIRM_WORD = "APAGAR";

    private final SettingRepository settingRepository;
    private final BackupService backupService;
    private final Runnable onDataRestored;
    private final HgBrasilClient hgBrasilClient;
    private final BrapiClient brapiClient;
    private final CoinGeckoClient coinGeckoClient;
    private final MarketService marketService;

    private final VBox root;
    private final Label statusLabel = new Label(" ");
    private final VBox syncTableBody = new VBox();

    private final SecretField hgBrasilKeyField = new SecretField("hgBrasilKey");
    private final SecretField brapiTokenField = new SecretField("brapiToken");
    private final TextField updateIntervalField = new TextField();
    private final Label updateIntervalErrorLabel = new Label();

    private final ComboBox<String> primaryCurrencyCombo = new ComboBox<>();
    private final ToggleSwitchPair themeToggle = new ToggleSwitchPair("Claro", "Escuro");
    private final ComboBox<String> defaultChartPeriodCombo = new ComboBox<>();
    private final ComboBox<Integer> cryptoDecimalsCombo = new ComboBox<>();
    private final ToggleSwitch hideDashboardValuesToggle = new ToggleSwitch();

    private final ToggleSwitch snapshotEnabledToggle = new ToggleSwitch();
    private final ToggleSwitch pauseWeekendsToggle = new ToggleSwitch();
    private final ToggleSwitch alertOnFailureToggle = new ToggleSwitch();

    public SettingsView(SettingRepository settingRepository, BackupService backupService, Runnable onDataRestored,
                         HgBrasilClient hgBrasilClient, BrapiClient brapiClient, CoinGeckoClient coinGeckoClient,
                         MarketService marketService) {
        this.settingRepository = settingRepository;
        this.backupService = backupService;
        this.onDataRestored = onDataRestored;
        this.hgBrasilClient = hgBrasilClient;
        this.brapiClient = brapiClient;
        this.coinGeckoClient = coinGeckoClient;
        this.marketService = marketService;

        HBox header = buildHeader();
        VBox apiKeysCard = buildApiKeysCard();
        VBox updateIntervalCard = buildUpdateIntervalCard();
        VBox preferencesCard = buildPreferencesCard();
        VBox backupCard = buildBackupCard();

        // Layout de 2 colunas na 2a linha (Telas.dc.html linha 1202:
        // grid-template-columns:1.05fr 1fr) - "Atualização de cotações" à
        // esquerda (mais largo), "Preferências" + "Backup e restauração"
        // empilhados à direita - diferente do empilhamento vertical simples
        // que a tela tinha antes.
        VBox rightColumn = new VBox(16, preferencesCard, backupCard);
        GridPane secondRow = twoColumnRow(updateIntervalCard, rightColumn, 51.2, 48.8);

        VBox contentBody = new VBox(20, apiKeysCard, secondRow);
        contentBody.setPadding(new Insets(24, 28, 28, 28));

        root = new VBox(header, contentBody);
        root.setStyle("-fx-background-color: -fx-color-bg-shell;");

        loadFromSettings();
    }

    @Override
    public Parent getRoot() {
        return root;
    }

    @Override
    public void onShow() {
        loadFromSettings();
    }

    // =====================================================================
    // Header
    // =====================================================================

    private HBox buildHeader() {
        Label title = new Label("Configurações");
        title.getStyleClass().add("header-title");
        Label subtitle = new Label("chaves de API, frequência de atualização, backup e zona de perigo");
        subtitle.getStyleClass().add("header-subtitle");
        VBox titleBox = new VBox(4, title, subtitle);
        HBox.setHgrow(titleBox, Priority.ALWAYS);

        Button discardButton = new Button("Descartar");
        discardButton.setId("discardSettingsButton");
        discardButton.getStyleClass().add("pill-secondary");
        discardButton.setOnAction(e -> discardChanges());

        Button saveButton = new Button("Salvar alterações");
        saveButton.setId("saveSettingsButton");
        saveButton.getStyleClass().add("button-primary");
        saveButton.setOnAction(e -> saveSettings());

        HBox actions = new HBox(10, discardButton, saveButton);
        actions.setAlignment(Pos.CENTER_RIGHT);

        HBox header = new HBox(20, titleBox, actions);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("header-bar");
        return header;
    }

    // =====================================================================
    // Bloco 1 — Chaves de API (HG Brasil / brapi.dev)
    // =====================================================================

    /**
     * 3 caixas (brapi.dev / HG Brasil / CoinGecko), mesma grade
     * {@code repeat(3,1fr)} do template (Telas.dc.html linhas 1160-1200) — o
     * badge "X de 3 disponíveis" e o status "Conectada"/"Sem chave
     * configurada" de cada caixa são derivados só do estado local já
     * persistido (campo em branco ou não), **sem** nenhuma chamada de rede —
     * o botão "Testar conexão" (e a barra de uso/latência do mockup, que
     * exigiria telemetria que o app não coleta hoje) fica só como estrutura
     * visual por enquanto, deliberadamente sem verificação real ainda.
     */
    private VBox buildApiKeysCard() {
        Label title = new Label("Chaves de API");
        title.getStyleClass().add("content-card-title");
        Label subtitle = new Label("armazenadas apenas no seu banco local — prioridade: aqui > variável de ambiente > padrão do projeto");
        subtitle.getStyleClass().add("content-card-subtitle");
        subtitle.setWrapText(true);
        VBox titleBox = new VBox(4, title, subtitle);
        HBox.setHgrow(titleBox, Priority.ALWAYS);

        Label connectedBadge = new Label();
        connectedBadge.setId("apiConnectedBadge");
        connectedBadge.setStyle("-fx-background-color: -fx-color-gain-bg; -fx-background-radius: 20; -fx-padding: 5 10;"
                + " -fx-font-family: 'Manrope'; -fx-font-weight: bold; -fx-font-size: 11px; -fx-text-fill: -fx-color-accent-strong;");

        HBox headerRow = new HBox(titleBox, connectedBadge);
        headerRow.setAlignment(Pos.CENTER_LEFT);

        ApiStatusIndicator brapiStatus = new ApiStatusIndicator();
        ApiStatusIndicator hgBrasilStatus = new ApiStatusIndicator();
        ApiStatusIndicator coinGeckoStatus = new ApiStatusIndicator();
        coinGeckoStatus.setNeutral("Plano público");

        Runnable refreshStatuses = () -> {
            boolean brapiOk = brapiTokenField.getValue() != null && !brapiTokenField.getValue().isBlank();
            boolean hgOk = hgBrasilKeyField.getValue() != null && !hgBrasilKeyField.getValue().isBlank();
            brapiStatus.setConnected(brapiOk);
            hgBrasilStatus.setConnected(hgOk);
            int count = (brapiOk ? 1 : 0) + (hgOk ? 1 : 0) + 1; // CoinGecko sempre disponível (plano público, sem chave)
            connectedBadge.setText(count + " de 3 disponíveis");
        };
        brapiTokenField.textProperty().addListener((obs, old, val) -> refreshStatuses.run());
        hgBrasilKeyField.textProperty().addListener((obs, old, val) -> refreshStatuses.run());
        refreshStatuses.run();

        VBox brapiBox = buildApiKeyBox("brapi.dev", "Ações, FIIs, ETFs e BDRs da B3 · cotação e histórico",
                "TOKEN", brapiTokenField.row, brapiStatus, () -> brapiClient.getQuote("PETR4"));
        VBox hgBrasilBox = buildApiKeyBox("HG Brasil", "Selic, CDI, IPCA, índices e câmbio USD/EUR",
                "CHAVE", hgBrasilKeyField.row, hgBrasilStatus, hgBrasilClient::getSnapshot);
        VBox coinGeckoBox = buildCoinGeckoBox(coinGeckoStatus);

        GridPane grid = evenColumnsGrid(3, 14);
        grid.add(brapiBox, 0, 0);
        grid.add(hgBrasilBox, 1, 0);
        grid.add(coinGeckoBox, 2, 0);

        statusLabel.getStyleClass().add("form-info-label");
        statusLabel.setWrapText(true);

        VBox card = new VBox(16, headerRow, grid, statusLabel);
        card.getStyleClass().add("content-card");
        return card;
    }

    private VBox buildApiKeyBox(String name, String description, String fieldLabel, HBox fieldRow,
                                 ApiStatusIndicator status, Runnable networkCall) {
        Label nameLabel = new Label(name);
        nameLabel.setStyle("-fx-font-family: 'Manrope'; -fx-font-weight: bold; -fx-font-size: 14px; -fx-text-fill: -fx-color-text-primary;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox nameRow = new HBox(nameLabel, spacer, status.node);
        nameRow.setAlignment(Pos.CENTER_LEFT);

        Label descLabel = new Label(description);
        descLabel.getStyleClass().add("content-card-subtitle");
        descLabel.setWrapText(true);

        VBox fieldBox = fieldGroup(fieldLabel, fieldRow);

        Button testButton = buildTestConnectionButton(name, networkCall);

        VBox box = new VBox(13, nameRow, descLabel, fieldBox, testButton);
        box.setStyle("-fx-background-color: -fx-color-bg-surface; -fx-border-color: -fx-color-border-card;"
                + " -fx-border-radius: 12; -fx-background-radius: 12; -fx-padding: 16 17;");
        return box;
    }

    private VBox buildCoinGeckoBox(ApiStatusIndicator status) {
        Label nameLabel = new Label("CoinGecko");
        nameLabel.setStyle("-fx-font-family: 'Manrope'; -fx-font-weight: bold; -fx-font-size: 14px; -fx-text-fill: -fx-color-text-primary;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox nameRow = new HBox(nameLabel, spacer, status.node);
        nameRow.setAlignment(Pos.CENTER_LEFT);

        Label descLabel = new Label("Criptomoedas · cotação e histórico de 365 dias");
        descLabel.getStyleClass().add("content-card-subtitle");
        descLabel.setWrapText(true);

        Label noKeyLabel = new Label("plano público, sem chave");
        noKeyLabel.getStyleClass().add("text-field");
        noKeyLabel.setAlignment(Pos.CENTER_LEFT);
        noKeyLabel.setMaxWidth(Double.MAX_VALUE);
        VBox fieldBox = fieldGroup("CHAVE (OPCIONAL)", noKeyLabel);

        Button testButton = buildTestConnectionButton("CoinGecko", () -> coinGeckoClient.getPrices(List.of("BTC")));

        VBox box = new VBox(13, nameRow, descLabel, fieldBox, testButton);
        box.setStyle("-fx-background-color: -fx-color-bg-surface; -fx-border-color: -fx-color-border-card;"
                + " -fx-border-radius: 12; -fx-background-radius: 12; -fx-padding: 16 17;");
        return box;
    }

    /**
     * Testa conectividade real chamando {@code networkCall} (o cliente
     * correspondente, ex. {@code hgBrasilClient::getSnapshot}) num {@link
     * Task} em background (RT06) — mede o tempo de resposta e mostra
     * sucesso/erro em {@link #statusLabel}. Os clientes injetados foram
     * construídos com a chave/token ativos **no momento em que o app abriu**
     * (ATV-18: chave nova só vale após reiniciar) — por isso o resultado do
     * teste sempre reflete a sessão atual, nunca um valor ainda não salvo no
     * campo de texto.
     */
    private Button buildTestConnectionButton(String apiName, Runnable networkCall) {
        Button button = new Button("Testar conexão");
        button.getStyleClass().add("pill-secondary");
        button.setMaxWidth(Double.MAX_VALUE);
        button.setAlignment(Pos.CENTER);
        button.setOnAction(e -> {
            button.setDisable(true);
            button.setText("Testando...");
            long startedAt = System.currentTimeMillis();
            Task<Void> task = new Task<>() {
                @Override
                protected Void call() {
                    networkCall.run();
                    return null;
                }
            };
            task.setOnSucceeded(ev -> {
                button.setDisable(false);
                button.setText("Testar conexão");
                long elapsedMs = System.currentTimeMillis() - startedAt;
                statusLabel.getStyleClass().remove("form-error-label");
                if (!statusLabel.getStyleClass().contains("form-info-label")) {
                    statusLabel.getStyleClass().add("form-info-label");
                }
                statusLabel.setText(apiName + ": conexão OK (" + elapsedMs
                        + " ms) — testado com a chave/token ativos nesta sessão.");
            });
            task.setOnFailed(ev -> {
                button.setDisable(false);
                button.setText("Testar conexão");
                Throwable ex = task.getException();
                showError(apiName + ": falha na conexão — "
                        + (ex != null && ex.getMessage() != null ? ex.getMessage() : "erro desconhecido"));
            });
            Thread thread = new Thread(task, "test-connection-" + apiName);
            thread.setDaemon(true);
            thread.start();
        });
        return button;
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

    // =====================================================================
    // Bloco 2 — Intervalo de atualização automática de cotações
    // =====================================================================

    /**
     * 3 linhas por fonte de dado (Telas.dc.html linhas 1210-1223), mais os 3
     * toggles (linhas 1227-1240) — layout igual ao template, mas com dado
     * real por trás em vez dos 3 valores independentes do mockup:
     * {@code updateIntervalMinutes} é hoje **1 único** valor
     * ({@code MarketServiceImpl.configuredQuoteTtl}, ATV-18) que sobrescreve
     * o TTL de cotação de brapi.dev **e** CoinGecko ao mesmo tempo — por
     * isso as linhas "Ações, FIIs e ETFs" e "Criptomoedas" usam o MESMO
     * grupo de pills (clicar em uma reflete na outra, não são
     * independentes). "Câmbio e indicadores" (HG Brasil) usa TTLs fixos
     * (5 min / 6h, {@code MarketServiceImpl.MACRO_TTL}/{@code
     * INDICATORS_TTL}) que esta tela não expõe como configurável — mostrado
     * como texto informativo, não como pills clicáveis, para não sugerir uma
     * opção que não existe. Os 3 toggles (snapshot/pausa/alerta) e a tabela
     * "Últimas sincronizações" do template não têm nenhuma lógica real por
     * trás ainda (sem log de sincronização no schema, snapshot diário já é
     * incondicional) — os toggles ficam só persistidos (mesmo padrão do
     * card "Preferências"), e a tabela de sincronizações foi omitida em vez
     * de preenchida com dado inventado.
     */
    private VBox buildUpdateIntervalCard() {
        Label title = new Label("Atualização de cotações");
        title.getStyleClass().add("content-card-title");
        Label subtitle = new Label("frequência aplicada por categoria para economizar requisições");
        subtitle.getStyleClass().add("content-card-subtitle");
        subtitle.setWrapText(true);
        VBox titleBox = new VBox(4, title, subtitle);

        List<String> presetLabels = List.of("15 min", "1 hora", "Diária");
        List<Integer> presetMinutes = List.of(15, 60, 1440);
        FrequencyPillGroup stocksFreq = new FrequencyPillGroup(presetLabels, presetMinutes);
        FrequencyPillGroup cryptoFreq = new FrequencyPillGroup(presetLabels, presetMinutes);

        updateIntervalField.setId("updateIntervalField");
        updateIntervalField.getStyleClass().add("text-field");
        updateIntervalField.setPromptText("ex.: 15 (deixe em branco para usar o padrão)");
        updateIntervalField.setMaxWidth(220);

        Runnable syncPills = () -> {
            Integer minutes = parseIntOrNull(updateIntervalField.getText());
            stocksFreq.setSelectedMinutes(minutes);
            cryptoFreq.setSelectedMinutes(minutes);
        };
        stocksFreq.setOnSelect(m -> updateIntervalField.setText(String.valueOf(m)));
        cryptoFreq.setOnSelect(m -> updateIntervalField.setText(String.valueOf(m)));
        updateIntervalField.textProperty().addListener((obs, old, val) -> syncPills.run());
        syncPills.run();

        HBox stocksRow = buildFrequencyRow("Ações, FIIs e ETFs", "brapi.dev · em dias de mercado", stocksFreq.node);
        HBox cryptoRow = buildFrequencyRow("Criptomoedas", "CoinGecko · 24 h por dia", cryptoFreq.node);

        Label fixedValue = new Label("5 min · 6 h");
        fixedValue.setStyle("-fx-font-family: 'IBM Plex Mono SemiBold'; -fx-font-size: 12px; -fx-text-fill: -fx-color-text-muted;");
        HBox fxRow = buildFrequencyRow("Câmbio e indicadores",
                "HG Brasil · Selic, CDI, IPCA, USD, EUR — intervalo fixo, não configurável aqui", fixedValue);

        VBox rowsBox = new VBox(9, stocksRow, cryptoRow, fxRow);

        updateIntervalErrorLabel.setId("updateIntervalErrorLabel");
        updateIntervalErrorLabel.getStyleClass().add("form-error-label");
        updateIntervalErrorLabel.setVisible(false);
        updateIntervalErrorLabel.setManaged(false);
        VBox customFieldBox = fieldGroup("OU DIGITE UM VALOR PERSONALIZADO (MINUTOS)", updateIntervalField);

        Region divider1 = new Region();
        divider1.setStyle("-fx-background-color: -fx-color-border-row; -fx-pref-height: 1;");
        divider1.setMaxWidth(Double.MAX_VALUE);

        HBox snapshotRow = buildToggleRow("Snapshot diário do patrimônio",
                "grava o valor total ao abrir o app — desligar aqui pausa novos pontos no gráfico do Dashboard", snapshotEnabledToggle);
        HBox pauseRow = buildToggleRow("Pausar em fins de semana e feriados",
                "não consome requisições com o mercado fechado (só sábado/domingo — feriados não cobertos)", pauseWeekendsToggle);
        HBox alertRow = buildToggleRow("Avisar quando a API falhar",
                "mostra um aviso no Dashboard com a última cotação válida quando uma busca falhar", alertOnFailureToggle);
        VBox togglesBox = new VBox(13, snapshotRow, pauseRow, alertRow);

        Region divider2 = new Region();
        divider2.setStyle("-fx-background-color: -fx-color-border-row; -fx-pref-height: 1;");
        divider2.setMaxWidth(Double.MAX_VALUE);
        VBox syncSection = buildSyncSection();

        VBox card = new VBox(16, titleBox, rowsBox, customFieldBox, updateIntervalErrorLabel, divider1, togglesBox,
                divider2, syncSection);
        card.getStyleClass().add("content-card");
        return card;
    }

    /**
     * "Últimas sincronizações" (Telas.dc.html linhas 1242-1253) — dado real,
     * mas só em memória: {@link MarketService#getRecentSyncs()} guarda as
     * últimas {@code MAX_RECENT_SYNCS} tentativas desde que o app foi aberto
     * (não há log persistido no banco). Atualizado toda vez que a tela é
     * exibida ({@link #onShow()}), não em tempo real enquanto outra tela
     * roda um {@code updateQuotes} — suficiente para o caso de uso (conferir
     * depois de mexer nas configurações), sem precisar de um mecanismo de
     * observação entre telas que o app não tem hoje.
     */
    private VBox buildSyncSection() {
        Label title = new Label("Últimas sincronizações");
        title.setStyle("-fx-font-family: 'Manrope'; -fx-font-weight: bold; -fx-font-size: 14px; -fx-text-fill: -fx-color-text-primary;");

        syncTableBody.setId("syncTableBody");
        VBox box = new VBox(9, title, syncTableBody);
        return box;
    }

    private void refreshSyncTable() {
        syncTableBody.getChildren().clear();
        List<SyncEvent> events = marketService.getRecentSyncs();

        if (events.isEmpty()) {
            Label empty = new Label("Nenhuma sincronização registrada nesta sessão ainda — clique em "
                    + "\"Atualizar cotações\" em qualquer tela.");
            empty.getStyleClass().add("content-card-subtitle");
            empty.setWrapText(true);
            syncTableBody.getChildren().add(empty);
            return;
        }

        GridPane grid = new GridPane();
        grid.setId("syncGrid");
        grid.setHgap(12);
        grid.getColumnConstraints().addAll(pctColumn(30), pctColumn(28), pctColumn(20), pctColumn(22));

        grid.add(syncHeaderCell("HORÁRIO", HPos.LEFT), 0, 0);
        grid.add(syncHeaderCell("FONTE", HPos.LEFT), 1, 0);
        grid.add(syncHeaderCell("ATIVOS", HPos.RIGHT), 2, 0);
        grid.add(syncHeaderCell("STATUS", HPos.RIGHT), 3, 0);

        DateTimeFormatter timeFmt = DateTimeFormatter.ofPattern("HH:mm", new Locale("pt", "BR")).withZone(ZoneId.systemDefault());
        for (int i = 0; i < events.size(); i++) {
            SyncEvent event = events.get(i);
            int row = i + 1;
            boolean last = i == events.size() - 1;

            Label timeLabel = syncCell(timeFmt.format(event.timestamp()), HPos.LEFT, last);
            Label sourceLabel = new Label(event.source());
            sourceLabel.setStyle("-fx-font-family: 'Manrope'; -fx-font-weight: bold; -fx-font-size: 12px; -fx-text-fill: -fx-color-text-primary;");
            sourceLabel.setMaxWidth(Double.MAX_VALUE);
            styleSyncRow(sourceLabel, last);
            Label countLabel = syncCell(String.valueOf(event.assetCount()), HPos.RIGHT, last);

            Label statusBadge = new Label(event.success() ? "ok" : "erro");
            statusBadge.getStyleClass().add(event.success() ? "badge-buy" : "badge-sell");
            HBox statusBox = new HBox(statusBadge);
            statusBox.setAlignment(Pos.CENTER_RIGHT);
            GridPane.setHalignment(statusBox, HPos.RIGHT);
            styleSyncRow(statusBox, last);

            grid.add(timeLabel, 0, row);
            grid.add(sourceLabel, 1, row);
            grid.add(countLabel, 2, row);
            grid.add(statusBox, 3, row);
        }

        syncTableBody.getChildren().add(grid);
    }

    private Label syncHeaderCell(String text, HPos align) {
        Label label = new Label(text);
        label.getStyleClass().add("table-header-cell");
        GridPane.setHalignment(label, align);
        return label;
    }

    private Label syncCell(String text, HPos align, boolean last) {
        Label label = new Label(text);
        label.getStyleClass().add("table-row-secondary");
        label.setMaxWidth(Double.MAX_VALUE);
        GridPane.setHalignment(label, align);
        styleSyncRow(label, last);
        return label;
    }

    private void styleSyncRow(Region node, boolean last) {
        node.setPadding(new Insets(11, 0, 11, 0));
        if (!last) {
            node.getStyleClass().add("table-row-divider");
        }
    }

    private HBox buildFrequencyRow(String name, String description, Node rightControl) {
        Label nameLabel = new Label(name);
        nameLabel.setStyle("-fx-font-family: 'Manrope'; -fx-font-weight: bold; -fx-font-size: 13px; -fx-text-fill: -fx-color-text-primary;");
        Label descLabel = new Label(description);
        descLabel.setStyle("-fx-font-family: 'Manrope Medium'; -fx-font-size: 11px; -fx-text-fill: -fx-color-text-muted;");
        VBox textBox = new VBox(3, nameLabel, descLabel);
        HBox.setHgrow(textBox, Priority.ALWAYS);

        HBox row = new HBox(14, textBox, rightControl);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setStyle("-fx-background-color: -fx-color-bg-empty; -fx-background-radius: 11; -fx-padding: 12 14;");
        return row;
    }

    private HBox buildToggleRow(String name, String description, ToggleSwitch toggle) {
        Label nameLabel = new Label(name);
        nameLabel.setStyle("-fx-font-family: 'Manrope'; -fx-font-weight: bold; -fx-font-size: 13px; -fx-text-fill: -fx-color-text-primary;");
        Label descLabel = new Label(description);
        descLabel.getStyleClass().add("content-card-subtitle");
        descLabel.setWrapText(true);
        VBox textBox = new VBox(3, nameLabel, descLabel);
        HBox.setHgrow(textBox, Priority.ALWAYS);

        HBox row = new HBox(14, textBox, toggle.node);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static Integer parseIntOrNull(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // =====================================================================
    // Bloco 3 — Preferências (Telas.dc.html linhas 1256-1271)
    // =====================================================================

    private VBox buildPreferencesCard() {
        Label title = new Label("Preferências");
        title.getStyleClass().add("content-card-title");

        primaryCurrencyCombo.setId("primaryCurrencyCombo");
        primaryCurrencyCombo.getStyleClass().add("text-field");
        primaryCurrencyCombo.setMaxWidth(Double.MAX_VALUE);
        // As 8 moedas que o app ja aceita como ativo de cambio (portanto com
        // taxa garantida em MacroSnapshot.currencies()) + o real. A conversao
        // e so de exibicao: o calculo e o relatorio de IR continuam em BRL.
        primaryCurrencyCombo.setItems(FXCollections.observableArrayList(CurrencyDisplay.labels()));

        Label currencyNote = new Label("converte apenas a exibição de Dashboard, Ações e FIIs, Renda Fixa e Câmbio "
                + "e Cripto — cálculo, cadastro e relatório de IR continuam em reais");
        currencyNote.getStyleClass().add("content-card-subtitle");
        currencyNote.setWrapText(true);

        defaultChartPeriodCombo.setId("defaultChartPeriodCombo");
        defaultChartPeriodCombo.getStyleClass().add("text-field");
        defaultChartPeriodCombo.setMaxWidth(Double.MAX_VALUE);
        defaultChartPeriodCombo.setItems(FXCollections.observableArrayList("1 mês", "6 meses", "12 meses", "Tudo"));

        cryptoDecimalsCombo.setId("cryptoDecimalsCombo");
        cryptoDecimalsCombo.getStyleClass().add("text-field");
        cryptoDecimalsCombo.setMaxWidth(Double.MAX_VALUE);
        cryptoDecimalsCombo.setItems(FXCollections.observableArrayList(2, 4, 6, 8));

        GridPane fieldsGrid = new GridPane();
        fieldsGrid.setHgap(14);
        fieldsGrid.setVgap(14);
        fieldsGrid.getColumnConstraints().addAll(pctColumn(50), pctColumn(50));
        fieldsGrid.add(fieldGroup("MOEDA PRINCIPAL", primaryCurrencyCombo), 0, 0);
        fieldsGrid.add(fieldGroup("TEMA", themeToggle.row), 1, 0);
        fieldsGrid.add(fieldGroup("PERÍODO PADRÃO DOS GRÁFICOS", defaultChartPeriodCombo), 0, 1);
        fieldsGrid.add(fieldGroup("CASAS DECIMAIS EM CRIPTO", cryptoDecimalsCombo), 1, 1);

        Region divider = new Region();
        divider.setStyle("-fx-background-color: -fx-color-border-row; -fx-pref-height: 1;");
        divider.setMaxWidth(Double.MAX_VALUE);

        Label hideLabel = new Label("Ocultar valores no dashboard");
        hideLabel.getStyleClass().add("table-row-primary");
        Label hideDesc = new Label("modo privacidade para uso em público");
        hideDesc.getStyleClass().add("content-card-subtitle");
        VBox hideTextBox = new VBox(3, hideLabel, hideDesc);
        HBox.setHgrow(hideTextBox, Priority.ALWAYS);
        HBox hideRow = new HBox(14, hideTextBox, hideDashboardValuesToggle.node);
        hideRow.setAlignment(Pos.CENTER_LEFT);

        VBox card = new VBox(16, title, fieldsGrid, currencyNote, divider, hideRow);
        card.getStyleClass().add("content-card");
        return card;
    }

    // =====================================================================
    // Bloco 4 — Backup/restauração + Bloco 5 — Zona de perigo
    // =====================================================================

    private VBox buildBackupCard() {
        Label title = new Label("Backup e restauração");
        title.getStyleClass().add("content-card-title");
        Label subtitle = new Label("todos os dados ficam no seu banco local — faça backups regulares");
        subtitle.getStyleClass().add("content-card-subtitle");
        subtitle.setWrapText(true);
        VBox titleBox = new VBox(4, title, subtitle);

        Button exportButton = new Button("Exportar backup agora");
        exportButton.setId("exportBackupButton");
        // No template esse botao e verde (accent + texto on-accent), nao o
        // fundo escuro de .button-primary — ver Telas.dc.html linha 1290.
        exportButton.getStyleClass().add("button-accent");
        exportButton.setMaxWidth(Double.MAX_VALUE);
        exportButton.setOnAction(e -> exportBackup());

        Button restoreButton = new Button("Restaurar");
        restoreButton.setId("restoreBackupButton");
        restoreButton.getStyleClass().add("pill-secondary");
        restoreButton.setOnAction(e -> restoreBackup());

        HBox buttons = new HBox(8, exportButton, restoreButton);
        HBox.setHgrow(exportButton, Priority.ALWAYS);

        VBox dangerTitleBox = new VBox(3,
                labelWithStyle("Apagar todos os dados", "danger-zone-title"),
                labelWithStyle("ação irreversível · exige confirmação", "danger-zone-desc"));
        HBox.setHgrow(dangerTitleBox, Priority.ALWAYS);

        Button eraseButton = new Button("Apagar");
        eraseButton.setId("eraseAllDataButton");
        eraseButton.getStyleClass().add("danger-zone-button");
        eraseButton.setOnAction(e -> openDangerZoneDialog());

        HBox dangerZone = new HBox(14, dangerTitleBox, eraseButton);
        dangerZone.setAlignment(Pos.CENTER_LEFT);
        dangerZone.getStyleClass().add("danger-zone");

        VBox card = new VBox(16, titleBox, buttons, dangerZone);
        card.getStyleClass().add("content-card");
        return card;
    }

    private Label labelWithStyle(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }

    // =====================================================================
    // Persistência (settings)
    // =====================================================================

    private void loadFromSettings() {
        hgBrasilKeyField.setValue(settingRepository.get(SETTING_HGBRASIL_API_KEY, ""));
        brapiTokenField.setValue(settingRepository.get(SETTING_BRAPI_TOKEN, ""));
        updateIntervalField.setText(settingRepository.get(SETTING_UPDATE_INTERVAL_MINUTES, ""));
        updateIntervalErrorLabel.setVisible(false);
        updateIntervalErrorLabel.setManaged(false);

        // Normaliza pelo enum em vez de usar o texto cru: um valor gravado por
        // uma versao anterior (ou editado a mao no banco) que nao esteja na
        // lista deixaria o ComboBox exibindo um item que nao existe nela.
        primaryCurrencyCombo.setValue(
                CurrencyDisplay.Primary.fromLabel(settingRepository.get(SETTING_PRIMARY_CURRENCY, null)).label());
        themeToggle.setSecondSelected(
                ThemeManager.fromSettingValue(settingRepository.get(SETTING_THEME, null)) == Theme.DARK);
        defaultChartPeriodCombo.setValue(settingRepository.get(SETTING_DEFAULT_CHART_PERIOD, "12 meses"));
        cryptoDecimalsCombo.setValue(parseIntOrDefault(settingRepository.get(SETTING_CRYPTO_DECIMALS, "6"), 6));
        hideDashboardValuesToggle.setOn(Boolean.parseBoolean(settingRepository.get(SETTING_HIDE_DASHBOARD_VALUES, "false")));

        // Mesmo default visual do template: snapshot e pausa em fins de
        // semana começam ligados, alerta de falha começa desligado.
        snapshotEnabledToggle.setOn(Boolean.parseBoolean(settingRepository.get(SETTING_SNAPSHOT_ENABLED, "true")));
        pauseWeekendsToggle.setOn(Boolean.parseBoolean(settingRepository.get(SETTING_PAUSE_WEEKENDS, "true")));
        alertOnFailureToggle.setOn(Boolean.parseBoolean(settingRepository.get(SETTING_ALERT_ON_FAILURE, "false")));

        refreshSyncTable();

        statusLabel.setText(" ");
    }

    private static int parseIntOrDefault(String text, int fallback) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void discardChanges() {
        loadFromSettings();
    }

    private void saveSettings() {
        String rawInterval = updateIntervalField.getText() == null ? "" : updateIntervalField.getText().trim();
        if (!rawInterval.isBlank() && !isValidPositiveInteger(rawInterval)) {
            updateIntervalErrorLabel.setText("Intervalo inválido — use um número inteiro maior que 0, ou deixe em branco.");
            updateIntervalErrorLabel.setVisible(true);
            updateIntervalErrorLabel.setManaged(true);
            return;
        }
        updateIntervalErrorLabel.setVisible(false);
        updateIntervalErrorLabel.setManaged(false);

        settingRepository.save(SETTING_HGBRASIL_API_KEY, safeTrim(hgBrasilKeyField.getValue()));
        settingRepository.save(SETTING_BRAPI_TOKEN, safeTrim(brapiTokenField.getValue()));
        settingRepository.save(SETTING_UPDATE_INTERVAL_MINUTES, rawInterval);

        Theme theme = themeToggle.isSecondSelected() ? Theme.DARK : Theme.LIGHT;
        settingRepository.save(SETTING_PRIMARY_CURRENCY, primaryCurrencyCombo.getValue());
        settingRepository.save(SETTING_THEME, ThemeManager.toSettingValue(theme));
        settingRepository.save(SETTING_DEFAULT_CHART_PERIOD, defaultChartPeriodCombo.getValue());
        settingRepository.save(SETTING_CRYPTO_DECIMALS, String.valueOf(cryptoDecimalsCombo.getValue()));
        settingRepository.save(SETTING_HIDE_DASHBOARD_VALUES, String.valueOf(hideDashboardValuesToggle.isOn()));

        settingRepository.save(SETTING_SNAPSHOT_ENABLED, String.valueOf(snapshotEnabledToggle.isOn()));
        settingRepository.save(SETTING_PAUSE_WEEKENDS, String.valueOf(pauseWeekendsToggle.isOn()));
        settingRepository.save(SETTING_ALERT_ON_FAILURE, String.valueOf(alertOnFailureToggle.isOn()));

        // Tema vale na hora: ThemeManager so troca a lista de stylesheets da
        // Scene, sem reconstruir tela nenhuma. Os graficos (Canvas, que nao
        // le CSS) so assumem a cor nova quando o usuario volta para a tela
        // deles — o Shell chama onShow()/refresh() a cada navegacao, e esta
        // tela nao tem grafico. Ver ThemeManager.
        ThemeManager.apply(theme);

        // Moeda: resolve a taxa de cambio agora para o aviso abaixo poder
        // dizer se ela realmente entrou em vigor. As telas de patrimonio
        // reconfiguram sozinhas ao serem abertas (Shell.select).
        CurrencyDisplay.configure(settingRepository, marketService);

        String currencyNote;
        if (CurrencyDisplay.isRateUnavailable()) {
            // Nao adianta fingir que aplicou: sem taxa, a exibicao caiu de
            // volta para BRL. O valor escolhido continua salvo e passa a valer
            // sozinho assim que a cotacao voltar.
            currencyNote = " A moeda escolhida foi salva, mas a taxa de câmbio não está disponível agora — "
                    + "os valores continuam em reais até a cotação ser obtida.";
        } else if (!CurrencyDisplay.isBrl()) {
            currencyNote = " Valores exibidos em " + CurrencyDisplay.current().label()
                    + " (1 " + CurrencyDisplay.code() + " = R$ "
                    + String.format(Locale.forLanguageTag("pt-BR"), "%,.4f", CurrencyDisplay.rate()) + ").";
        } else {
            currencyNote = "";
        }

        statusLabel.setText("Configurações salvas. O tema, a moeda principal e o intervalo de atualização já valem "
                + "imediatamente; novas chaves de API só valem após reiniciar o aplicativo." + currencyNote);
    }

    private static boolean isValidPositiveInteger(String text) {
        try {
            return Integer.parseInt(text) > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    // =====================================================================
    // Backup / restauração
    // =====================================================================

    private void exportBackup() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Exportar backup");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Banco SQLite", "*.db"));
        chooser.setInitialFileName("investimento-backup-" + LocalDate.now() + ".db");

        File file = root.getScene() != null && root.getScene().getWindow() != null
                ? chooser.showSaveDialog(root.getScene().getWindow())
                : null;
        if (file == null) {
            return;
        }
        try {
            backupService.createBackup(file.toPath());
            statusLabel.setText("Backup exportado em " + file.getAbsolutePath());
        } catch (IOException e) {
            showError("Falha ao exportar backup: " + e.getMessage());
        }
    }

    private void restoreBackup() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Restaurar backup");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Banco SQLite", "*.db"));

        File file = root.getScene() != null && root.getScene().getWindow() != null
                ? chooser.showOpenDialog(root.getScene().getWindow())
                : null;
        if (file == null) {
            return;
        }

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                "Restaurar o backup selecionado substitui TODOS os dados atuais pelos dados do arquivo escolhido. "
                        + "Esta ação não pode ser desfeita. Deseja continuar?",
                ButtonType.YES, ButtonType.NO);
        alert.setHeaderText("Restaurar backup");
        Optional<ButtonType> result = alert.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.YES) {
            performRestore(file);
        }
    }

    private void performRestore(File file) {
        try {
            backupService.restoreBackup(file.toPath());
            statusLabel.setText("Backup restaurado com sucesso.");
            if (onDataRestored != null) {
                onDataRestored.run();
            }
        } catch (IOException e) {
            showError("Falha ao restaurar backup: " + e.getMessage());
        }
    }

    /**
     * Seam de teste (mesmo pacote) — pula o {@link FileChooser} e o
     * {@link Alert} de confirmação (mesma estratégia já usada nas ATV-13 a
     * 17 para pular interações modais/de janela), exercitando direto a
     * chamada ao {@link BackupService} e o callback {@link #onDataRestored}.
     */
    void performRestoreForTest(File file) {
        performRestore(file);
    }

    /**
     * Seam de teste (mesmo pacote) — pula o {@link FileChooser}, exercita
     * direto a chamada a {@link BackupService#createBackup(java.nio.file.Path)}.
     */
    void exportBackupForTest(File file) throws IOException {
        backupService.createBackup(file.toPath());
    }

    // =====================================================================
    // Zona de perigo — apagar todos os dados
    // =====================================================================

    private void openDangerZoneDialog() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Apagar todos os dados");
        dialog.setHeaderText("Esta ação é irreversível e apaga TODOS os dados do aplicativo (ativos, transações, "
                + "histórico de cotações e configurações). Digite \"" + CONFIRM_WORD + "\" para confirmar.");

        ButtonType confirmButtonType = new ButtonType("Apagar definitivamente", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(confirmButtonType, ButtonType.CANCEL);

        TextField confirmField = new TextField();
        confirmField.setId("dangerZoneConfirmField");
        confirmField.setPromptText("Digite " + CONFIRM_WORD);
        confirmField.getStyleClass().add("text-field");
        dialog.getDialogPane().setContent(confirmField);

        Node confirmButtonNode = dialog.getDialogPane().lookupButton(confirmButtonType);
        confirmButtonNode.setDisable(true);
        confirmField.textProperty().addListener((obs, oldVal, newVal) ->
                confirmButtonNode.setDisable(!isConfirmWordValid(newVal)));

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isPresent() && result.get() == confirmButtonType) {
            performEraseAllData();
        }
    }

    private void performEraseAllData() {
        backupService.eraseAllData();
        loadFromSettings();
        statusLabel.setText("Todos os dados foram apagados.");
    }

    /**
     * Seam de teste (mesmo pacote) — valida a mesma regra que habilita o
     * botão de confirmação do {@link Dialog} ({@link #openDangerZoneDialog()}),
     * sem precisar abrir a janela modal de verdade.
     */
    boolean isConfirmWordValid(String typed) {
        return CONFIRM_WORD.equals(typed);
    }

    /**
     * Seam de teste (mesmo pacote) — pula o {@link Dialog} modal (mesma
     * decisão das ATV-13/14/17 para {@link Alert} de confirmação),
     * exercitando direto {@link BackupService#eraseAllData()} + o recarregamento
     * dos campos do formulário.
     */
    void performEraseAllDataForTest() {
        performEraseAllData();
    }

    private void showError(String message) {
        statusLabel.getStyleClass().remove("form-info-label");
        statusLabel.getStyleClass().add("form-error-label");
        statusLabel.setText(message);
    }

    // =====================================================================
    // Seams de teste — leitura/escrita direta dos campos (mesmo pacote)
    // =====================================================================

    String hgBrasilKeyForTest() {
        return hgBrasilKeyField.getValue();
    }

    void setHgBrasilKeyForTest(String value) {
        hgBrasilKeyField.setValue(value);
    }

    String brapiTokenForTest() {
        return brapiTokenField.getValue();
    }

    void setBrapiTokenForTest(String value) {
        brapiTokenField.setValue(value);
    }

    void setUpdateIntervalForTest(String value) {
        updateIntervalField.setText(value);
    }

    String updateIntervalForTest() {
        return updateIntervalField.getText();
    }

    void saveForTest() {
        saveSettings();
    }

    void discardForTest() {
        discardChanges();
    }

    boolean isUpdateIntervalErrorVisibleForTest() {
        return updateIntervalErrorLabel.isVisible();
    }

    String statusForTest() {
        return statusLabel.getText();
    }

    boolean isSecretMaskedForTest(boolean hgBrasil) {
        SecretField field = hgBrasil ? hgBrasilKeyField : brapiTokenField;
        return field.maskedField.isVisible();
    }

    void toggleSecretVisibilityForTest(boolean hgBrasil) {
        SecretField field = hgBrasil ? hgBrasilKeyField : brapiTokenField;
        field.toggleButton.fire();
    }

    // =====================================================================
    // Helpers de layout
    // =====================================================================

    private VBox fieldGroup(String labelText, Node control) {
        Label label = new Label(labelText);
        label.getStyleClass().add("field-label");
        if (control instanceof Region region) {
            region.setMaxWidth(Double.MAX_VALUE);
        }
        return new VBox(7, label, control);
    }

    private ColumnConstraints pctColumn(double percent) {
        ColumnConstraints c = new ColumnConstraints();
        c.setPercentWidth(percent);
        c.setHalignment(HPos.LEFT);
        return c;
    }

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

    /**
     * Par {@link PasswordField}/{@link TextField} sobrepostos num
     * {@link StackPane} (mesmo valor, via {@code bindBidirectional}) — só um
     * fica visível por vez, alternado pelo botão "Mostrar/Ocultar". JavaFX
     * não tem um "reveal" nativo em {@code PasswordField} (RT05 exige não
     * deixar a chave em texto plano visível por padrão).
     */
    private static final class SecretField {
        final PasswordField maskedField = new PasswordField();
        final TextField plainField = new TextField();
        final Button toggleButton = new Button("Mostrar");
        final HBox row;

        SecretField(String idPrefix) {
            maskedField.setId(idPrefix + "Masked");
            plainField.setId(idPrefix + "Plain");
            maskedField.getStyleClass().add("text-field");
            plainField.getStyleClass().add("text-field");
            maskedField.setMaxWidth(Double.MAX_VALUE);
            plainField.setMaxWidth(Double.MAX_VALUE);

            plainField.setVisible(false);
            plainField.setManaged(false);
            plainField.textProperty().bindBidirectional(maskedField.textProperty());

            StackPane stack = new StackPane(maskedField, plainField);
            HBox.setHgrow(stack, Priority.ALWAYS);

            toggleButton.getStyleClass().add("pill-secondary");
            toggleButton.setOnAction(e -> toggleVisibility());

            row = new HBox(8, stack, toggleButton);
        }

        private void toggleVisibility() {
            boolean showingPlain = plainField.isVisible();
            plainField.setVisible(!showingPlain);
            plainField.setManaged(!showingPlain);
            maskedField.setVisible(showingPlain);
            maskedField.setManaged(showingPlain);
            toggleButton.setText(showingPlain ? "Mostrar" : "Ocultar");
        }

        String getValue() {
            return maskedField.getText();
        }

        void setValue(String value) {
            maskedField.setText(value == null ? "" : value);
        }

        javafx.beans.value.ObservableValue<String> textProperty() {
            return maskedField.textProperty();
        }
    }

    /**
     * Indicador "● Conectada"/"● Sem chave configurada" (dot + label) de cada
     * caixa de API — estado sempre derivado do campo local (em branco ou
     * não), nunca de uma chamada de rede real (ver Javadoc de {@code
     * buildApiKeysCard}).
     */
    private static final class ApiStatusIndicator {
        final HBox node;
        private final Region dot = new Region();
        private final Label label = new Label();

        ApiStatusIndicator() {
            dot.setMinSize(7, 7);
            dot.setMaxSize(7, 7);
            node = new HBox(6, dot, label);
            node.setAlignment(Pos.CENTER_LEFT);
        }

        void setConnected(boolean connected) {
            label.setText(connected ? "Conectada" : "Sem chave configurada");
            String textColor = connected ? "-fx-color-accent-strong" : "-fx-color-text-faint";
            String dotColor = connected ? "-fx-color-accent" : "-fx-color-border-pill";
            label.setStyle("-fx-font-family: 'Manrope'; -fx-font-weight: bold; -fx-font-size: 11px; -fx-text-fill: " + textColor + ";");
            dot.setStyle("-fx-background-radius: 4; -fx-background-color: " + dotColor + ";");
        }

        void setNeutral(String text) {
            label.setText(text);
            label.setStyle("-fx-font-family: 'Manrope'; -fx-font-weight: bold; -fx-font-size: 11px; -fx-text-fill: -fx-color-text-muted;");
            dot.setStyle("-fx-background-radius: 4; -fx-background-color: -fx-color-neutral-warn;");
        }
    }

    /**
     * Grupo de pills de frequência (ex.: "15 min"/"1 hora"/"Diária"),
     * reaproveitando {@code .tab-pill}/{@code .tab-pill-active} já existentes
     * — clicar numa opção dispara o callback de {@link #setOnSelect}; {@link
     * #setSelectedMinutes} só reflete visualmente qual pill está ativa
     * (comparando pelo valor em minutos), não altera estado sozinho.
     */
    private static final class FrequencyPillGroup {
        final HBox node = new HBox(5);
        private final List<Label> pills = new java.util.ArrayList<>();
        private final List<Integer> minuteValues;

        FrequencyPillGroup(List<String> labels, List<Integer> minuteValues) {
            this.minuteValues = minuteValues;
            for (String labelText : labels) {
                Label pill = new Label(labelText);
                pill.setCursor(javafx.scene.Cursor.HAND);
                pill.getStyleClass().add("tab-pill");
                pills.add(pill);
                node.getChildren().add(pill);
            }
        }

        void setOnSelect(java.util.function.IntConsumer onSelect) {
            for (int i = 0; i < pills.size(); i++) {
                int minutes = minuteValues.get(i);
                pills.get(i).setOnMouseClicked(e -> onSelect.accept(minutes));
            }
        }

        void setSelectedMinutes(Integer minutes) {
            for (int i = 0; i < pills.size(); i++) {
                boolean active = minutes != null && minutes.equals(minuteValues.get(i));
                pills.get(i).getStyleClass().removeAll("tab-pill", "tab-pill-active");
                pills.get(i).getStyleClass().add(active ? "tab-pill-active" : "tab-pill");
            }
        }
    }

    /**
     * Toggle switch simples (trilho + círculo) — JavaFX não tem um controle
     * nativo equivalente ao switch iOS do template; nenhum componente
     * "toggle" está catalogado em componentes.md, então este é um {@code
     * StackPane} clicável próprio, seguindo a mesma paleta/radius do resto do
     * tema (classes {@code .toggle-track}/{@code .toggle-track-on}/{@code
     * .toggle-thumb} em {@code theme.css}).
     */
    private static final class ToggleSwitch {
        final StackPane node = new StackPane();
        private final Region thumb = new Region();
        private boolean on;

        ToggleSwitch() {
            thumb.getStyleClass().add("toggle-thumb");
            node.getChildren().add(thumb);
            node.setOnMouseClicked(e -> setOn(!on));
            applyState();
        }

        boolean isOn() {
            return on;
        }

        void setOn(boolean value) {
            this.on = value;
            applyState();
        }

        private void applyState() {
            node.getStyleClass().removeAll("toggle-track", "toggle-track-on");
            node.getStyleClass().add(on ? "toggle-track-on" : "toggle-track");
            StackPane.setAlignment(thumb, on ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        }
    }

    /**
     * Segmented control de 2 opções (ex.: "Claro"/"Escuro") reaproveitando as
     * classes já existentes {@code .segmented-option}/{@code
     * .segmented-option-selected} (mesmo padrão da moeda BRL/USD/EUR do
     * Cadastro, ATV-13) — a 2ª opção fica selecionada, nunca as duas.
     */
    private static final class ToggleSwitchPair {
        final HBox row;
        private final Label firstLabel;
        private final Label secondLabel;
        private boolean secondSelected;

        ToggleSwitchPair(String firstText, String secondText) {
            firstLabel = new Label(firstText);
            secondLabel = new Label(secondText);
            for (Label label : List.of(firstLabel, secondLabel)) {
                label.setAlignment(Pos.CENTER);
                label.setMaxWidth(Double.MAX_VALUE);
                label.setCursor(javafx.scene.Cursor.HAND);
                HBox.setHgrow(label, Priority.ALWAYS);
            }
            firstLabel.setOnMouseClicked(e -> setSecondSelected(false));
            secondLabel.setOnMouseClicked(e -> setSecondSelected(true));
            row = new HBox(6, firstLabel, secondLabel);
            applyState();
        }

        boolean isSecondSelected() {
            return secondSelected;
        }

        void setSecondSelected(boolean value) {
            this.secondSelected = value;
            applyState();
        }

        private void applyState() {
            firstLabel.getStyleClass().removeAll("segmented-option", "segmented-option-selected");
            secondLabel.getStyleClass().removeAll("segmented-option", "segmented-option-selected");
            firstLabel.getStyleClass().add(secondSelected ? "segmented-option" : "segmented-option-selected");
            secondLabel.getStyleClass().add(secondSelected ? "segmented-option-selected" : "segmented-option");
        }
    }
}
