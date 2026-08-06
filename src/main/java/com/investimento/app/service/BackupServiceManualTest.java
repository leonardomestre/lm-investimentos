package com.investimento.app.service;

import com.investimento.app.data.Database;
import com.investimento.app.data.model.Asset;
import com.investimento.app.data.model.AssetType;
import com.investimento.app.data.model.Category;
import com.investimento.app.data.model.QuoteSource;
import com.investimento.app.repository.AssetRepository;
import com.investimento.app.repository.AssetRepositoryImpl;
import com.investimento.app.repository.SettingRepository;
import com.investimento.app.repository.SettingRepositoryImpl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

/**
 * Teste manual (main(), sem JUnit — mesma decisao das ATV-01 a 18) do
 * BackupService (ATV-19).
 *
 * Diferente de todo teste manual anterior deste projeto, este NAO roda
 * contra um SQLite em memoria isolado — nao ha como testar
 * BackupService.createBackup/restoreBackup sem exercitar o arquivo .db real
 * apontado por Database (a classe e um singleton com o caminho
 * %APPDATA%\InvestimentoApp\investimento.db fixo, de proposito — e
 * exatamente esse arquivo que o BackupService precisa fechar/substituir/
 * reabrir). Para nao destruir nenhum dado real do usuario/ambiente de dev:
 *
 * 1. Salva de lado (fora do repositorio, num diretorio scratch) uma copia
 *    exata do estado atual do arquivo .db (+ -wal/-shm, se existirem) antes
 *    de tocar em qualquer coisa.
 * 2. Roda o cenario descrito no passo 3 da atividade inteiramente sobre o
 *    arquivo real.
 * 3. Ao final (sucesso OU falha, bloco finally), fecha a conexao e
 *    restaura os arquivos salvos no passo 1 exatamente como estavam —
 *    inclusive apagando o .db se ele nao existia antes do teste comecar.
 */
public class BackupServiceManualTest {

    public static void main(String[] args) throws Exception {
        Path dbFile = Database.getDbFile();
        Path walFile = Path.of(dbFile + "-wal");
        Path shmFile = Path.of(dbFile + "-shm");
        Path safetyDir = Files.createTempDirectory("atv19-backup-safety");

        boolean dbExistedBefore = Files.exists(dbFile);
        Path savedDb = safetyDir.resolve("investimento.db.original");
        Path savedWal = safetyDir.resolve("investimento.db-wal.original");
        Path savedShm = safetyDir.resolve("investimento.db-shm.original");

        if (dbExistedBefore) {
            Files.copy(dbFile, savedDb, StandardCopyOption.REPLACE_EXISTING);
            if (Files.exists(walFile)) {
                Files.copy(walFile, savedWal, StandardCopyOption.REPLACE_EXISTING);
            }
            if (Files.exists(shmFile)) {
                Files.copy(shmFile, savedShm, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        System.out.println("[setup] db existia antes do teste? " + dbExistedBefore
                + " (copia de seguranca em " + safetyDir + ")");

        BackupService backupService = new BackupServiceImpl();
        Path backupFile = safetyDir.resolve("investimento-backup-teste.db");

        try {
            runScenario(backupService, dbFile, backupFile);
            System.out.println("\nTODOS OS CENARIOS PASSARAM.");
        } finally {
            Database.close();
            System.out.println("\n[cleanup] restaurando arquivo .db real ao estado anterior ao teste...");
            if (dbExistedBefore) {
                Files.copy(savedDb, dbFile, StandardCopyOption.REPLACE_EXISTING);
                Files.deleteIfExists(walFile);
                Files.deleteIfExists(shmFile);
                if (Files.exists(savedWal)) {
                    Files.copy(savedWal, walFile, StandardCopyOption.REPLACE_EXISTING);
                }
                if (Files.exists(savedShm)) {
                    Files.copy(savedShm, shmFile, StandardCopyOption.REPLACE_EXISTING);
                }
            } else {
                Files.deleteIfExists(dbFile);
                Files.deleteIfExists(walFile);
                Files.deleteIfExists(shmFile);
            }
            deleteRecursive(safetyDir);
            System.out.println("[cleanup] concluido — arquivo real restaurado, nada de teste deixado para tras.");
        }
    }

    private static void runScenario(BackupService backupService, Path dbFile, Path backupFile) throws Exception {
        // Estado inicial: abre a conexao real (cria o schema se o .db nao
        // existia antes) e limpa qualquer residuo de uma execucao anterior
        // deste MESMO teste (tickers previsiveis, caso o processo tenha sido
        // interrompido no meio antes do cleanup rodar).
        Connection conn = Database.getConnection();
        AssetRepository assetRepository = new AssetRepositoryImpl(conn);
        purgeTestAssets(assetRepository);

        // Cenario 1: cadastra o "ativo antigo", gera backup, confirma que o
        // arquivo de backup - aberto em uma conexao SEPARADA - contem esse
        // ativo (valida "backup gerado contem todos os dados ate o momento
        // da geracao", sem confiar so no service que acabou de escrever).
        Asset older = assetRepository.insert(Asset.builder()
                .type(AssetType.STOCK)
                .category(Category.STOCKS)
                .ticker("ATV19OLD")
                .displayName("ATV-19 Teste - ativo antigo")
                .currency("BRL")
                .quoteSource(QuoteSource.MANUAL)
                .active(true)
                .build());
        System.out.println("[cenario 1] ativo antigo inserido, id=" + older.getId());

        backupService.createBackup(backupFile);
        if (!Files.isRegularFile(backupFile)) {
            throw new AssertionError("createBackup nao gerou o arquivo de destino");
        }
        System.out.println("[cenario 1] backup gerado em " + backupFile
                + " (" + Files.size(backupFile) + " bytes)");

        int tableCountInBackup = countTables(backupFile);
        if (tableCountInBackup < 8) {
            throw new AssertionError("backup com menos tabelas do que o schema esperado: " + tableCountInBackup);
        }
        boolean olderPresentInBackupFile = tickerExistsInDbFile(backupFile, "ATV19OLD");
        if (!olderPresentInBackupFile) {
            throw new AssertionError("ATV19OLD nao encontrado dentro do ARQUIVO de backup (aberto separadamente)");
        }
        System.out.println("[cenario 1] OK — backup tem " + tableCountInBackup
                + " tabelas e contem ATV19OLD (verificado abrindo o arquivo de backup em conexao separada)");

        // Cenario 2: cadastra um "ativo novo" DEPOIS do backup, confirma que
        // ele esta na base atual mas nao no arquivo de backup (prova que o
        // backup e um snapshot do momento em que foi gerado, nao uma copia
        // ao vivo).
        Asset newer = assetRepository.insert(Asset.builder()
                .type(AssetType.STOCK)
                .category(Category.STOCKS)
                .ticker("ATV19NEW")
                .displayName("ATV-19 Teste - ativo novo")
                .currency("BRL")
                .quoteSource(QuoteSource.MANUAL)
                .active(true)
                .build());
        System.out.println("[cenario 2] ativo novo inserido, id=" + newer.getId());

        boolean newerPresentInCurrentDb = findByTicker(assetRepository, "ATV19NEW").isPresent();
        boolean newerPresentInBackupFile = tickerExistsInDbFile(backupFile, "ATV19NEW");
        if (!newerPresentInCurrentDb) {
            throw new AssertionError("ATV19NEW deveria estar na base atual");
        }
        if (newerPresentInBackupFile) {
            throw new AssertionError("ATV19NEW nao deveria estar no backup gerado ANTES dele existir");
        }
        System.out.println("[cenario 2] OK — ATV19NEW existe na base atual e NAO existe no arquivo de backup antigo");

        // Cenario 3 (o pedido explicitamente pelo passo 3 da atividade):
        // restaura o backup antigo e confirma que o ativo novo sumiu.
        backupService.restoreBackup(backupFile);
        System.out.println("[cenario 3] restoreBackup concluido");

        Connection reopened = Database.getConnection();
        if (reopened == conn) {
            throw new AssertionError("Database.getConnection() deveria devolver uma conexao NOVA apos restoreBackup");
        }
        AssetRepository assetRepositoryAfterRestore = new AssetRepositoryImpl(reopened);

        boolean newerStillPresentAfterRestore = findByTicker(assetRepositoryAfterRestore, "ATV19NEW").isPresent();
        boolean olderStillPresentAfterRestore = findByTicker(assetRepositoryAfterRestore, "ATV19OLD").isPresent();
        if (newerStillPresentAfterRestore) {
            throw new AssertionError("ATV19NEW deveria ter sumido apos restaurar o backup antigo");
        }
        if (!olderStillPresentAfterRestore) {
            throw new AssertionError("ATV19OLD deveria continuar presente apos restaurar o backup antigo");
        }
        System.out.println("[cenario 3] OK — apos restoreBackup: ATV19NEW sumiu, ATV19OLD continua presente"
                + " (prova que a restauracao substituiu o banco de verdade, nao so copiou por cima sem efeito)");

        // Cenario 4 (ATV-18 — zona de perigo "Apagar todos os dados"):
        // eraseAllData() apaga assets/settings (e, por ON DELETE CASCADE,
        // transactions/quote_history/distributions), SEM fechar/reabrir a
        // conexao — diferente de restoreBackup, a mesma Connection/repository
        // ja construidos continuam validos depois de chamar isto.
        SettingRepository settingRepository = new SettingRepositoryImpl(reopened);
        Asset beforeErase = assetRepositoryAfterRestore.insert(Asset.builder()
                .type(AssetType.STOCK)
                .category(Category.STOCKS)
                .ticker("ATV19ERASE")
                .displayName("ATV-18 Teste - sera apagado")
                .currency("BRL")
                .quoteSource(QuoteSource.MANUAL)
                .active(true)
                .build());
        settingRepository.save("hgbrasil.apiKey", "chave-de-teste-sera-apagada");
        System.out.println("[cenario 4] ativo (id=" + beforeErase.getId() + ") e setting inseridos antes do eraseAllData()");

        backupService.eraseAllData();
        System.out.println("[cenario 4] eraseAllData() concluido");

        boolean assetStillPresent = findByTicker(assetRepositoryAfterRestore, "ATV19ERASE").isPresent();
        if (assetStillPresent) {
            throw new AssertionError("ATV19ERASE deveria ter sumido apos eraseAllData()");
        }
        String settingAfterErase = settingRepository.get("hgbrasil.apiKey", null);
        if (settingAfterErase != null) {
            throw new AssertionError("settings.hgbrasil.apiKey deveria ter sido apagado, veio: " + settingAfterErase);
        }
        // Confirma que a MESMA Connection (reopened) continua valida depois de
        // eraseAllData() - diferente de restoreBackup, nao houve reopen aqui,
        // entao um novo insert pela MESMA instancia de repository precisa funcionar.
        Asset afterErase = assetRepositoryAfterRestore.insert(Asset.builder()
                .type(AssetType.STOCK)
                .category(Category.STOCKS)
                .ticker("ATV19POSERASE")
                .displayName("ATV-18 Teste - apos eraseAllData")
                .currency("BRL")
                .quoteSource(QuoteSource.MANUAL)
                .active(true)
                .build());
        if (afterErase.getId() == null) {
            throw new AssertionError("insert apos eraseAllData() deveria funcionar normalmente (mesma conexao, sem reopen)");
        }
        System.out.println("[cenario 4] OK — assets/settings apagados, e a MESMA conexao/repository continuam"
                + " funcionais depois (novo insert id=" + afterErase.getId() + "), sem precisar de restart/reopen");

        // Limpa os ativos de teste da conexao reaberta (fica dentro do
        // arquivo .db real ate o bloco finally do main() restaurar o
        // arquivo original por cima de qualquer forma, mas nao custa nada
        // deixar o dado de teste explicitamente removido tambem).
        purgeTestAssets(assetRepositoryAfterRestore);
    }

    private static Optional<Asset> findByTicker(AssetRepository repository, String ticker) {
        return repository.listAssets(true).stream()
                .filter(a -> ticker.equals(a.getTicker()))
                .findFirst();
    }

    private static void purgeTestAssets(AssetRepository repository) {
        List<Asset> all = repository.listAssets(true);
        for (Asset asset : all) {
            if (asset.getTicker() != null && asset.getTicker().startsWith("ATV19")) {
                hardDelete(asset.getId());
            }
        }
    }

    private static void hardDelete(long assetId) {
        // AssetRepository.remove(...) e soft delete (is_active=0) — para o
        // teste ficar limpo de verdade entre execucoes, apaga a linha
        // fisicamente via SQL direto (nao existe um "delete fisico" na
        // interface publica de AssetRepository, e nao ha motivo para
        // adiciona-lo so por causa deste teste).
        try (Statement st = Database.getConnection().createStatement()) {
            st.execute("DELETE FROM transactions WHERE asset_id = " + assetId + ";");
            st.execute("DELETE FROM assets WHERE id = " + assetId + ";");
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private static int countTables(Path dbFile) throws SQLException {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT count(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%';")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static boolean tickerExistsInDbFile(Path dbFile, String ticker) throws SQLException {
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM assets WHERE ticker = '" + ticker + "';")) {
            rs.next();
            return rs.getInt(1) > 0;
        }
    }

    private static void deleteRecursive(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            // best-effort, diretorio temporario do SO
                        }
                    });
        }
    }
}
