package com.investimento.app.data.model;

/**
 * Nao listado explicitamente entre os 4 enums da ATV-02, mas
 * distributions.type e CHECK-constrained igual as demais colunas modeladas
 * como enum (ver CLAUDE.md, entrada da ATV-02, para a decisao).
 */
public enum DistributionType {
    DIVIDEND, INTEREST_ON_EQUITY, INCOME
}
