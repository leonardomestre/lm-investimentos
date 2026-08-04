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

    private static Connection open() {
        try {
            Path dbDir = Path.of(System.getenv("APPDATA"), "InvestimentoApp");
            Files.createDirectories(dbDir);
            Path dbFile = dbDir.resolve("investimento.db");

            Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA foreign_keys = ON;");
                st.execute("PRAGMA journal_mode = WAL;");
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
}
