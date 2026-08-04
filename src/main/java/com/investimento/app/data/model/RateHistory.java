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
public class RateHistory {
    private LocalDate date;
    private double cdi;
    private double selic;
    private Double cdiDaily;
    private Double selicDaily;
    private Double dailyFactor;
    private LocalDateTime fetchedAt;
}
