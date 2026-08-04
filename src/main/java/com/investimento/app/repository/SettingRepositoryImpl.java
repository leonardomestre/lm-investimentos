package com.investimento.app.repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class SettingRepositoryImpl implements SettingRepository {

    private final Connection connection;

    public SettingRepositoryImpl(Connection connection) {
        this.connection = connection;
    }

    @Override
    public String get(String key, String defaultValue) {
        // "key" e palavra reservada em SQL — sempre entre aspas duplas aqui.
        String sql = "SELECT value FROM settings WHERE \"key\" = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("value") : defaultValue;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao buscar Setting", e);
        }
    }

    @Override
    public void save(String key, String value) {
        String sql = "INSERT INTO settings (\"key\", value) VALUES (?, ?) "
                + "ON CONFLICT(\"key\") DO UPDATE SET value = excluded.value";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao salvar Setting", e);
        }
    }
}
