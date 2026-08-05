package com.investimento.app.mapper;

import com.investimento.app.data.model.Transaction;
import com.investimento.app.dto.TransactionDTO;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

/**
 * Converte {@code Transaction} (entidade Lombok) <-> {@code TransactionDTO}
 * (record) — mesmo padrão de {@code AssetMapper} (ATV-08, ver
 * CONVENCOES.md seção 3). {@code componentModel} default (projeto não usa
 * Spring/CDI) — acesso via {@code TransactionMapper.INSTANCE}.
 */
@Mapper
public interface TransactionMapper {

    TransactionMapper INSTANCE = Mappers.getMapper(TransactionMapper.class);

    TransactionDTO toDto(Transaction entity);

    Transaction toEntity(TransactionDTO dto);
}
