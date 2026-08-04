package com.investimento.app.repository;

import com.investimento.app.data.model.Asset;

import java.util.List;
import java.util.Optional;

public interface AssetRepository {

    Optional<Asset> findById(long id);

    List<Asset> listAssets(boolean includeInactive);

    Asset insert(Asset asset);

    void update(Asset asset);

    /**
     * Soft delete (is_active = 0) — nao apaga a linha fisicamente, o
     * historico de transacoes de um ativo "removido" ainda precisa aparecer
     * no relatorio de IR (RF07).
     */
    void remove(long id);
}
