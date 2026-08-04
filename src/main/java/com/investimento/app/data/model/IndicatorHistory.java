package com.investimento.app.data.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndicatorHistory {
    private Long id;
    private String ticker;
    private String period;
    private double value;
    private LocalDateTime fetchedAt;
}
