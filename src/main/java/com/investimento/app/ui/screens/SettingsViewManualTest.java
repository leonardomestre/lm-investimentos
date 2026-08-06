package com.investimento.app.ui.screens;

import com.investimento.app.api.brapi.BrapiClientImpl;
import com.investimento.app.api.coingecko.CoinGeckoClientImpl;
import com.investimento.app.api.hgbrasil.HgBrasilClientImpl;
import com.investimento.app.repository.IndicatorHistoryRepositoryImpl;
import com.investimento.app.repository.QuoteHistoryRepositoryImpl;
import com.investimento.app.repository.RateHistoryRepositoryImpl;
import com.investimento.app.repository.SettingRepository;
import com.investimento.app.repository.SettingRepositoryImpl;
import com.investimento.app.service.BackupService;
import com.investimento.app.service.MarketService;
import com.investimento.app.service.MarketServiceImpl;
import javafx.application.Platform;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Teste manual (main(), sem JUnit — mesmo padrão das ATV-13 a 19) da tela
 * Configurações (ATV-18).
 *
 * <p><strong>Escopo desta execução</strong>: por instrução explícita do
 * usuário, nenhuma etapa de validação visual/manual ({@code mvn javafx:run} +
 * observação humana) foi executada — só os cenários abaixo, verificáveis
 * inteiramente por código. Roda com {@code Platform.startup} (toolkit
 * "headless", sem {@code Stage}/{@code Scene}), contra um SQLite em memória
 * isolado (schema montado inline, mesmo padrão de {@code
 * TaxHistoryViewManualTest} — {@code Database.bootstrapSchema} é
 * package-private de outro pacote), com um {@link BackupService} fake (não
 * toca o arquivo {@code .db} real — esse caminho já é coberto separadamente
 * por {@code BackupServiceManualTest}, incluindo agora o cenário de {@code
 * eraseAllData()}).</p>
 */
public final class SettingsViewManualTest {

    private SettingsViewManualTest() {
    }

    public static void main(String[] args) throws Exception {
        Platform.startup(() -> {
        });

        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            runAllScenarios();
        } catch (Throwable t) {
            failure.set(t);
            t.printStackTrace();
        }

        Platform.exit();

        if (failure.get() != null) {
            System.out.println("FALHOU: " + failure.get().getMessage());
            System.exit(1);
        } else {
            System.out.println("TODOS OS CENARIOS PASSARAM (ATV-18)");
        }
    }

    private static void runAllScenarios() throws Exception {
        Connection connection = openInMemoryDatabase();
        SettingRepository settingRepository = new SettingRepositoryImpl(connection);
        FakeBackupService backupService = new FakeBackupService();
        AtomicInteger onDataRestoredCount = new AtomicInteger(0);
        Runnable onDataRestored = onDataRestoredCount::incrementAndGet;

        // Clientes reais (nunca chamados nestes cenarios - so exercitados se
        // "Testar conexao" for clicado, o que nenhum cenario abaixo faz) +
        // MarketService real sobre o mesmo banco em memoria, so para
        // satisfazer a nova dependencia de SettingsView (getRecentSyncs()).
        MarketService marketService = new MarketServiceImpl(new HgBrasilClientImpl(), new BrapiClientImpl(),
                new CoinGeckoClientImpl(), new RateHistoryRepositoryImpl(connection),
                new IndicatorHistoryRepositoryImpl(connection), new QuoteHistoryRepositoryImpl(connection),
                settingRepository);

        AtomicReference<SettingsView> viewRef = new AtomicReference<>();
        runOnFxAndWait(() -> viewRef.set(new SettingsView(settingRepository, backupService, onDataRestored,
                new HgBrasilClientImpl(), new BrapiClientImpl(), new CoinGeckoClientImpl(), marketService)));
        SettingsView view = viewRef.get();

        scenario1_defaultsEmptyAndMasked(view);
        scenario2_saveWritesToSettings(view, settingRepository);
        scenario3_discardRevertsUnsavedChanges(view, settingRepository);
        scenario4_invalidIntervalBlocksSave(view, settingRepository);
        scenario5_showHideToggle(view);
        scenario6_dangerZoneConfirmWordValidation(view);
        scenario7_eraseAllDataSeam(view, backupService);
        scenario8_backupExportImportSeams(view, backupService, onDataRestoredCount);

        connection.close();
    }

    // =====================================================================
    // Cenário 1 — estado inicial: campos vazios, chaves mascaradas por padrão (RT05)
    // =====================================================================

    private static void scenario1_defaultsEmptyAndMasked(SettingsView view) {
        assertEquals("", view.hgBrasilKeyForTest(), "chave HG Brasil deveria comecar vazia (nenhum setting salvo)");
        assertEquals("", view.brapiTokenForTest(), "token brapi deveria comecar vazio");
        assertEquals("", view.updateIntervalForTest(), "intervalo deveria comecar vazio (usa TTL padrao)");
        assertTrue(view.isSecretMaskedForTest(true), "chave HG Brasil deveria comecar mascarada (PasswordField visivel)");
        assertTrue(view.isSecretMaskedForTest(false), "token brapi deveria comecar mascarado");
        System.out.println("[OK] cenario 1: campos comecam vazios e chaves mascaradas por padrao");
    }

    // =====================================================================
    // Cenário 2 — "Salvar alterações" persiste em settings (SettingRepository)
    // =====================================================================

    private static void scenario2_saveWritesToSettings(SettingsView view, SettingRepository settingRepository) {
        view.setHgBrasilKeyForTest("chave-nova-do-usuario");
        view.setBrapiTokenForTest("token-novo-do-usuario");
        view.setUpdateIntervalForTest("30");
        view.saveForTest();

        assertEquals("chave-nova-do-usuario", settingRepository.get("hgbrasil.apiKey", null),
                "settings.hgbrasil.apiKey deveria ter sido persistido apos Salvar");
        assertEquals("token-novo-do-usuario", settingRepository.get("brapi.token", null),
                "settings.brapi.token deveria ter sido persistido apos Salvar");
        assertEquals("30", settingRepository.get("updateIntervalMinutes", null),
                "settings.updateIntervalMinutes deveria ter sido persistido apos Salvar");
        System.out.println("[OK] cenario 2: Salvar alteracoes grava os 3 campos em settings (SettingRepository)");
    }

    // =====================================================================
    // Cenário 3 — "Descartar" reverte edição não salva para o último valor persistido
    // =====================================================================

    private static void scenario3_discardRevertsUnsavedChanges(SettingsView view, SettingRepository settingRepository) {
        view.setHgBrasilKeyForTest("edicao-nao-salva");
        view.discardForTest();

        assertEquals("chave-nova-do-usuario", view.hgBrasilKeyForTest(),
                "Descartar deveria reverter para o ultimo valor salvo (cenario 2), nao manter a edicao nao salva");
        assertEquals("chave-nova-do-usuario", settingRepository.get("hgbrasil.apiKey", null),
                "settings nao deveria ter sido alterado por uma edicao descartada");
        System.out.println("[OK] cenario 3: Descartar reverte edicao nao salva para o ultimo valor persistido");
    }

    // =====================================================================
    // Cenário 4 — intervalo inválido bloqueia o Salvar (nenhum campo é persistido)
    // =====================================================================

    private static void scenario4_invalidIntervalBlocksSave(SettingsView view, SettingRepository settingRepository) {
        view.setHgBrasilKeyForTest("chave-que-nao-deveria-salvar");
        view.setUpdateIntervalForTest("abc");
        view.saveForTest();

        assertTrue(view.isUpdateIntervalErrorVisibleForTest(), "erro de validacao do intervalo deveria ficar visivel");
        assertEquals("chave-nova-do-usuario", settingRepository.get("hgbrasil.apiKey", null),
                "Salvar com intervalo invalido NAO deveria persistir NENHUM campo (nem os validos)");

        // idem para intervalo negativo/zero.
        view.setUpdateIntervalForTest("0");
        view.saveForTest();
        assertTrue(view.isUpdateIntervalErrorVisibleForTest(), "intervalo 0 tambem deveria ser rejeitado");

        // corrige e confirma que salva normalmente depois.
        view.setUpdateIntervalForTest("45");
        view.saveForTest();
        assertEquals("45", settingRepository.get("updateIntervalMinutes", null), "apos corrigir, deveria salvar normalmente");
        assertEquals("chave-que-nao-deveria-salvar", settingRepository.get("hgbrasil.apiKey", null),
                "apos corrigir o intervalo, o Salvar deveria persistir tambem os outros campos editados");
        System.out.println("[OK] cenario 4: intervalo invalido (nao-numerico e <= 0) bloqueia o Salvar; corrigido, salva normalmente");
    }

    // =====================================================================
    // Cenário 5 — botão "Mostrar/Ocultar" alterna a máscara sem perder o valor
    // =====================================================================

    private static void scenario5_showHideToggle(SettingsView view) {
        String beforeToggle = view.hgBrasilKeyForTest();
        assertTrue(view.isSecretMaskedForTest(true), "deveria comecar mascarado antes do toggle");

        view.toggleSecretVisibilityForTest(true);
        assertFalse(view.isSecretMaskedForTest(true), "apos clicar Mostrar, deveria ficar desmascarado");
        assertEquals(beforeToggle, view.hgBrasilKeyForTest(), "alternar a mascara nao deveria mudar o valor do campo");

        view.toggleSecretVisibilityForTest(true);
        assertTrue(view.isSecretMaskedForTest(true), "clicar Ocultar de novo deveria voltar a mascarar");
        assertEquals(beforeToggle, view.hgBrasilKeyForTest(), "valor continua o mesmo apos re-mascarar");
        System.out.println("[OK] cenario 5: toggle Mostrar/Ocultar alterna a mascara sem perder o valor digitado");
    }

    // =====================================================================
    // Cenário 6 — zona de perigo: validação da palavra de confirmação
    // =====================================================================

    private static void scenario6_dangerZoneConfirmWordValidation(SettingsView view) {
        assertFalse(view.isConfirmWordValid(""), "campo vazio nao deveria confirmar");
        assertFalse(view.isConfirmWordValid("apagar"), "minusculo nao deveria confirmar (case-sensitive, evita clique acidental)");
        assertFalse(view.isConfirmWordValid("APAGA"), "palavra incompleta nao deveria confirmar");
        assertFalse(view.isConfirmWordValid("APAGAR "), "espaco extra nao deveria confirmar");
        assertTrue(view.isConfirmWordValid("APAGAR"), "palavra exata deveria confirmar");
        System.out.println("[OK] cenario 6: so a palavra exata \"APAGAR\" habilita a confirmacao da zona de perigo");
    }

    // =====================================================================
    // Cenário 7 — "Apagar todos os dados" chama BackupService.eraseAllData() e recarrega o formulario
    // =====================================================================

    private static void scenario7_eraseAllDataSeam(SettingsView view, FakeBackupService backupService) {
        int before = backupService.eraseAllDataCalls;
        view.performEraseAllDataForTest();
        assertTrue(backupService.eraseAllDataCalls == before + 1, "performEraseAllDataForTest deveria chamar BackupService.eraseAllData() exatamente 1 vez");
        assertTrue(view.statusForTest().contains("apagados"), "status deveria informar que os dados foram apagados, veio: " + view.statusForTest());
        System.out.println("[OK] cenario 7: zona de perigo (seam de teste) chama BackupService.eraseAllData() e atualiza o status");
    }

    // =====================================================================
    // Cenário 8 — backup/restauração chamam BackupService com o Path certo + callback onDataRestored
    // =====================================================================

    private static void scenario8_backupExportImportSeams(SettingsView view, FakeBackupService backupService,
                                                            AtomicInteger onDataRestoredCount) throws IOException {
        File exportTarget = File.createTempFile("atv18-export", ".db");
        exportTarget.deleteOnExit();
        view.exportBackupForTest(exportTarget);
        assertTrue(backupService.createBackupCalls.contains(exportTarget.toPath()),
                "exportBackupForTest deveria chamar BackupService.createBackup com o Path exato do arquivo escolhido");

        File restoreSource = File.createTempFile("atv18-restore", ".db");
        restoreSource.deleteOnExit();
        int restoredBefore = onDataRestoredCount.get();
        view.performRestoreForTest(restoreSource);
        assertTrue(backupService.restoreBackupCalls.contains(restoreSource.toPath()),
                "performRestoreForTest deveria chamar BackupService.restoreBackup com o Path exato do arquivo escolhido");
        assertTrue(onDataRestoredCount.get() == restoredBefore + 1,
                "restauracao bem sucedida deveria disparar o callback onDataRestored exatamente 1 vez (rebuild do composition root, App.java)");
        System.out.println("[OK] cenario 8: exportar/restaurar backup chamam BackupService com o Path certo; "
                + "restaurar dispara o callback de reconstrucao do Shell (onDataRestored)");
    }

    // =====================================================================
    // Infra do teste
    // =====================================================================

    private static void runOnFxAndWait(Runnable action) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<RuntimeException> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (RuntimeException e) {
                error.set(e);
            } finally {
                latch.countDown();
            }
        });
        latch.await();
        if (error.get() != null) {
            throw error.get();
        }
    }

    private static Connection openInMemoryDatabase() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        String sql = readResource("/schema.sql");
        try (Statement st = connection.createStatement()) {
            for (String statement : sql.split(";")) {
                String trimmed = statement.trim();
                if (!trimmed.isEmpty()) {
                    st.execute(trimmed);
                }
            }
        }
        return connection;
    }

    private static String readResource(String resourcePath) {
        try (InputStream in = SettingsViewManualTest.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("Recurso nao encontrado no classpath: " + resourcePath);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(message + " (esperado=" + expected + ", encontrado=" + actual + ")");
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertFalse(boolean condition, String message) {
        if (condition) {
            throw new AssertionError(message);
        }
    }

    /**
     * Fake de {@link BackupService} — nunca toca o arquivo {@code .db} real
     * (esse caminho já é coberto por {@code BackupServiceManualTest}, ATV-19,
     * agora estendido com o cenário de {@code eraseAllData()}). Só registra
     * as chamadas recebidas, para testar a "cola" da {@link SettingsView}
     * (ela passa o {@link Path} certo, e trata sucesso/callback certo).
     */
    private static final class FakeBackupService implements BackupService {
        final List<Path> createBackupCalls = new ArrayList<>();
        final List<Path> restoreBackupCalls = new ArrayList<>();
        int eraseAllDataCalls = 0;

        @Override
        public void createBackup(Path destination) {
            createBackupCalls.add(destination);
        }

        @Override
        public void restoreBackup(Path backupFile) {
            restoreBackupCalls.add(backupFile);
        }

        @Override
        public void eraseAllData() {
            eraseAllDataCalls++;
        }
    }
}
