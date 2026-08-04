package com.investimento.app.repository;

import com.investimento.app.data.model.IndicatorHistory;

import java.util.List;
import java.util.Optional;

public interface IndicatorHistoryRepository {

    Optional<IndicatorHistory> findById(long id);

    List<IndicatorHistory> listByTicker(String ticker);

    /** Upsert por (ticker, period) — dado do periodo pode ser reconsultado. */
    IndicatorHistory upsert(IndicatorHistory indicatorHistory);

    void delete(long id);
}
