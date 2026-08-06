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
import com.investimento.app.dto.SyncEvent;
import com.investimento.app.repository.IndicatorHistoryRepository;
import com.investimento.app.repository.QuoteHistoryRepository;
import com.investimento.app.repository.RateHistoryRepository;
import com.investimento.app.repository.SettingRepository;
import javafx.concurrent.Task;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

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

    /**
     * Chave em {@code settings} (ATV-18) que sobrescreve
     * {@link #BRAPI_QUOTE_TTL}/{@link #COINGECKO_QUOTE_TTL} — "intervalo de
     * atualização automática de cotações", em minutos. {@link #MACRO_TTL}/
     * {@link #INDICATORS_TTL} (painel macro/indicadores da HG Brasil) não são
     * afetados — a ATV-18 fala especificamente de "cotações", não do painel
     * macro/indicadores.
     */
    static final String SETTING_UPDATE_INTERVAL_MINUTES = "updateIntervalMinutes";

    /**
     * Mesma chave que {@code SettingsView.SETTING_PAUSE_WEEKENDS} (pacote
     * {@code ui.screens}, inacessível daqui) — literal duplicado de propósito,
     * mesmo padrão já usado por {@link #SETTING_UPDATE_INTERVAL_MINUTES}.
     * "Pausar em fins de semana e feriados" — só cobre sábado/domingo
     * ({@code LocalDate.getDayOfWeek()}); feriados móveis da B3 exigiriam um
     * calendário externo, não implementado.
     */
    static final String SETTING_PAUSE_WEEKENDS = "updateSchedule.pauseWeekends";

    private static final int MAX_RECENT_SYNCS = 10;

    private static final String[] INDICATOR_TICKERS = {"IBGE:IPCA", "BCB:SELICMETA"};

    private final HgBrasilClient hgBrasilClient;
    private final BrapiClient brapiClient;
    private final CoinGeckoClient coinGeckoClient;
    private final RateHistoryRepository rateHistoryRepository;
    private final IndicatorHistoryRepository indicatorHistoryRepository;
    private final QuoteHistoryRepository quoteHistoryRepository;
    private final SettingRepository settingRepository;

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

    // Ultima falha de rede (qualquer uma das 3 APIs) - limpa na proxima busca
    // bem sucedida da MESMA operacao. So em memoria (reinicia com o app).
    private volatile String lastFailureMessage;

    // Ultimas tentativas de sincronizacao por fonte, mais recente primeiro -
    // so em memoria, cap de MAX_RECENT_SYNCS.
    private final Deque<SyncEvent> recentSyncs = new ConcurrentLinkedDeque<>();

    public MarketServiceImpl(HgBrasilClient hgBrasilClient,
                              BrapiClient brapiClient,
                              CoinGeckoClient coinGeckoClient,
                              RateHistoryRepository rateHistoryRepository,
                              IndicatorHistoryRepository indicatorHistoryRepository,
                              QuoteHistoryRepository quoteHistoryRepository,
                              SettingRepository settingRepository) {
        this.hgBrasilClient = hgBrasilClient;
        this.brapiClient = brapiClient;
        this.coinGeckoClient = coinGeckoClient;
        this.rateHistoryRepository = rateHistoryRepository;
        this.indicatorHistoryRepository = indicatorHistoryRepository;
        this.quoteHistoryRepository = quoteHistoryRepository;
        this.settingRepository = settingRepository;
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
            clearFailure();
            return snapshot;
        } catch (HgBrasilException e) {
            logFailure("getMacroSnapshot", e);
            // Sem internet/chave invalida: devolve o ultimo valor conhecido
            // (pode ser null se nunca buscou com sucesso - o chamador decide
            // o placeholder, RT06/resiliencia da ATV-06).
            return macroSnapshotCache;
        }
    }

    @Override
    public MacroSnapshot getCachedMacroSnapshot() {
        return macroSnapshotCache;
    }

    @Override
    public Map<String, List<IndicatorPoint>> getCachedIndicators() {
        return indicatorsCache;
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
            clearFailure();
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
                if (isWeekendPauseActive()) {
                    updateMessage("Atualização pausada — fim de semana (mercado fechado).");
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
     * "Pausar em fins de semana e feriados" (settings, {@link
     * #SETTING_PAUSE_WEEKENDS}, default {@code true} — mesmo default do
     * template) — só cobre sábado/domingo; feriados da B3 não são cobertos
     * (exigiria um calendário externo, não implementado).
     */
    private boolean isWeekendPauseActive() {
        if (settingRepository == null) {
            return false;
        }
        boolean pauseEnabled = Boolean.parseBoolean(settingRepository.get(SETTING_PAUSE_WEEKENDS, "true"));
        if (!pauseEnabled) {
            return false;
        }
        DayOfWeek day = LocalDate.now().getDayOfWeek();
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
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
            recordSync("HG Brasil", hgAssets.size(), false);
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
        recordSync("HG Brasil", hgAssets.size(), true);
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
                .filter(a -> needsRefresh(a.getId(), configuredQuoteTtl(BRAPI_QUOTE_TTL)))
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
            clearFailure();
            recordSync("brapi.dev", brapiAssets.size(), true);
        } catch (BrapiException e) {
            logFailure("updateQuotes(BRAPI)", e);
            recordSync("brapi.dev", brapiAssets.size(), false);
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
                .filter(a -> needsRefresh(a.getId(), configuredQuoteTtl(COINGECKO_QUOTE_TTL)))
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
            clearFailure();
            recordSync("CoinGecko", cryptoAssets.size(), true);
        } catch (CoinGeckoException e) {
            logFailure("updateQuotes(COINGECKO)", e);
            recordSync("CoinGecko", cryptoAssets.size(), false);
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

    /**
     * O seed monta a lista inteira e grava de uma vez ({@code upsertAll}, uma
     * transacao so). Um upsert por ponto seguraria a conexao unica do app por
     * milhares de transacoes seguidas — {@code range=max} traz ~6,5 mil pontos
     * para um ativo antigo — congelando a UI, que compartilha essa conexao.
     */
    private void seedFromBrapi(Asset asset) {
        try {
            List<com.investimento.app.api.brapi.model.HistoricalPoint> history =
                    brapiClient.getHistory(asset.getSourceIdentifier(), "max", "1d");
            List<QuoteHistory> quotes = new ArrayList<>();
            for (com.investimento.app.api.brapi.model.HistoricalPoint point : history) {
                Double price = point.adjustedClose() != null ? point.adjustedClose() : point.close();
                if (price == null) {
                    continue;
                }
                quotes.add(QuoteHistory.builder()
                        .assetId(asset.getId())
                        .date(point.date())
                        .price(price)
                        .source(QuoteSource.BRAPI)
                        .build());
            }
            quoteHistoryRepository.upsertAll(deduplicateByDate(quotes));
        } catch (BrapiException e) {
            logFailure("seedInitialHistory(BRAPI, " + asset.getSourceIdentifier() + ")", e);
        }
    }

    private void seedFromCoinGecko(Asset asset) {
        try {
            List<com.investimento.app.api.coingecko.model.HistoricalPoint> history =
                    coinGeckoClient.getHistory(asset.getSourceIdentifier(), 365);
            List<QuoteHistory> quotes = new ArrayList<>();
            for (com.investimento.app.api.coingecko.model.HistoricalPoint point : history) {
                if (point.price() == null) {
                    continue;
                }
                quotes.add(QuoteHistory.builder()
                        .assetId(asset.getId())
                        .date(point.date())
                        .price(point.price())
                        .source(QuoteSource.COINGECKO)
                        .build());
            }
            quoteHistoryRepository.upsertAll(deduplicateByDate(quotes));
        } catch (CoinGeckoException e) {
            logFailure("seedInitialHistory(COINGECKO, " + asset.getSourceIdentifier() + ")", e);
        }
    }

    /**
     * Mantem so o ultimo ponto de cada data. Necessario porque
     * {@code ON CONFLICT} resolve conflito com linhas <b>ja gravadas</b>, nao
     * entre linhas do mesmo lote — e a CoinGecko devolve granularidade horaria
     * quando {@code days} e grande (varios pontos por dia), o que faria o
     * batch inteiro falhar com {@code UNIQUE constraint failed}.
     */
    private static List<QuoteHistory> deduplicateByDate(List<QuoteHistory> quotes) {
        Map<LocalDate, QuoteHistory> byDate = new LinkedHashMap<>();
        for (QuoteHistory quote : quotes) {
            byDate.put(quote.getDate(), quote);
        }
        return new ArrayList<>(byDate.values());
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

    /**
     * ATV-18: {@code settings.updateIntervalMinutes}, quando presente e
     * válido (número &gt; 0, minutos — aceita fração para permitir testes
     * automatizados rápidos sem esperar minutos inteiros), sobrescreve o TTL
     * padrão passado em {@code defaultTtl} (BRAPI 15 min / COINGECKO 5 min).
     * Sem configuração (ou valor inválido/≤0), usa o TTL padrão da ATV-06
     * sem alteração nenhuma.
     */
    private Duration configuredQuoteTtl(Duration defaultTtl) {
        if (settingRepository == null) {
            return defaultTtl;
        }
        String raw = settingRepository.get(SETTING_UPDATE_INTERVAL_MINUTES, null);
        if (raw == null || raw.isBlank()) {
            return defaultTtl;
        }
        try {
            double minutes = Double.parseDouble(raw.trim());
            if (minutes <= 0) {
                return defaultTtl;
            }
            return Duration.ofMillis(Math.round(minutes * 60_000));
        } catch (NumberFormatException e) {
            return defaultTtl;
        }
    }

    private boolean needsRefresh(Long assetId, Duration ttl) {
        Instant last = lastQuoteFetch.get(assetId);
        return last == null || Duration.between(last, Instant.now()).compareTo(ttl) >= 0;
    }

    private static boolean isFresh(Instant fetchedAt, Duration ttl) {
        return fetchedAt != null && Duration.between(fetchedAt, Instant.now()).compareTo(ttl) < 0;
    }

    private void logFailure(String operation, Exception e) {
        // Nao deixa a excecao subir e quebrar a tela (resiliencia, ATV-06) -
        // so loga e deixa quem chamou usar o ultimo valor conhecido.
        System.err.println("[MarketService] Falha em " + operation + ": " + e.getMessage());
        lastFailureMessage = operation + ": " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
    }

    private void clearFailure() {
        lastFailureMessage = null;
    }

    @Override
    public Optional<String> getLastFailure() {
        return Optional.ofNullable(lastFailureMessage);
    }

    private void recordSync(String source, int assetCount, boolean success) {
        recentSyncs.addFirst(new SyncEvent(Instant.now(), source, assetCount, success));
        while (recentSyncs.size() > MAX_RECENT_SYNCS) {
            recentSyncs.removeLast();
        }
    }

    @Override
    public List<SyncEvent> getRecentSyncs() {
        return new ArrayList<>(recentSyncs);
    }
}
