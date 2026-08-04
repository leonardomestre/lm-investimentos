package com.investimento.app.repository;

import com.investimento.app.data.model.Asset;
import com.investimento.app.data.model.AssetType;
import com.investimento.app.data.model.Benchmark;
import com.investimento.app.data.model.Category;
import com.investimento.app.data.model.QuoteSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class AssetRepositoryImpl implements AssetRepository {

    private final Connection connection;

    public AssetRepositoryImpl(Connection connection) {
        this.connection = connection;
    }

    @Override
    public Optional<Asset> findById(long id) {
        String sql = "SELECT * FROM assets WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao buscar Asset por id", e);
        }
    }

    @Override
    public List<Asset> listAssets(boolean includeInactive) {
        String sql = includeInactive
                ? "SELECT * FROM assets ORDER BY display_name"
                : "SELECT * FROM assets WHERE is_active = 1 ORDER BY display_name";
        List<Asset> result = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(map(rs));
            }
            return result;
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao listar Assets", e);
        }
    }

    @Override
    public Asset insert(Asset asset) {
        String sql = "INSERT INTO assets (type, category, ticker, display_name, currency, quote_source, "
                + "source_identifier, benchmark, contracted_rate_pct, financial_institution, investment_date, "
                + "maturity_date, is_active, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, asset.getType().name());
            ps.setString(2, asset.getCategory().name());
            ps.setString(3, asset.getTicker());
            ps.setString(4, asset.getDisplayName());
            ps.setString(5, asset.getCurrency());
            ps.setString(6, asset.getQuoteSource().name());
            ps.setString(7, asset.getSourceIdentifier());
            ps.setString(8, asset.getBenchmark() != null ? asset.getBenchmark().name() : null);
            setNullableDouble(ps, 9, asset.getContractedRatePct());
            ps.setString(10, asset.getFinancialInstitution());
            ps.setString(11, asset.getInvestmentDate() != null ? asset.getInvestmentDate().toString() : null);
            ps.setString(12, asset.getMaturityDate() != null ? asset.getMaturityDate().toString() : null);
            ps.setInt(13, asset.isActive() ? 1 : 0);
            LocalDateTime createdAt = asset.getCreatedAt() != null ? asset.getCreatedAt() : LocalDateTime.now();
            ps.setString(14, SqlDateTimeUtil.format(createdAt));

            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    asset.setId(keys.getLong(1));
                }
            }
            asset.setCreatedAt(createdAt);
            return asset;
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao inserir Asset", e);
        }
    }

    @Override
    public void update(Asset asset) {
        String sql = "UPDATE assets SET type=?, category=?, ticker=?, display_name=?, currency=?, quote_source=?, "
                + "source_identifier=?, benchmark=?, contracted_rate_pct=?, financial_institution=?, "
                + "investment_date=?, maturity_date=?, is_active=? WHERE id=?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, asset.getType().name());
            ps.setString(2, asset.getCategory().name());
            ps.setString(3, asset.getTicker());
            ps.setString(4, asset.getDisplayName());
            ps.setString(5, asset.getCurrency());
            ps.setString(6, asset.getQuoteSource().name());
            ps.setString(7, asset.getSourceIdentifier());
            ps.setString(8, asset.getBenchmark() != null ? asset.getBenchmark().name() : null);
            setNullableDouble(ps, 9, asset.getContractedRatePct());
            ps.setString(10, asset.getFinancialInstitution());
            ps.setString(11, asset.getInvestmentDate() != null ? asset.getInvestmentDate().toString() : null);
            ps.setString(12, asset.getMaturityDate() != null ? asset.getMaturityDate().toString() : null);
            ps.setInt(13, asset.isActive() ? 1 : 0);
            ps.setLong(14, asset.getId());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao atualizar Asset", e);
        }
    }

    @Override
    public void remove(long id) {
        String sql = "UPDATE assets SET is_active = 0 WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("Erro ao remover (soft delete) Asset", e);
        }
    }

    private void setNullableDouble(PreparedStatement ps, int index, Double value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.REAL);
        } else {
            ps.setDouble(index, value);
        }
    }

    private Asset map(ResultSet rs) throws SQLException {
        String benchmark = rs.getString("benchmark");
        String investmentDate = rs.getString("investment_date");
        String maturityDate = rs.getString("maturity_date");
        double contractedRate = rs.getDouble("contracted_rate_pct");
        boolean contractedRateNull = rs.wasNull();

        return Asset.builder()
                .id(rs.getLong("id"))
                .type(AssetType.valueOf(rs.getString("type")))
                .category(Category.valueOf(rs.getString("category")))
                .ticker(rs.getString("ticker"))
                .displayName(rs.getString("display_name"))
                .currency(rs.getString("currency"))
                .quoteSource(QuoteSource.valueOf(rs.getString("quote_source")))
                .sourceIdentifier(rs.getString("source_identifier"))
                .benchmark(benchmark != null ? Benchmark.valueOf(benchmark) : null)
                .contractedRatePct(contractedRateNull ? null : contractedRate)
                .financialInstitution(rs.getString("financial_institution"))
                .investmentDate(investmentDate != null ? LocalDate.parse(investmentDate) : null)
                .maturityDate(maturityDate != null ? LocalDate.parse(maturityDate) : null)
                .active(rs.getInt("is_active") == 1)
                .createdAt(SqlDateTimeUtil.parse(rs.getString("created_at")))
                .build();
    }
}
