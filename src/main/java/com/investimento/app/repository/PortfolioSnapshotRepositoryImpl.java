package com.investimento.app.repository;

import com.investimento.app.data.model.PortfolioSnapshot;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class PortfolioSnapshotRepositoryImpl implements PortfolioSnapshotRepository {

    private final Connection connection;

    public PortfolioSnapshotRepositoryImpl(Connection connection) {
        this.connection = connection;
    }

    @Override
    public Optional<PortfolioSnapshot> findByDate(LocalDate date) {
        String sql = "SELECT * FROM portfolio_snapshot WHERE date = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, date.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao buscar PortfolioSnapshot por data", e);
        }
    }

    @Override
    public List<PortfolioSnapshot> listAll() {
        String sql = "SELECT * FROM portfolio_snapshot ORDER BY date";
        List<PortfolioSnapshot> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(map(rs));
            }
            return result;
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao listar PortfolioSnapshot", e);
        }
    }

    @Override
    public PortfolioSnapshot insert(PortfolioSnapshot snapshot) {
        String sql = "INSERT INTO portfolio_snapshot (date, total_value, invested_value, fetched_at) "
                + "VALUES (?,?,?,?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, snapshot.getDate().toString());
            ps.setDouble(2, snapshot.getTotalValue());
            ps.setDouble(3, snapshot.getInvestedValue());
            LocalDateTime fetchedAt = snapshot.getFetchedAt() != null ? snapshot.getFetchedAt() : LocalDateTime.now();
            ps.setString(4, SqlDateTimeUtil.format(fetchedAt));
            ps.executeUpdate();
            snapshot.setFetchedAt(fetchedAt);
            return snapshot;
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao inserir PortfolioSnapshot", e);
        }
    }

    @Override
    public void update(PortfolioSnapshot snapshot) {
        String sql = "UPDATE portfolio_snapshot SET total_value=?, invested_value=? WHERE date=?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setDouble(1, snapshot.getTotalValue());
            ps.setDouble(2, snapshot.getInvestedValue());
            ps.setString(3, snapshot.getDate().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao atualizar PortfolioSnapshot", e);
        }
    }

    @Override
    public void delete(LocalDate date) {
        String sql = "DELETE FROM portfolio_snapshot WHERE date = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, date.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao excluir PortfolioSnapshot", e);
        }
    }

    private PortfolioSnapshot map(ResultSet rs) throws SQLException {
        return PortfolioSnapshot.builder()
                .date(LocalDate.parse(rs.getString("date")))
                .totalValue(rs.getDouble("total_value"))
                .investedValue(rs.getDouble("invested_value"))
                .fetchedAt(SqlDateTimeUtil.parse(rs.getString("fetched_at")))
                .build();
    }
}
