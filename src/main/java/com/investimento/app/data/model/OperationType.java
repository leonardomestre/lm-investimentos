package com.investimento.app.data.model;

/**
 * Nao listado explicitamente entre os 4 enums da ATV-02, mas
 * transactions.operation_type e CHECK-constrained igual as demais colunas
 * modeladas como enum (ver CLAUDE.md, entrada da ATV-02, para a decisao).
 */
public enum OperationType {
    BUY, SELL
}
