package com.investimento.app.repository;

import com.investimento.app.data.model.OperationType;
import com.investimento.app.data.model.Transaction;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class TransactionRepositoryImpl implements TransactionRepository {

    private final Connection connection;

    public TransactionRepositoryImpl(Connection connection) {
        this.connection = connection;
    }

    @Override
    public Optional<Transaction> findById(long id) {
        String sql = "SELECT * FROM transactions WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao buscar Transaction por id", e);
        }
    }

    /**
     * O desempate por {@code id} nao e cosmetico: {@code PositionService}
     * percorre esta lista aplicando custo medio, e o resultado de uma compra e
     * uma venda no MESMO dia depende da ordem em que elas aparecem. Sem o
     * segundo criterio, a ordem entre linhas de data igual e a que o SQLite
     * resolver, podendo mudar de execucao para execucao (e com ela o preco
     * medio e o ganho apurado). {@code id} e crescente por ordem de cadastro,
     * que e o desempate mais proximo da intencao do usuario.
     */
    @Override
    public List<Transaction> listAll() {
        String sql = "SELECT * FROM transactions ORDER BY date, id";
        List<Transaction> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(map(rs));
            }
            return result;
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao listar Transactions", e);
        }
    }

    @Override
    public List<Transaction> listByAsset(long assetId) {
        String sql = "SELECT * FROM transactions WHERE asset_id = ? ORDER BY date, id";
        List<Transaction> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, assetId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(map(rs));
                }
            }
            return result;
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao listar Transactions por ativo", e);
        }
    }

    @Override
    public List<Transaction> listByPeriod(LocalDate start, LocalDate end) {
        String sql = "SELECT * FROM transactions WHERE date BETWEEN ? AND ? ORDER BY date, id";
        List<Transaction> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, start.toString());
            ps.setString(2, end.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(map(rs));
                }
            }
            return result;
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao listar Transactions por periodo", e);
        }
    }

    @Override
    public Transaction insert(Transaction transaction) {
        String sql = "INSERT INTO transactions (asset_id, operation_type, date, quantity, unit_price, fees, "
                + "notes, created_at) VALUES (?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, transaction.getAssetId());
            ps.setString(2, transaction.getOperationType().name());
            ps.setString(3, transaction.getDate().toString());
            ps.setDouble(4, transaction.getQuantity());
            ps.setDouble(5, transaction.getUnitPrice());
            ps.setDouble(6, transaction.getFees());
            ps.setString(7, transaction.getNotes());
            LocalDateTime createdAt = transaction.getCreatedAt() != null ? transaction.getCreatedAt() : LocalDateTime.now();
            ps.setString(8, SqlDateTimeUtil.format(createdAt));

            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    transaction.setId(keys.getLong(1));
                }
            }
            transaction.setCreatedAt(createdAt);
            return transaction;
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao inserir Transaction", e);
        }
    }

    @Override
    public void update(Transaction transaction) {
        String sql = "UPDATE transactions SET asset_id=?, operation_type=?, date=?, quantity=?, unit_price=?, "
                + "fees=?, notes=? WHERE id=?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, transaction.getAssetId());
            ps.setString(2, transaction.getOperationType().name());
            ps.setString(3, transaction.getDate().toString());
            ps.setDouble(4, transaction.getQuantity());
            ps.setDouble(5, transaction.getUnitPrice());
            ps.setDouble(6, transaction.getFees());
            ps.setString(7, transaction.getNotes());
            ps.setLong(8, transaction.getId());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao atualizar Transaction", e);
        }
    }

    @Override
    public void delete(long id) {
        String sql = "DELETE FROM transactions WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao excluir Transaction", e);
        }
    }

    private Transaction map(ResultSet rs) throws SQLException {
        return Transaction.builder()
                .id(rs.getLong("id"))
                .assetId(rs.getLong("asset_id"))
                .operationType(OperationType.valueOf(rs.getString("operation_type")))
                .date(LocalDate.parse(rs.getString("date")))
                .quantity(rs.getDouble("quantity"))
                .unitPrice(rs.getDouble("unit_price"))
                .fees(rs.getDouble("fees"))
                .notes(rs.getString("notes"))
                .createdAt(SqlDateTimeUtil.parse(rs.getString("created_at")))
                .build();
    }
}
