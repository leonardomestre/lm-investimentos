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
public class QuoteHistory {
    private Long id;
    private Long assetId;
    private LocalDate date;
    private double price;
    private QuoteSource source;
    private LocalDateTime fetchedAt;
}
