package com.investimento.app.service;

import com.investimento.app.api.brapi.BrapiClient;
import com.investimento.app.api.brapi.BrapiException;
import com.investimento.app.api.brapi.model.AssetQuote;
import com.investimento.app.api.coingecko.CoinGeckoClient;
import com.investimento.app.data.model.Asset;
import com.investimento.app.data.model.AssetType;
import com.investimento.app.data.model.Category;
import com.investimento.app.data.model.QuoteSource;
import com.investimento.app.dto.AssetDTO;
import com.investimento.app.dto.CreateAssetRequest;
import com.investimento.app.mapper.AssetMapper;
import com.investimento.app.repository.AssetRepository;
import javafx.concurrent.Task;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Implementação de {@link AssetService} — valida conforme o tipo de ativo
 * (RF01: "campos específicos variam conforme o tipo"), deriva {@code
 * category}/{@code quoteSource}/{@code sourceIdentifier} (tabelas fixas da
 * ATV-08, o usuário nunca escolhe esses campos diretamente) e dispara o seed
 * de histórico inicial em background após o cadastro.
 */
public class AssetServiceImpl implements AssetService {

    /** RF01: os 8 códigos ISO que a HG Brasil cobre para câmbio (FOREX:xxxBRL). */
    private static final Set<String> SUPPORTED_FOREX_CURRENCIES =
            Set.of("ARS", "AUD", "CAD", "CNY", "EUR", "GBP", "JPY", "USD");

    private final AssetRepository assetRepository;
    private final BrapiClient brapiClient;
    private final CoinGeckoClient coinGeckoClient;
    private final MarketService marketService;

    public AssetServiceImpl(AssetRepository assetRepository,
                             BrapiClient brapiClient,
                             CoinGeckoClient coinGeckoClient,
                             MarketService marketService) {
        this.assetRepository = assetRepository;
        this.brapiClient = brapiClient;
        this.coinGeckoClient = coinGeckoClient;
        this.marketService = marketService;
    }

    @Override
    public AssetDTO create(CreateAssetRequest req) throws ValidationException, BrapiException {
        if (req == null || req.type() == null) {
            throw new ValidationException("Tipo de ativo é obrigatório.");
        }
        if (isBlank(req.displayName())) {
            throw new ValidationException("Nome de exibição é obrigatório.");
        }

        AssetType type = req.type();
        Category category = deriveCategory(type);

        String resolvedTicker = req.ticker();
        String currency = req.currency();
        QuoteSource quoteSource;
        String sourceIdentifier;

        switch (type) {
            case STOCK, FII, ETF, BDR -> {
                if (isBlank(req.ticker())) {
                    throw new ValidationException("Ticker é obrigatório para " + type + ".");
                }
                AssetQuote quote = validateTicker(req.ticker());
                // Sempre salva o ticker RESOLVIDO pela API, nunca o digitado
                // pelo usuário (brapi-api/SKILL.md secao 7) - se
                // resolvedTicker != req.ticker(), a UI (ATV-13) compara os
                // dois para avisar o usuario que o ticker foi renomeado.
                resolvedTicker = quote.resolvedSymbol();
                quoteSource = QuoteSource.BRAPI;
                sourceIdentifier = resolvedTicker;
                currency = isBlank(currency) ? "BRL" : currency;
            }
            case CRYPTO -> {
                if (isBlank(req.ticker())) {
                    throw new ValidationException("Símbolo da criptomoeda é obrigatório.");
                }
                String symbol = req.ticker().trim().toUpperCase();
                // Validacao local contra o ID_MAP fixo do CoinGeckoClient -
                // nunca confia so na UI (dropdown), mas tambem nao gasta uma
                // chamada de rede so para confirmar um simbolo ja fixo e
                // confiavel (ver CoinGeckoClient.supportedSymbols()).
                if (!coinGeckoClient.supportedSymbols().contains(symbol)) {
                    throw new ValidationException(
                            "Criptomoeda '" + req.ticker() + "' não suportada. Moedas disponíveis: "
                                    + String.join(", ", coinGeckoClient.supportedSymbols()) + ".");
                }
                resolvedTicker = symbol;
                quoteSource = QuoteSource.COINGECKO;
                sourceIdentifier = symbol;
                currency = isBlank(currency) ? "BRL" : currency;
            }
            case FOREIGN_CURRENCY -> {
                if (isBlank(currency) || !SUPPORTED_FOREX_CURRENCIES.contains(currency.trim().toUpperCase())) {
                    throw new ValidationException(
                            "Moeda '" + currency + "' não suportada. Códigos aceitos: "
                                    + String.join(", ", SUPPORTED_FOREX_CURRENCIES) + ".");
                }
                currency = currency.trim().toUpperCase();
                quoteSource = QuoteSource.HGBRASIL;
                sourceIdentifier = "FOREX:" + currency + "BRL";
                resolvedTicker = null;
            }
            case FIXED_INCOME -> {
                if (req.benchmark() == null || req.contractedRatePct() == null
                        || isBlank(req.financialInstitution())
                        || req.investmentDate() == null || req.maturityDate() == null) {
                    throw new ValidationException(
                            "Para renda fixa, indexador, taxa contratada, instituição financeira, "
                                    + "data de aplicação e data de vencimento são obrigatórios.");
                }
                if (!req.maturityDate().isAfter(req.investmentDate())) {
                    throw new ValidationException(
                            "A data de vencimento deve ser posterior à data de aplicação.");
                }
                quoteSource = QuoteSource.NONE;
                sourceIdentifier = null;
                resolvedTicker = null;
                currency = isBlank(currency) ? "BRL" : currency;
            }
            default -> throw new ValidationException("Tipo de ativo desconhecido: " + type);
        }

        Asset asset = Asset.builder()
                .type(type)
                .category(category)
                .ticker(resolvedTicker)
                .displayName(req.displayName())
                .currency(currency)
                .quoteSource(quoteSource)
                .sourceIdentifier(sourceIdentifier)
                .benchmark(req.benchmark())
                .contractedRatePct(req.contractedRatePct())
                .financialInstitution(req.financialInstitution())
                .investmentDate(req.investmentDate())
                .maturityDate(req.maturityDate())
                .active(true)
                .build();

        Asset inserted = assetRepository.insert(asset);
        AssetDTO dto = AssetMapper.INSTANCE.toDto(inserted);

        // FIXED_INCOME nao tem sourceIdentifier nem historico de cotacao para
        // semear - nao dispara Task nenhuma para esse tipo (armadilha
        // documentada na ATV-08). Para os demais tipos, seedInitialHistory ja
        // sabe nao fazer nada de rede quando quoteSource for HGBRASIL/MANUAL/
        // NONE (MarketServiceImpl), entao e seguro chamar sempre.
        if (type != AssetType.FIXED_INCOME && marketService != null) {
            Task<Void> seedTask = marketService.seedInitialHistory(inserted);
            Thread thread = new Thread(seedTask, "seed-initial-history-" + inserted.getId());
            thread.setDaemon(true);
            thread.start();
        }

        return dto;
    }

    /**
     * Chama {@link BrapiClient#getQuote(String)} para confirmar que o ticker
     * existe na B3 antes de gravar (RF01) — se vier {@code NOT_FOUND},
     * traduz para {@link ValidationException} com mensagem pronta para a UI;
     * qualquer outro erro (rede, token, etc.) propaga como {@link
     * BrapiException}, conforme a assinatura de {@link #create}.
     */
    private AssetQuote validateTicker(String ticker) throws ValidationException, BrapiException {
        try {
            return brapiClient.getQuote(ticker.trim().toUpperCase());
        } catch (BrapiException e) {
            if ("NOT_FOUND".equals(e.getCode())) {
                throw new ValidationException("Ticker '" + ticker + "' não encontrado na B3.");
            }
            throw e;
        }
    }

    private static Category deriveCategory(AssetType type) {
        return switch (type) {
            case STOCK, ETF, BDR -> Category.STOCKS;
            case FII -> Category.FIIS;
            case FIXED_INCOME -> Category.FIXED_INCOME;
            case CRYPTO -> Category.CRYPTO;
            case FOREIGN_CURRENCY -> Category.FOREX;
        };
    }

    @Override
    public void update(AssetDTO dto) throws ValidationException {
        if (dto == null) {
            throw new ValidationException("Ativo inválido.");
        }
        if (isBlank(dto.displayName())) {
            throw new ValidationException("Nome de exibição é obrigatório.");
        }
        if (dto.type() == AssetType.FIXED_INCOME
                && dto.investmentDate() != null && dto.maturityDate() != null
                && !dto.maturityDate().isAfter(dto.investmentDate())) {
            throw new ValidationException("A data de vencimento deve ser posterior à data de aplicação.");
        }
        assetRepository.update(AssetMapper.INSTANCE.toEntity(dto));
    }

    @Override
    public void remove(long assetId) {
        assetRepository.remove(assetId);
    }

    @Override
    public List<AssetDTO> listAssets(boolean includeInactive) {
        return assetRepository.listAssets(includeInactive).stream()
                .map(AssetMapper.INSTANCE::toDto)
                .toList();
    }

    @Override
    public Optional<AssetDTO> findById(long id) {
        return assetRepository.findById(id).map(AssetMapper.INSTANCE::toDto);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
