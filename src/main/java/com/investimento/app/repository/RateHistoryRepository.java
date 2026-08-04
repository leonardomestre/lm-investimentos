package com.investimento.app.repository;

import com.investimento.app.data.model.RateHistory;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface RateHistoryRepository {

    Optional<RateHistory> findByDate(LocalDate date);

    /** Usado pela ATV-10 (projecao de renda fixa indexada a CDI/SELIC). */
    Optional<RateHistory> findMostRecent();

    List<RateHistory> listAll();

    RateHistory upsert(RateHistory rateHistory);

    void delete(LocalDate date);
}
