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
public class Distribution {
    private Long id;
    private Long assetId;
    private DistributionType type;
    private LocalDate paymentDate;
    private double value;
    private String notes;
    private LocalDateTime createdAt;
}
