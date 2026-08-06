package com.investimento.app.service;

import com.investimento.app.data.Database;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public class BackupServiceImpl implements BackupService {

    @Override
    public synchronized void createBackup(Path destination) throws IOException {
        Connection conn = Database.getConnection();
        try (Statement st = conn.createStatement()) {
            // Obrigatorio: com journal_mode = WAL (ATV-02), escritas recentes
            // podem estar so no arquivo -wal auxiliar e nao no .db principal.
            // Sem este checkpoint o backup pode sair "quase certo" mas
            // faltando as ultimas transacoes.
            st.execute("PRAGMA wal_checkpoint(FULL);");
        } catch (SQLException e) {
            throw new IOException("Falha ao forcar checkpoint do WAL antes do backup", e);
        }

        Path dbFile = Database.getDbFile();
        Files.copy(dbFile, destination, StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public synchronized void restoreBackup(Path backupFile) throws IOException {
        if (!Files.isRegularFile(backupFile)) {
            throw new IOException("Arquivo de backup nao encontrado: " + backupFile);
        }

        Database.close();

        Path dbFile = Database.getDbFile();
        Files.copy(backupFile, dbFile, StandardCopyOption.REPLACE_EXISTING);

        // Arquivos auxiliares do WAL da conexao anterior nao tem mais relacao
        // com o arquivo .db recem-restaurado - descartar para o SQLite nao
        // tentar reaplicar frames antigos por cima do backup.
        Files.deleteIfExists(Path.of(dbFile + "-wal"));
        Files.deleteIfExists(Path.of(dbFile + "-shm"));

        Database.reopen();
    }
}
