package com.investimento.app.repository;

import com.investimento.app.data.model.QuoteHistory;
import com.investimento.app.data.model.QuoteSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class QuoteHistoryRepositoryImpl implements QuoteHistoryRepository {

    private final Connection connection;

    public QuoteHistoryRepositoryImpl(Connection connection) {
        this.connection = connection;
    }

    @Override
    public Optional<QuoteHistory> findById(long id) {
        String sql = "SELECT * FROM quote_history WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao buscar QuoteHistory por id", e);
        }
    }

    @Override
    public List<QuoteHistory> listByAsset(long assetId) {
        String sql = "SELECT * FROM quote_history WHERE asset_id = ? ORDER BY date";
        List<QuoteHistory> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, assetId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(map(rs));
                }
            }
            return result;
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao listar QuoteHistory por ativo", e);
        }
    }

    @Override
    public Optional<QuoteHistory> findLatestByAsset(long assetId) {
        // `date` e TEXT em ISO-8601 (yyyy-MM-dd), entao a ordem lexicografica
        // do SQLite coincide com a cronologica — ORDER BY date DESC LIMIT 1
        // devolve mesmo a cotacao mais recente.
        String sql = "SELECT * FROM quote_history WHERE asset_id = ? ORDER BY date DESC LIMIT 1";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, assetId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao buscar a cotacao mais recente do ativo", e);
        }
    }

    @Override
    public QuoteHistory upsert(QuoteHistory quoteHistory) {
        String sql = "INSERT INTO quote_history (asset_id, date, price, source) VALUES (?, ?, ?, ?) "
                + "ON CONFLICT(asset_id, date) DO UPDATE SET price = excluded.price, source = excluded.source, "
                + "fetched_at = datetime('now')";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, quoteHistory.getAssetId());
            ps.setString(2, quoteHistory.getDate().toString());
            ps.setDouble(3, quoteHistory.getPrice());
            ps.setString(4, quoteHistory.getSource().name());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao fazer upsert de QuoteHistory", e);
        }
        return findByAssetAndDate(quoteHistory.getAssetId(), quoteHistory.getDate())
                .orElseThrow(() -> new IllegalStateException("QuoteHistory nao encontrado apos upsert"));
    }

    @Override
    public int upsertAll(List<QuoteHistory> quotes) {
        if (quotes == null || quotes.isEmpty()) {
            return 0;
        }
        String sql = "INSERT INTO quote_history (asset_id, date, price, source) VALUES (?, ?, ?, ?) "
                + "ON CONFLICT(asset_id, date) DO UPDATE SET price = excluded.price, source = excluded.source, "
                + "fetched_at = datetime('now')";

        boolean previousAutoCommit;
        try {
            previousAutoCommit = connection.getAutoCommit();
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao preparar a gravacao em lote de QuoteHistory", e);
        }

        try {
            connection.setAutoCommit(false);
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                for (QuoteHistory quote : quotes) {
                    ps.setLong(1, quote.getAssetId());
                    ps.setString(2, quote.getDate().toString());
                    ps.setDouble(3, quote.getPrice());
                    ps.setString(4, quote.getSource().name());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            connection.commit();
            return quotes.size();
        } catch (SQLException e) {
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                e.addSuppressed(rollbackFailure);
            }
            throw new RuntimeException("Erro ao gravar QuoteHistory em lote", e);
        } finally {
            // A conexao e unica e compartilhada por todos os repositories: se o
            // autocommit nao voltar ao estado anterior, toda escrita posterior
            // feita por outra tela ficaria pendente sem commit.
            try {
                connection.setAutoCommit(previousAutoCommit);
            } catch (SQLException e) {
                throw new RuntimeException("Erro ao restaurar o autocommit apos gravacao em lote", e);
            }
        }
    }

    private Optional<QuoteHistory> findByAssetAndDate(long assetId, LocalDate date) {
        String sql = "SELECT * FROM quote_history WHERE asset_id = ? AND date = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, assetId);
            ps.setString(2, date.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao buscar QuoteHistory por ativo e data", e);
        }
    }

    @Override
    public void delete(long id) {
        String sql = "DELETE FROM quote_history WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao excluir QuoteHistory", e);
        }
    }

    private QuoteHistory map(ResultSet rs) throws SQLException {
        return QuoteHistory.builder()
                .id(rs.getLong("id"))
                .assetId(rs.getLong("asset_id"))
                .date(LocalDate.parse(rs.getString("date")))
                .price(rs.getDouble("price"))
                .source(QuoteSource.valueOf(rs.getString("source")))
                .fetchedAt(SqlDateTimeUtil.parse(rs.getString("fetched_at")))
                .build();
    }
}
