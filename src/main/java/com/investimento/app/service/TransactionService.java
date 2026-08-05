package com.investimento.app.service;

import com.investimento.app.dto.CreateTransactionRequest;
import com.investimento.app.dto.TransactionDTO;

import java.time.LocalDate;
import java.util.List;

/**
 * Regra de validação de registro/edição/exclusão de operações de compra e
 * venda vinculadas a um ativo (RF02) — sem UI (a tela real é a ATV-13, que
 * só chama este serviço). Não calcula preço médio nem ganho/perda — isso é
 * escopo exclusivo da ATV-10 (PositionService), que agrega as transações
 * cruas que este serviço grava e lê.
 */
public interface TransactionService {

    /**
     * Valida {@code req} (ativo existente e ativo, quantidade/preço
     * positivos, data não futura) e insere via {@code TransactionRepository}.
     * Nunca grava no banco se a validação falhar.
     */
    TransactionDTO create(CreateTransactionRequest req) throws ValidationException;

    void update(TransactionDTO transaction) throws ValidationException;

    /** DELETE físico — RF02 permite excluir de verdade, sem soft delete. */
    void delete(long transactionId);

    List<TransactionDTO> listByAsset(long assetId);

    /** Usado pela tela de Histórico/IR (ATV-17). */
    List<TransactionDTO> listAll();

    /** Filtro por período (RF07) — implementado no SQL, não em memória. */
    List<TransactionDTO> listByPeriod(LocalDate start, LocalDate end);
}
