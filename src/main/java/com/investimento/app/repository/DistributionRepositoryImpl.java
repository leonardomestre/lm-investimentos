package com.investimento.app.repository;

import com.investimento.app.data.model.Distribution;
import com.investimento.app.data.model.DistributionType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class DistributionRepositoryImpl implements DistributionRepository {

    private final Connection connection;

    public DistributionRepositoryImpl(Connection connection) {
        this.connection = connection;
    }

    @Override
    public Distribution insert(Distribution distribution) {
        String sql = "INSERT INTO distributions (asset_id, type, payment_date, value, notes, created_at) "
                + "VALUES (?,?,?,?,?,?)";
        try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, distribution.getAssetId());
            ps.setString(2, distribution.getType().name());
            ps.setString(3, distribution.getPaymentDate().toString());
            ps.setDouble(4, distribution.getValue());
            ps.setString(5, distribution.getNotes());
            LocalDateTime createdAt = distribution.getCreatedAt() != null ? distribution.getCreatedAt() : LocalDateTime.now();
            ps.setString(6, SqlDateTimeUtil.format(createdAt));
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    distribution.setId(keys.getLong(1));
                }
            }
            distribution.setCreatedAt(createdAt);
            return distribution;
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao inserir Distribution", e);
        }
    }

    @Override
    public List<Distribution> listByAsset(long assetId) {
        String sql = "SELECT * FROM distributions WHERE asset_id = ? ORDER BY payment_date";
        List<Distribution> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, assetId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(map(rs));
                }
            }
            return result;
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao listar Distributions por ativo", e);
        }
    }

    @Override
    public void delete(long id) {
        String sql = "DELETE FROM distributions WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao excluir Distribution", e);
        }
    }

    private Distribution map(ResultSet rs) throws SQLException {
        return Distribution.builder()
                .id(rs.getLong("id"))
                .assetId(rs.getLong("asset_id"))
                .type(DistributionType.valueOf(rs.getString("type")))
                .paymentDate(LocalDate.parse(rs.getString("payment_date")))
                .value(rs.getDouble("value"))
                .notes(rs.getString("notes"))
                .createdAt(SqlDateTimeUtil.parse(rs.getString("created_at")))
                .build();
    }
}
