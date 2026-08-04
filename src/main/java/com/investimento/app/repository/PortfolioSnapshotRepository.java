package com.investimento.app.repository;

import com.investimento.app.data.model.PortfolioSnapshot;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PortfolioSnapshotRepository {

    Optional<PortfolioSnapshot> findByDate(LocalDate date);

    List<PortfolioSnapshot> listAll();

    PortfolioSnapshot insert(PortfolioSnapshot snapshot);

    void update(PortfolioSnapshot snapshot);

    void delete(LocalDate date);
}
