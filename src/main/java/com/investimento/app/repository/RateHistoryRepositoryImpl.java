package com.investimento.app.repository;

import com.investimento.app.data.model.RateHistory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class RateHistoryRepositoryImpl implements RateHistoryRepository {

    private final Connection connection;

    public RateHistoryRepositoryImpl(Connection connection) {
        this.connection = connection;
    }

    @Override
    public Optional<RateHistory> findByDate(LocalDate date) {
        String sql = "SELECT * FROM rate_history WHERE date = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, date.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao buscar RateHistory por data", e);
        }
    }

    @Override
    public Optional<RateHistory> findMostRecent() {
        String sql = "SELECT * FROM rate_history ORDER BY date DESC LIMIT 1";
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? Optional.of(map(rs)) : Optional.empty();
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao buscar RateHistory mais recente", e);
        }
    }

    @Override
    public List<RateHistory> listAll() {
        String sql = "SELECT * FROM rate_history ORDER BY date";
        List<RateHistory> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(map(rs));
            }
            return result;
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao listar RateHistory", e);
        }
    }

    @Override
    public RateHistory upsert(RateHistory rateHistory) {
        String sql = "INSERT INTO rate_history (date, cdi, selic, cdi_daily, selic_daily, daily_factor) "
                + "VALUES (?,?,?,?,?,?) "
                + "ON CONFLICT(date) DO UPDATE SET cdi = excluded.cdi, selic = excluded.selic, "
                + "cdi_daily = excluded.cdi_daily, selic_daily = excluded.selic_daily, "
                + "daily_factor = excluded.daily_factor, fetched_at = datetime('now')";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, rateHistory.getDate().toString());
            ps.setDouble(2, rateHistory.getCdi());
            ps.setDouble(3, rateHistory.getSelic());
            setNullableDouble(ps, 4, rateHistory.getCdiDaily());
            setNullableDouble(ps, 5, rateHistory.getSelicDaily());
            setNullableDouble(ps, 6, rateHistory.getDailyFactor());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao fazer upsert de RateHistory", e);
        }
        return findByDate(rateHistory.getDate())
                .orElseThrow(() -> new IllegalStateException("RateHistory nao encontrado apos upsert"));
    }

    @Override
    public void delete(LocalDate date) {
        String sql = "DELETE FROM rate_history WHERE date = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, date.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao excluir RateHistory", e);
        }
    }

    private void setNullableDouble(PreparedStatement ps, int index, Double value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.REAL);
        } else {
            ps.setDouble(index, value);
        }
    }

    private RateHistory map(ResultSet rs) throws SQLException {
        double cdiDaily = rs.getDouble("cdi_daily");
        boolean cdiDailyNull = rs.wasNull();
        double selicDaily = rs.getDouble("selic_daily");
        boolean selicDailyNull = rs.wasNull();
        double dailyFactor = rs.getDouble("daily_factor");
        boolean dailyFactorNull = rs.wasNull();

        return RateHistory.builder()
                .date(LocalDate.parse(rs.getString("date")))
                .cdi(rs.getDouble("cdi"))
                .selic(rs.getDouble("selic"))
                .cdiDaily(cdiDailyNull ? null : cdiDaily)
                .selicDaily(selicDailyNull ? null : selicDaily)
                .dailyFactor(dailyFactorNull ? null : dailyFactor)
                .fetchedAt(SqlDateTimeUtil.parse(rs.getString("fetched_at")))
                .build();
    }
}
