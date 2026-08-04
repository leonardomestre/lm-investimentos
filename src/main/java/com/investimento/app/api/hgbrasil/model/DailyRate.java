package com.investimento.app.api.hgbrasil.model;

import java.time.LocalDate;

/**
 * Taxas do dia (CDI/SELIC), conforme {@code results.taxes[0]} de
 * {@code GET /finance} ou {@code GET /finance/taxes}.
 *
 * @param date         data de referência
 * @param cdi          CDI anual %
 * @param selic        SELIC anual %
 * @param cdiDaily     CDI "diária" anualizada %
 * @param selicDaily   SELIC "diária" anualizada %
 * @param dailyFactor  fator diário — frequentemente {@code 0}
 */
public record DailyRate(
        LocalDate date,
        double cdi,
        double selic,
        double cdiDaily,
        double selicDaily,
        double dailyFactor
) {
}
