package com.investimento.app.repository;

import com.investimento.app.data.model.Transaction;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface TransactionRepository {

    Optional<Transaction> findById(long id);

    List<Transaction> listAll();

    List<Transaction> listByAsset(long assetId);

    /**
     * Filtro por periodo (RF07, ATV-09) aplicado no SQL ({@code WHERE date
     * BETWEEN ? AND ?}), nao trazendo tudo para a memoria - a tabela pode
     * crescer bastante ao longo dos anos. Nao fazia parte do contrato
     * original da ATV-02 (adicao aditiva desta atividade).
     */
    List<Transaction> listByPeriod(LocalDate start, LocalDate end);

    Transaction insert(Transaction transaction);

    void update(Transaction transaction);

    /** DELETE fisico — sem soft delete para transacoes nesta camada. */
    void delete(long id);
}
