package com.investimento.app.data;

import com.investimento.app.data.model.Asset;
import com.investimento.app.data.model.AssetType;
import com.investimento.app.data.model.Category;
import com.investimento.app.data.model.OperationType;
import com.investimento.app.data.model.QuoteSource;
import com.investimento.app.dto.CreateTransactionRequest;
import com.investimento.app.dto.TransactionDTO;
import com.investimento.app.repository.AssetRepository;
import com.investimento.app.repository.AssetRepositoryImpl;
import com.investimento.app.repository.TransactionRepository;
import com.investimento.app.repository.TransactionRepositoryImpl;
import com.investimento.app.service.TransactionService;
import com.investimento.app.service.TransactionServiceImpl;
import com.investimento.app.service.ValidationException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;

/**
 * Teste manual da ATV-09 (CRUD de Transações, regra de negócio) — roda
 * contra um banco SQLite em memória (mesmo padrão de {@link
 * RepositoryManualTest}/{@link AssetServiceManualTest}), sem tocar rede (o
 * serviço desta atividade não depende de nenhum client de API). Não há JUnit
 * no projeto ainda — main() temporário, mesma decisão das atividades
 * anteriores.
 *
 * Executar (dentro de "LM Investimentos/", jars/target sem espaço no caminho
 * — gotcha das ATV-01 a 08/CLAUDE.md):
 *   mvn -q compile
 *   java -cp "&lt;target-classes-sem-espaco&gt;;&lt;sqlite-jdbc.jar&gt;;&lt;mapstruct.jar&gt;" \
 *        com.investimento.app.data.TransactionServiceManualTest
 */
public final class TransactionServiceManualTest {

    private TransactionServiceManualTest() {
    }

    public static void main(String[] args) throws Exception {
        Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON;");
        }
        Database.bootstrapSchema(conn);

        AssetRepository assetRepository = new AssetRepositoryImpl(conn);
        TransactionRepository transactionRepository = new TransactionRepositoryImpl(conn);
        TransactionService transactionService = new TransactionServiceImpl(transactionRepository, assetRepository);

        // Ativo de apoio, inserido direto via repository (fora do escopo da
        // validacao desta atividade, que e so sobre Transaction).
        Asset petr4 = assetRepository.insert(Asset.builder()
                .type(AssetType.STOCK)
                .category(Category.STOCKS)
                .ticker("PETR4")
                .displayName("Petrobras PN")
                .currency("BRL")
                .quoteSource(QuoteSource.BRAPI)
                .sourceIdentifier("PETR4")
                .active(true)
                .build());

        // ---- Cenario 1: registrar compra e venda do mesmo ativo, listByAsset traz as 2 ----
        System.out.println("=== Cenario 1: registrar compra e venda ===");
        TransactionDTO buy = transactionService.create(new CreateTransactionRequest(
                petr4.getId(), OperationType.BUY, LocalDate.of(2026, 1, 10), 100, 30.0, 5.0, "Compra inicial"));
        TransactionDTO sell = transactionService.create(new CreateTransactionRequest(
                petr4.getId(), OperationType.SELL, LocalDate.of(2026, 3, 10), 40, 35.0, 5.0, "Venda parcial"));
        check(buy.id() != null && sell.id() != null, "compra e venda deveriam ter id gerado");
        List<TransactionDTO> byAsset = transactionService.listByAsset(petr4.getId());
        check(byAsset.size() == 2, "listByAsset deveria retornar as 2 transacoes, retornou " + byAsset.size());

        // ---- Cenario 1b: editar uma (mudar quantidade), confirma persistiu ----
        System.out.println();
        System.out.println("=== Cenario 1b: editar transacao (mudar quantidade) ===");
        TransactionDTO buyEdited = new TransactionDTO(buy.id(), buy.assetId(), buy.operationType(), buy.date(),
                150, buy.unitPrice(), buy.fees(), buy.notes());
        transactionService.update(buyEdited);
        TransactionDTO buyReloaded = transactionService.listByAsset(petr4.getId()).stream()
                .filter(t -> t.id().equals(buy.id())).findFirst().orElseThrow();
        check(buyReloaded.quantity() == 150, "quantidade editada deveria ter persistido (150), veio " + buyReloaded.quantity());

        // ---- Cenario 1c: excluir uma, confirma sumiu ----
        System.out.println();
        System.out.println("=== Cenario 1c: excluir transacao ===");
        transactionService.delete(sell.id());
        List<TransactionDTO> afterDelete = transactionService.listByAsset(petr4.getId());
        check(afterDelete.size() == 1, "apos excluir deveria sobrar 1 transacao, sobrou " + afterDelete.size());
        check(afterDelete.stream().noneMatch(t -> t.id().equals(sell.id())), "transacao excluida nao deveria mais aparecer");

        // ---- Cenario 1d: listByPeriod com intervalo que pega so 1 das 2 originais ----
        System.out.println();
        System.out.println("=== Cenario 1d: listByPeriod (recriando a venda excluida para o teste) ===");
        TransactionDTO sell2 = transactionService.create(new CreateTransactionRequest(
                petr4.getId(), OperationType.SELL, LocalDate.of(2026, 3, 10), 40, 35.0, 5.0, "Venda parcial 2"));
        List<TransactionDTO> periodJanOnly = transactionService.listByPeriod(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));
        check(periodJanOnly.size() == 1 && periodJanOnly.get(0).id().equals(buy.id()),
                "listByPeriod(jan/2026) deveria trazer so a compra (10/01), trouxe " + periodJanOnly.size());
        List<TransactionDTO> periodAll = transactionService.listByPeriod(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        check(periodAll.size() == 2, "listByPeriod(2026 inteiro) deveria trazer as 2 restantes, trouxe " + periodAll.size());

        // ---- Cenario 2: rejeicoes ----
        System.out.println();
        System.out.println("=== Cenario 2: rejeicoes (assetId inexistente, quantity negativa, date futura) ===");
        try {
            transactionService.create(new CreateTransactionRequest(
                    999999L, OperationType.BUY, LocalDate.of(2026, 1, 1), 10, 10.0, 0, null));
            throw new AssertionError("FALHOU: deveria ter rejeitado assetId inexistente");
        } catch (ValidationException e) {
            System.out.println("OK: assetId inexistente rejeitado: " + e.getMessage());
        }
        try {
            transactionService.create(new CreateTransactionRequest(
                    petr4.getId(), OperationType.BUY, LocalDate.of(2026, 1, 1), -10, 10.0, 0, null));
            throw new AssertionError("FALHOU: deveria ter rejeitado quantity negativa");
        } catch (ValidationException e) {
            System.out.println("OK: quantity negativa/zero rejeitada: " + e.getMessage());
        }
        try {
            transactionService.create(new CreateTransactionRequest(
                    petr4.getId(), OperationType.BUY, LocalDate.now().plusDays(1), 10, 10.0, 0, null));
            throw new AssertionError("FALHOU: deveria ter rejeitado date futura");
        } catch (ValidationException e) {
            System.out.println("OK: date futura rejeitada: " + e.getMessage());
        }

        // Ativo inativo (soft delete) tambem deve ser rejeitado.
        assetRepository.remove(petr4.getId());
        try {
            transactionService.create(new CreateTransactionRequest(
                    petr4.getId(), OperationType.BUY, LocalDate.of(2026, 1, 1), 10, 10.0, 0, null));
            throw new AssertionError("FALHOU: deveria ter rejeitado ativo inativo");
        } catch (ValidationException e) {
            System.out.println("OK: ativo inativo (soft delete) rejeitado: " + e.getMessage());
        }

        conn.close();
        System.out.println();
        System.out.println("TransactionServiceManualTest: TODOS OS CENARIOS PASSARAM.");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("FALHOU: " + message);
        }
        System.out.println("OK: " + message);
    }
}
