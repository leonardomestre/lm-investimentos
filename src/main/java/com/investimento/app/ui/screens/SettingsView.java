package com.investimento.app.ui.screens;

import com.investimento.app.repository.SettingRepository;
import com.investimento.app.service.BackupService;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
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

    private static final String CONFIRM_WORD = "APAGAR";

    private final SettingRepository settingRepository;
    private final BackupService backupService;
    private final Runnable onDataRestored;

    private final VBox root;
    private final Label statusLabel = new Label(" ");

    private final SecretField hgBrasilKeyField = new SecretField("hgBrasilKey");
    private final SecretField brapiTokenField = new SecretField("brapiToken");
    private final TextField updateIntervalField = new TextField();
    private final Label updateIntervalErrorLabel = new Label();

    public SettingsView(SettingRepository settingRepository, BackupService backupService, Runnable onDataRestored) {
        this.settingRepository = settingRepository;
        this.backupService = backupService;
        this.onDataRestored = onDataRestored;

        HBox header = buildHeader();
        VBox apiKeysCard = buildApiKeysCard();
        VBox updateIntervalCard = buildUpdateIntervalCard();
        VBox backupCard = buildBackupCard();

        VBox contentBody = new VBox(20, apiKeysCard, updateIntervalCard, backupCard);
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

    private VBox buildApiKeysCard() {
        Label title = new Label("Chaves de API");
        title.getStyleClass().add("content-card-title");
        Label subtitle = new Label("armazenadas apenas no seu banco local — prioridade: aqui > variável de ambiente > padrão do projeto");
        subtitle.getStyleClass().add("content-card-subtitle");
        subtitle.setWrapText(true);
        VBox titleBox = new VBox(4, title, subtitle);

        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(7);
        grid.getColumnConstraints().addAll(pctColumn(50), pctColumn(50));
        grid.add(fieldGroup("CHAVE HG BRASIL", hgBrasilKeyField.row), 0, 0);
        grid.add(fieldGroup("TOKEN BRAPI.DEV", brapiTokenField.row), 1, 0);

        statusLabel.getStyleClass().add("form-info-label");
        statusLabel.setWrapText(true);

        VBox card = new VBox(14, titleBox, grid, statusLabel);
        card.getStyleClass().add("content-card");
        return card;
    }

    // =====================================================================
    // Bloco 2 — Intervalo de atualização automática de cotações
    // =====================================================================

    private VBox buildUpdateIntervalCard() {
        Label title = new Label("Atualização de cotações");
        title.getStyleClass().add("content-card-title");
        Label subtitle = new Label("intervalo mínimo (minutos) entre 2 buscas de cotação do mesmo ativo — sobrescreve o padrão "
                + "de 15 min (ações/FIIs/ETFs/BDRs) e 5 min (criptomoedas)");
        subtitle.getStyleClass().add("content-card-subtitle");
        subtitle.setWrapText(true);
        VBox titleBox = new VBox(4, title, subtitle);

        updateIntervalField.setId("updateIntervalField");
        updateIntervalField.getStyleClass().add("text-field");
        updateIntervalField.setPromptText("ex.: 15 (deixe em branco para usar o padrão)");
        updateIntervalField.setMaxWidth(220);

        updateIntervalErrorLabel.setId("updateIntervalErrorLabel");
        updateIntervalErrorLabel.getStyleClass().add("form-error-label");
        updateIntervalErrorLabel.setVisible(false);
        updateIntervalErrorLabel.setManaged(false);

        VBox fieldBox = fieldGroup("MINUTOS", updateIntervalField);
        VBox card = new VBox(14, titleBox, fieldBox, updateIntervalErrorLabel);
        card.getStyleClass().add("content-card");
        return card;
    }

    // =====================================================================
    // Bloco 3 — Backup/restauração + Bloco 4 — Zona de perigo
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
        exportButton.getStyleClass().add("button-primary");
        exportButton.setOnAction(e -> exportBackup());

        Button restoreButton = new Button("Restaurar");
        restoreButton.setId("restoreBackupButton");
        restoreButton.getStyleClass().add("pill-secondary");
        restoreButton.setOnAction(e -> restoreBackup());

        HBox buttons = new HBox(8, exportButton, restoreButton);

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
        statusLabel.setText(" ");
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

        statusLabel.setText("Configurações salvas. O intervalo de atualização já vale imediatamente; "
                + "novas chaves de API só valem após reiniciar o aplicativo.");
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
    }
}
