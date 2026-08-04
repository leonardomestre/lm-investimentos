package com.investimento.app.repository;

import com.investimento.app.data.model.Distribution;

import java.util.List;

public interface DistributionRepository {

    Distribution insert(Distribution distribution);

    List<Distribution> listByAsset(long assetId);

    void delete(long id);
}
