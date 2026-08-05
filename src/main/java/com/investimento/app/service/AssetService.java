package com.investimento.app.service;

import com.investimento.app.api.brapi.BrapiException;
import com.investimento.app.dto.AssetDTO;
import com.investimento.app.dto.CreateAssetRequest;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Regra de validação e derivação de campos do cadastro de ativos (RF01) —
 * sem UI (a tela real é a ATV-13, que só chama este serviço).
 */
public interface AssetService {

    /**
     * Valida {@code req} conforme o tipo de ativo, deriva {@code category}/
     * {@code quoteSource}/{@code sourceIdentifier}, insere via {@code
     * AssetRepository} e dispara {@code MarketService.seedInitialHistory}
     * em background (não bloqueia o retorno). Nunca grava no banco se a
     * validação falhar.
     */
    AssetDTO create(CreateAssetRequest req) throws ValidationException, BrapiException;

    void update(AssetDTO dto) throws ValidationException;

    /** Soft delete ({@code is_active = 0}) — nunca apaga a linha física. */
    void remove(long assetId);

    List<AssetDTO> listAssets(boolean includeInactive);

    Optional<AssetDTO> findById(long id);

    /**
     * Os 8 códigos ISO que a HG Brasil cobre para câmbio (RF01, ATV-13) —
     * exposto para a UI popular o dropdown de "moeda de referência" de
     * {@code FOREIGN_CURRENCY} sem duplicar a lista fixa localmente (mesmo
     * padrão de {@code CoinGeckoClient.supportedSymbols()}, ATV-08).
     */
    Set<String> supportedForexCurrencies();
}
