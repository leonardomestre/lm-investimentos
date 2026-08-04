package com.investimento.app.data;

import com.investimento.app.data.model.Asset;
import com.investimento.app.data.model.AssetType;
import com.investimento.app.data.model.Category;
import com.investimento.app.data.model.OperationType;
import com.investimento.app.data.model.QuoteHistory;
import com.investimento.app.data.model.QuoteSource;
import com.investimento.app.data.model.Transaction;
import com.investimento.app.repository.AssetRepository;
import com.investimento.app.repository.AssetRepositoryImpl;
import com.investimento.app.repository.QuoteHistoryRepository;
import com.investimento.app.repository.QuoteHistoryRepositoryImpl;
import com.investimento.app.repository.TransactionRepository;
import com.investimento.app.repository.TransactionRepositoryImpl;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Teste manual da camada de acesso a dados (ATV-02, passo 4). Roda contra
 * um banco SQLite em memoria (jdbc:sqlite::memory:), isolado do banco real
 * do app (Database.getConnection() nao e usado aqui de proposito). Nao ha
 * framework de teste no projeto ainda, entao este e um main() temporario,
 * conforme a atividade permite explicitamente.
 *
 * Executar (dentro de "LM Investimentos/"):
 *   mvn -q compile
 *   java -cp "target/classes;<caminho-para-sqlite-jdbc-jar>" com.investimento.app.data.RepositoryManualTest
 */
public final class RepositoryManualTest {

    private RepositoryManualTest() {
    }

    public static void main(String[] args) throws SQLException {
        Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON;");
        }
        Database.bootstrapSchema(conn);

        AssetRepository assetRepository = new AssetRepositoryImpl(conn);
        TransactionRepository transactionRepository = new TransactionRepositoryImpl(conn);
        QuoteHistoryRepository quoteHistoryRepository = new QuoteHistoryRepositoryImpl(conn);

        // 1) Insere um Asset via AssetRepository (interface, nao Impl) e le de volta.
        Asset asset = Asset.builder()
                .type(AssetType.STOCK)
                .category(Category.STOCKS)
                .ticker("PETR4")
                .displayName("Petrobras PN")
                .currency("BRL")
                .quoteSource(QuoteSource.BRAPI)
                .sourceIdentifier("PETR4")
                .active(true)
                .build();
        Asset insertedAsset = assetRepository.insert(asset);
        check(insertedAsset.getId() != null, "Asset.id deveria ser gerado no insert");

        Optional<Asset> foundAsset = assetRepository.findById(insertedAsset.getId());
        check(foundAsset.isPresent(), "Asset inserido deveria ser encontrado por id");
        check("PETR4".equals(foundAsset.get().getTicker()), "Ticker deveria ser PETR4");
        check(foundAsset.get().getType() == AssetType.STOCK, "Type deveria ser STOCK");

        // 2) Insere uma Transaction vinculada ao Asset, le de volta.
        Transaction transaction = Transaction.builder()
                .assetId(insertedAsset.getId())
                .operationType(OperationType.BUY)
                .date(LocalDate.of(2024, 1, 15))
                .quantity(100.0)
                .unitPrice(28.5)
                .fees(2.5)
                .build();
        Transaction insertedTransaction = transactionRepository.insert(transaction);
        check(insertedTransaction.getId() != null, "Transaction.id deveria ser gerado no insert");

        List<Transaction> transactionsForAsset = transactionRepository.listByAsset(insertedAsset.getId());
        check(transactionsForAsset.size() == 1, "Deveria haver exatamente 1 transacao para o ativo");
        check(transactionsForAsset.get(0).getQuantity() == 100.0, "Quantity deveria ser 100.0");

        // 3) Upsert de QuoteHistory duas vezes (mesmo assetId+date) com precos
        // diferentes - confirma que sobrescreve, nao duplica.
        QuoteHistory q1 = QuoteHistory.builder()
                .assetId(insertedAsset.getId())
                .date(LocalDate.of(2024, 1, 15))
                .price(28.5)
                .source(QuoteSource.BRAPI)
                .build();
        quoteHistoryRepository.upsert(q1);

        QuoteHistory q2 = QuoteHistory.builder()
                .assetId(insertedAsset.getId())
                .date(LocalDate.of(2024, 1, 15))
                .price(29.9)
                .source(QuoteSource.BRAPI)
                .build();
        quoteHistoryRepository.upsert(q2);

        List<QuoteHistory> quotes = quoteHistoryRepository.listByAsset(insertedAsset.getId());
        check(quotes.size() == 1, "Upsert nao deveria duplicar linha (mesmo asset_id+date)");
        check(quotes.get(0).getPrice() == 29.9, "Upsert deveria sobrescrever o preco para 29.9");

        // 4) Update - confirma que persiste.
        Asset toUpdate = foundAsset.get();
        toUpdate.setDisplayName("Petrobras PN (atualizado)");
        assetRepository.update(toUpdate);
        Optional<Asset> updatedAsset = assetRepository.findById(insertedAsset.getId());
        check(updatedAsset.isPresent()
                        && "Petrobras PN (atualizado)".equals(updatedAsset.get().getDisplayName()),
                "Update deveria persistir o novo displayName");

        conn.close();
        System.out.println("RepositoryManualTest: TODOS OS CENARIOS PASSARAM.");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("FALHOU: " + message);
        }
        System.out.println("OK: " + message);
    }
}
