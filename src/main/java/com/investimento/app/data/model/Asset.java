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
public class Asset {
    private Long id;
    private AssetType type;
    private Category category;
    private String ticker;
    private String displayName;
    private String currency;
    private QuoteSource quoteSource;
    private String sourceIdentifier;
    private Benchmark benchmark;
    private Double contractedRatePct;
    private String financialInstitution;
    private LocalDate investmentDate;
    private LocalDate maturityDate;
    private boolean active;
    private LocalDateTime createdAt;
}
