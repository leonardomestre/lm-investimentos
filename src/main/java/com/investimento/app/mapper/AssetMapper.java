package com.investimento.app.mapper;

import com.investimento.app.data.model.Asset;
import com.investimento.app.dto.AssetDTO;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

/**
 * Converte {@code Asset} (entidade Lombok) <-> {@code AssetDTO} (record) —
 * único ponto de conversão entre a linha do banco e o que a UI recebe (ver
 * CONVENCOES.md seção 3). {@code componentModel} default (projeto não usa
 * Spring/CDI) — acesso via {@code AssetMapper.INSTANCE}.
 */
@Mapper
public interface AssetMapper {

    AssetMapper INSTANCE = Mappers.getMapper(AssetMapper.class);

    AssetDTO toDto(Asset entity);

    Asset toEntity(AssetDTO dto);
}
