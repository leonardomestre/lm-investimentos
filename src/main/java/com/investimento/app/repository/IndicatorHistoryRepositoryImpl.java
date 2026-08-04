package com.investimento.app.repository;

import com.investimento.app.data.model.IndicatorHistory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class IndicatorHistoryRepositoryImpl implements IndicatorHistoryRepository {

    private final Connection connection;

    public IndicatorHistoryRepositoryImpl(Connection connection) {
        this.connection = connection;
    }

    @Override
    public Optional<IndicatorHistory> findById(long id) {
        String sql = "SELECT * FROM indicator_history WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao buscar IndicatorHistory por id", e);
        }
    }

    @Override
    public List<IndicatorHistory> listByTicker(String ticker) {
        String sql = "SELECT * FROM indicator_history WHERE ticker = ? ORDER BY period";
        List<IndicatorHistory> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, ticker);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(map(rs));
                }
            }
            return result;
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao listar IndicatorHistory por ticker", e);
        }
    }

    @Override
    public IndicatorHistory upsert(IndicatorHistory indicatorHistory) {
        String sql = "INSERT INTO indicator_history (ticker, period, value) VALUES (?,?,?) "
                + "ON CONFLICT(ticker, period) DO UPDATE SET value = excluded.value, fetched_at = datetime('now')";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, indicatorHistory.getTicker());
            ps.setString(2, indicatorHistory.getPeriod());
            ps.setDouble(3, indicatorHistory.getValue());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao fazer upsert de IndicatorHistory", e);
        }
        return findByTickerAndPeriod(indicatorHistory.getTicker(), indicatorHistory.getPeriod())
                .orElseThrow(() -> new IllegalStateException("IndicatorHistory nao encontrado apos upsert"));
    }

    private Optional<IndicatorHistory> findByTickerAndPeriod(String ticker, String period) {
        String sql = "SELECT * FROM indicator_history WHERE ticker = ? AND period = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, ticker);
            ps.setString(2, period);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao buscar IndicatorHistory por ticker e periodo", e);
        }
    }

    @Override
    public void delete(long id) {
        String sql = "DELETE FROM indicator_history WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao excluir IndicatorHistory", e);
        }
    }

    private IndicatorHistory map(ResultSet rs) throws SQLException {
        return IndicatorHistory.builder()
                .id(rs.getLong("id"))
                .ticker(rs.getString("ticker"))
                .period(rs.getString("period"))
                .value(rs.getDouble("value"))
                .fetchedAt(SqlDateTimeUtil.parse(rs.getString("fetched_at")))
                .build();
    }
}
