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
public class Transaction {
    private Long id;
    private Long assetId;
    private OperationType operationType;
    private LocalDate date;
    private double quantity;
    private double unitPrice;
    private double fees;
    private String notes;
    private LocalDateTime createdAt;
}
