package com.investimento.app.data.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PortfolioSnapshot {
    private LocalDate date;
    private double totalValue;
    private double investedValue;
    private LocalDateTime fetchedAt;
}
