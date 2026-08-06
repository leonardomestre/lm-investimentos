package com.investimento.app.data;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Abre a conexao unica com o banco SQLite local do usuario e garante que o
 * schema (schema.sql) exista antes de qualquer uso. Classe concreta, sem
 * interface: so existe uma forma plausivel de "abrir o SQLite local" neste
 * projeto (ver ATV-02).
 */
public final class Database {

    private static final String SCHEMA_RESOURCE = "/schema.sql";

    private static Connection connection;

    private Database() {
    }

    /**
     * Retorna a conexao unica, viva pelo tempo de vida do app. Abre (cria
     * diretorio/arquivo/schema) na primeira chamada.
     */
    public static synchronized Connection getConnection() {
        if (connection == null) {
            connection = open();
        }
        return connection;
    }

    /**
     * Caminho do arquivo .db no disco (%APPDATA%\InvestimentoApp\investimento.db),
     * sem abrir/tocar a conexao. Usado pelo BackupService (ATV-19) para
     * copiar/substituir o arquivo diretamente.
     */
    public static Path getDbFile() {
        return resolveDbFile();
    }

    private static Path resolveDbFile() {
        Path dbDir = Path.of(System.getenv("APPDATA"), "InvestimentoApp");
        return dbDir.resolve("investimento.db");
    }

    private static Connection open() {
        try {
            Path dbDir = Path.of(System.getenv("APPDATA"), "InvestimentoApp");
            Files.createDirectories(dbDir);
            Path dbFile = resolveDbFile();

            Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA foreign_keys = ON;");
                st.execute("PRAGMA journal_mode = WAL;");
                // A UI e as Task de background (atualizacao de cotacoes, seed
                // de historico) usam esta mesma conexao. Sem busy_timeout, um
                // lock disputado falha na hora com SQLITE_BUSY e derruba a
                // operacao; com ele, o SQLite espera ate 5s antes de desistir.
                st.execute("PRAGMA busy_timeout = 5000;");
            }
            bootstrapSchema(conn);
            return conn;
        } catch (IOException | SQLException e) {
            throw new IllegalStateException("Falha ao abrir o banco de dados local", e);
        }
    }

    /**
     * Executa o schema.sql inteiro (todo CREATE usa IF NOT EXISTS, seguro
     * rodar toda vez que o app abre). Pacote-privado: reutilizado pelo
     * teste manual da camada de repository (RepositoryManualTest, mesmo
     * pacote), que roda contra um banco em memoria isolado do real.
     */
    static void bootstrapSchema(Connection conn) throws SQLException {
        String sql = readResource(SCHEMA_RESOURCE);
        try (Statement st = conn.createStatement()) {
            for (String statement : sql.split(";")) {
                String trimmed = statement.trim();
                if (!trimmed.isEmpty()) {
                    st.execute(trimmed);
                }
            }
        }
    }

    private static String readResource(String resourcePath) {
        try (InputStream in = Database.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("Recurso nao encontrado no classpath: " + resourcePath);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Fecha a conexao. Chamar no Platform.exit()/stop() da Application (ver
     * ATV-07, shell da aplicacao).
     */
    public static synchronized void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                // Encerrando a aplicacao - nao ha o que fazer alem de ignorar.
            } finally {
                connection = null;
            }
        }
    }

    /**
     * Fecha a conexao ativa (se houver) e abre uma nova, apontando para o
     * mesmo arquivo .db no disco (que pode ter sido substituido por fora,
     * ver BackupService.restoreBackup, ATV-19). Depois de chamar isto,
     * qualquer codigo que ja tinha guardado a Connection anterior (em vez de
     * chamar Database.getConnection() de novo a cada uso) fica com uma
     * conexao fechada/obsoleta - por isso restaurar um backup so e seguro
     * enquanto nenhuma outra operacao de banco esta em andamento (ver
     * BackupService).
     */
    public static synchronized Connection reopen() {
        close();
        connection = open();
        return connection;
    }
}
