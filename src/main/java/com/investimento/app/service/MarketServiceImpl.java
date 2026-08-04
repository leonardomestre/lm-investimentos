package com.investimento.app.service;

import com.investimento.app.api.brapi.BrapiClient;
import com.investimento.app.api.brapi.BrapiException;
import com.investimento.app.api.brapi.model.AssetQuote;
import com.investimento.app.api.coingecko.CoinGeckoClient;
import com.investimento.app.api.coingecko.CoinGeckoException;
import com.investimento.app.api.coingecko.model.CryptoPrice;
import com.investimento.app.api.hgbrasil.HgBrasilClient;
import com.investimento.app.api.hgbrasil.HgBrasilException;
import com.investimento.app.api.hgbrasil.model.Currency;
import com.investimento.app.api.hgbrasil.model.DailyRate;
import com.investimento.app.api.hgbrasil.model.IndicatorPoint;
import com.investimento.app.api.hgbrasil.model.MacroSnapshot;
import com.investimento.app.data.model.Asset;
import com.investimento.app.data.model.IndicatorHistory;
import com.investimento.app.data.model.QuoteHistory;
import com.investimento.app.data.model.QuoteSource;
import com.investimento.app.data.model.RateHistory;
import com.investimento.app.repository.IndicatorHistoryRepository;
import com.investimento.app.repository.QuoteHistoryRepository;
import com.investimento.app.repository.RateHistoryRepository;
import javafx.concurrent.Task;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implementação de {@link MarketService} — orquestra os 3 clientes de API
 * (ATV-03/04/05) com cache (em memória para moedas/índices/indicadores, em
 * banco para tudo que vira histórico de ativo cadastrado) e roda a
 * atualização periódica fora da UI thread via {@link Task} (RT06).
 */
public class MarketServiceImpl implements MarketService {

    private static final Duration MACRO_TTL = Duration.ofMinutes(5);
    private static final Duration INDICATORS_TTL = Duration.ofHours(6);
    private static final Duration BRAPI_QUOTE_TTL = Duration.ofMinutes(15);
    private static final Duration COINGECKO_QUOTE_TTL = Duration.ofMinutes(5);

    private static final String[] INDICATOR_TICKERS = {"IBGE:IPCA", "BCB:SELICMETA"};

    private final HgBrasilClient hgBrasilClient;
    private final BrapiClient brapiClient;
    private final CoinGeckoClient coinGeckoClient;
    private final RateHistoryRepository rateHistoryRepository;
    private final IndicatorHistoryRepository indicatorHistoryRepository;
    private final QuoteHistoryRepository quoteHistoryRepository;

    // Cache em memoria do painel macro (moedas/indices/CDI-SELIC do dia) - nao
    // ha tabela dedicada para moedas/indices (nota da ATV-06); so o CDI/SELIC
    // do dia vai pro banco (rate_history), feito a cada busca bem-sucedida.
    private volatile MacroSnapshot macroSnapshotCache;
    private volatile Instant macroSnapshotFetchedAt;

    // Cache em memoria de IPCA + meta SELIC (a serie completa tambem vai pro
    // banco a cada busca bem-sucedida).
    private volatile Map<String, List<IndicatorPoint>> indicatorsCache = Map.of();
    private volatile Instant indicatorsFetchedAt;

    // Ultimo fetch de cotacao por ativo (assetId) - controla o TTL de
    // quote_history por fonte sem precisar reconsultar o banco a cada
    // updateQuotes().
    private final Map<Long, Instant> lastQuoteFetch = new ConcurrentHashMap<>();

    // Variacao % do dia por ativo, populada a partir do ultimo payload de
    // rede (nao de comparar linhas de quote_history) - usada pela ATV-12.
    private final Map<Long, Double> dailyChanges = new ConcurrentHashMap<>();

    public MarketServiceImpl(HgBrasilClient hgBrasilClient,
                              BrapiClient brapiClient,
                              CoinGeckoClient coinGeckoClient,
                              RateHistoryRepository rateHistoryRepository,
                              IndicatorHistoryRepository indicatorHistoryRepository,
                              QuoteHistoryRepository quoteHistoryRepository) {
        this.hgBrasilClient = hgBrasilClient;
        this.brapiClient = brapiClient;
        this.coinGeckoClient = coinGeckoClient;
        this.rateHistoryRepository = rateHistoryRepository;
        this.indicatorHistoryRepository = indicatorHistoryRepository;
        this.quoteHistoryRepository = quoteHistoryRepository;
    }

    @Override
    public synchronized MacroSnapshot getMacroSnapshot() {
        if (macroSnapshotCache != null && isFresh(macroSnapshotFetchedAt, MACRO_TTL)) {
            return macroSnapshotCache;
        }
        try {
            MacroSnapshot snapshot = hgBrasilClient.getSnapshot();
            macroSnapshotCache = snapshot;
            macroSnapshotFetchedAt = Instant.now();
            persistTodayRate(snapshot.todayRate());
            return snapshot;
        } catch (HgBrasilException e) {
            logFailure("getMacroSnapshot", e);
            // Sem internet/chave invalida: devolve o ultimo valor conhecido
            // (pode ser null se nunca buscou com sucesso - o chamador decide
            // o placeholder, RT06/resiliencia da ATV-06).
            return macroSnapshotCache;
        }
    }

    private void persistTodayRate(DailyRate rate) {
        if (rate == null) {
            return;
        }
        rateHistoryRepository.upsert(RateHistory.builder()
                .date(rate.date())
                .cdi(rate.cdi())
                .selic(rate.selic())
                .cdiDaily(rate.cdiDaily())
                .selicDaily(rate.selicDaily())
                .dailyFactor(rate.dailyFactor())
                .build());
    }

    @Override
    public synchronized Map<String, List<IndicatorPoint>> getIndicators() {
        if (!indicatorsCache.isEmpty() && isFresh(indicatorsFetchedAt, INDICATORS_TTL)) {
            return indicatorsCache;
        }
        try {
            Map<String, List<IndicatorPoint>> indicators = hgBrasilClient.getIndicators(INDICATOR_TICKERS);
            indicatorsCache = indicators;
            indicatorsFetchedAt = Instant.now();
            persistIndicators(indicators);
            return indicators;
        } catch (HgBrasilException e) {
            logFailure("getIndicators", e);
            // Pode ser Map.of() se nunca buscou com sucesso - o chamador
            // decide o placeholder.
            return indicatorsCache;
        }
    }

    private void persistIndicators(Map<String, List<IndicatorPoint>> indicators) {
        for (Map.Entry<String, List<IndicatorPoint>> entry : indicators.entrySet()) {
            String ticker = entry.getKey();
            for (IndicatorPoint point : entry.getValue()) {
                indicatorHistoryRepository.upsert(IndicatorHistory.builder()
                        .ticker(ticker)
                        .period(point.period())
                        .value(point.value())
                        .build());
            }
        }
    }

    @Override
    public Task<Void> updateQuotes(List<Asset> assets) {
        return new Task<>() {
            @Override
            protected Void call() {
                if (assets == null || assets.isEmpty()) {
                    return null;
                }
                updateMessage("Atualizando cotações...");
                updateProgress(0, 3);
                updateHgBrasilAssets(assets);
                updateProgress(1, 3);
                updateBrapiAssets(assets);
                updateProgress(2, 3);
                updateCoinGeckoAssets(assets);
                updateProgress(3, 3);
                updateMessage("Cotações atualizadas.");
                return null;
            }
        };
    }

    /**
     * Ativos {@code HGBRASIL} (câmbio) não geram chamada de API própria —
     * usam o {@link MacroSnapshot} já cacheado (busca/reaproveita conforme o
     * TTL de 5 min de {@link #getMacroSnapshot()}).
     */
    private void updateHgBrasilAssets(List<Asset> assets) {
        List<Asset> hgAssets = assets.stream()
                .filter(a -> a.getQuoteSource() == QuoteSource.HGBRASIL)
                .toList();
        if (hgAssets.isEmpty()) {
            return;
        }
        MacroSnapshot snapshot = getMacroSnapshot();
        if (snapshot == null) {
            return; // sem internet e sem cache anterior - nao ha o que atualizar agora.
        }
        LocalDate today = LocalDate.now();
        for (Asset asset : hgAssets) {
            String iso = resolveCurrencyIso(asset.getSourceIdentifier());
            Currency currency = iso == null ? null : snapshot.currencies().get(iso);
            if (currency == null) {
                continue;
            }
            quoteHistoryRepository.upsert(QuoteHistory.builder()
                    .assetId(asset.getId())
                    .date(today)
                    .price(currency.buy())
                    .source(QuoteSource.HGBRASIL)
                    .build());
            dailyChanges.put(asset.getId(), currency.changePercent());
            lastQuoteFetch.put(asset.getId(), Instant.now());
        }
    }

    /**
     * {@code sourceIdentifier} de ativos HGBRASIL segue o padrão de câmbio
     * ({@code "FOREX:USDBRL"} ou só {@code "USDBRL"}, conforme o
     * {@code schema.sql}) — {@link MacroSnapshot#currencies()} é indexado
     * pelo código ISO de 3 letras ({@code "USD"}). Extrai o prefixo
     * {@code FOREX:} (se houver) e os 3 primeiros caracteres do par
     * resultante.
     */
    private static String resolveCurrencyIso(String sourceIdentifier) {
        if (sourceIdentifier == null || sourceIdentifier.isBlank()) {
            return null;
        }
        String value = sourceIdentifier.contains(":")
                ? sourceIdentifier.substring(sourceIdentifier.indexOf(':') + 1)
                : sourceIdentifier;
        value = value.toUpperCase();
        return value.length() >= 6 ? value.substring(0, 3) : value;
    }

    /**
     * Todos os ativos {@code BRAPI} são resolvidos numa única chamada a
     * {@link BrapiClient#getQuotes(List)} — o próprio cliente decide se isso
     * vira 1 requisição (grupo demo) ou N requisições sequenciais (1 por
     * ticker, fora do grupo demo), conforme a ATV-04.
     */
    private void updateBrapiAssets(List<Asset> assets) {
        List<Asset> brapiAssets = assets.stream()
                .filter(a -> a.getQuoteSource() == QuoteSource.BRAPI)
                .filter(a -> needsRefresh(a.getId(), BRAPI_QUOTE_TTL))
                .toList();
        if (brapiAssets.isEmpty()) {
            return;
        }
        List<String> tickers = brapiAssets.stream().map(Asset::getSourceIdentifier).toList();
        try {
            Map<String, AssetQuote> quotes = brapiClient.getQuotes(tickers);
            LocalDate today = LocalDate.now();
            for (Asset asset : brapiAssets) {
                AssetQuote quote = quotes.get(asset.getSourceIdentifier());
                if (quote == null || quote.currentPrice() == null) {
                    continue;
                }
                quoteHistoryRepository.upsert(QuoteHistory.builder()
                        .assetId(asset.getId())
                        .date(today)
                        .price(quote.currentPrice())
                        .source(QuoteSource.BRAPI)
                        .build());
                if (quote.changePercent() != null) {
                    dailyChanges.put(asset.getId(), quote.changePercent());
                }
                lastQuoteFetch.put(asset.getId(), Instant.now());
            }
        } catch (BrapiException e) {
            logFailure("updateQuotes(BRAPI)", e);
            // Mantem o ultimo preco conhecido no banco - nao propaga a excecao.
        }
    }

    /**
     * Todos os ativos {@code COINGECKO} viram 1 única chamada a
     * {@link CoinGeckoClient#getPrices(List)} (a API prefere isso — ATV-05).
     */
    private void updateCoinGeckoAssets(List<Asset> assets) {
        List<Asset> cryptoAssets = assets.stream()
                .filter(a -> a.getQuoteSource() == QuoteSource.COINGECKO)
                .filter(a -> needsRefresh(a.getId(), COINGECKO_QUOTE_TTL))
                .toList();
        if (cryptoAssets.isEmpty()) {
            return;
        }
        List<String> symbols = cryptoAssets.stream().map(Asset::getSourceIdentifier).toList();
        try {
            Map<String, CryptoPrice> prices = coinGeckoClient.getPrices(symbols);
            LocalDate today = LocalDate.now();
            for (Asset asset : cryptoAssets) {
                String symbol = asset.getSourceIdentifier() == null ? null : asset.getSourceIdentifier().toUpperCase();
                CryptoPrice price = prices.get(symbol);
                if (price == null || price.priceBrl() == null) {
                    continue;
                }
                quoteHistoryRepository.upsert(QuoteHistory.builder()
                        .assetId(asset.getId())
                        .date(today)
                        .price(price.priceBrl())
                        .source(QuoteSource.COINGECKO)
                        .build());
                if (price.change24hPct() != null) {
                    dailyChanges.put(asset.getId(), price.change24hPct());
                }
                lastQuoteFetch.put(asset.getId(), Instant.now());
            }
        } catch (CoinGeckoException e) {
            logFailure("updateQuotes(COINGECKO)", e);
        }
    }

    @Override
    public Task<Void> seedInitialHistory(Asset asset) {
        return new Task<>() {
            @Override
            protected Void call() {
                if (asset == null || asset.getQuoteSource() == null) {
                    return null;
                }
                switch (asset.getQuoteSource()) {
                    case BRAPI -> seedFromBrapi(asset);
                    case COINGECKO -> seedFromCoinGecko(asset);
                    default -> {
                        // HGBRASIL: sem historico gratuito (comeca vazio e
                        // cresce so pela coleta periodica). MANUAL/NONE: sem
                        // fonte de rede, nada a semear.
                    }
                }
                return null;
            }
        };
    }

    private void seedFromBrapi(Asset asset) {
        try {
            List<com.investimento.app.api.brapi.model.HistoricalPoint> history =
                    brapiClient.getHistory(asset.getSourceIdentifier(), "max", "1d");
            for (com.investimento.app.api.brapi.model.HistoricalPoint point : history) {
                Double price = point.adjustedClose() != null ? point.adjustedClose() : point.close();
                if (price == null) {
                    continue;
                }
                quoteHistoryRepository.upsert(QuoteHistory.builder()
                        .assetId(asset.getId())
                        .date(point.date())
                        .price(price)
                        .source(QuoteSource.BRAPI)
                        .build());
            }
        } catch (BrapiException e) {
            logFailure("seedInitialHistory(BRAPI, " + asset.getSourceIdentifier() + ")", e);
        }
    }

    private void seedFromCoinGecko(Asset asset) {
        try {
            List<com.investimento.app.api.coingecko.model.HistoricalPoint> history =
                    coinGeckoClient.getHistory(asset.getSourceIdentifier(), 365);
            for (com.investimento.app.api.coingecko.model.HistoricalPoint point : history) {
                if (point.price() == null) {
                    continue;
                }
                quoteHistoryRepository.upsert(QuoteHistory.builder()
                        .assetId(asset.getId())
                        .date(point.date())
                        .price(point.price())
                        .source(QuoteSource.COINGECKO)
                        .build());
            }
        } catch (CoinGeckoException e) {
            logFailure("seedInitialHistory(COINGECKO, " + asset.getSourceIdentifier() + ")", e);
        }
    }

    @Override
    public Map<Long, Double> getDailyChanges(List<Asset> assets) {
        if (assets == null || assets.isEmpty()) {
            return Map.of();
        }
        Map<Long, Double> result = new HashMap<>();
        for (Asset asset : assets) {
            Double change = dailyChanges.get(asset.getId());
            if (change != null) {
                result.put(asset.getId(), change);
            }
        }
        return result;
    }

    // ---- TTL / resiliencia ------------------------------------------------

    private boolean needsRefresh(Long assetId, Duration ttl) {
        Instant last = lastQuoteFetch.get(assetId);
        return last == null || Duration.between(last, Instant.now()).compareTo(ttl) >= 0;
    }

    private static boolean isFresh(Instant fetchedAt, Duration ttl) {
        return fetchedAt != null && Duration.between(fetchedAt, Instant.now()).compareTo(ttl) < 0;
    }

    private static void logFailure(String operation, Exception e) {
        // Nao deixa a excecao subir e quebrar a tela (resiliencia, ATV-06) -
        // so loga e deixa quem chamou usar o ultimo valor conhecido.
        System.err.println("[MarketService] Falha em " + operation + ": " + e.getMessage());
    }
}
