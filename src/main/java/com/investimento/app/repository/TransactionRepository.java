package com.investimento.app.repository;

import com.investimento.app.data.model.Transaction;

import java.util.List;
import java.util.Optional;

public interface TransactionRepository {

    Optional<Transaction> findById(long id);

    List<Transaction> listAll();

    List<Transaction> listByAsset(long assetId);

    Transaction insert(Transaction transaction);

    void update(Transaction transaction);

    /** DELETE fisico — sem soft delete para transacoes nesta camada. */
    void delete(long id);
}
