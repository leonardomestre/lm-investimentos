package com.investimento.app.api.hgbrasil;

import com.investimento.app.api.hgbrasil.model.Currency;
import com.investimento.app.api.hgbrasil.model.DailyRate;
import com.investimento.app.api.hgbrasil.model.IndicatorPoint;
import com.investimento.app.api.hgbrasil.model.MacroSnapshot;
import com.investimento.app.api.hgbrasil.model.MarketIndex;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Implementação HTTP (síncrona, stateless) de {@link HgBrasilClient}. Não
 * conhece cache/SQLite — quem decide se precisa buscar de novo é a ATV-06
 * ({@code MarketService}).
 */
public class HgBrasilClientImpl implements HgBrasilClient {

    private static final String BASE_URL = "https://api.hgbrasil.com";

    /** Chaves de {@code results.currencies} que não são blocos de moeda. */
    private static final List<String> CURRENCIES_NON_ISO_KEYS = List.of("source", "available_sources");

    private final String apiKey;
    private final HttpClient httpClient;

    /** Contador simples de requisicoes HTTP feitas — mesmo padrao da ATV-04/05, usado pelos testes da ATV-06 (cache). */
    private int requestCount = 0;

    public HgBrasilClientImpl(String apiKey) {
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newHttpClient();
    }

    public HgBrasilClientImpl() {
        this(System.getenv("HG_BRASIL_KEY") != null ? System.getenv("HG_BRASIL_KEY") : "ee3b78db");
    }

    public int getRequestCount() {
        return requestCount;
    }

    @Override
    public MacroSnapshot getSnapshot() throws HgBrasilException {
        JSONObject root = get("/finance", Map.of("key", apiKey));
        checkV1Errors(root);
        JSONObject results = root.getJSONObject("results");

        Map<String, Currency> currencies = parseCurrencies(results.getJSONObject("currencies"));
        Map<String, MarketIndex> indices = parseIndices(results.getJSONObject("stocks"));
        DailyRate todayRate = parseDailyRate(results.getJSONArray("taxes").getJSONObject(0));

        return new MacroSnapshot(currencies, indices, todayRate);
    }

    @Override
    public Map<String, List<IndicatorPoint>> getIndicators(String... tickers) throws HgBrasilException {
        if (tickers == null || tickers.length == 0) {
            throw new IllegalArgumentException("Informe ao menos 1 ticker.");
        }
        String tickersCsv = String.join(",", tickers);
        JSONObject root = get("/v2/finance/indicators", Map.of("key", apiKey, "tickers", tickersCsv));
        JSONArray results = checkV2Errors(root);

        Map<String, List<IndicatorPoint>> byTicker = new LinkedHashMap<>();
        for (int i = 0; i < results.length(); i++) {
            JSONObject item = results.getJSONObject(i);
            String ticker = item.getString("ticker");
            byTicker.put(ticker, parseSeries(item.getJSONArray("series")));
        }
        return byTicker;
    }

    @Override
    public IndicatorPoint getCurrentIndicator(String ticker) throws HgBrasilException {
        JSONObject root = get("/v2/finance/indicators", Map.of(
                "key", apiKey,
                "tickers", ticker,
                "days_ago", "0"));
        JSONArray results = checkV2Errors(root);

        if (results.isEmpty()) {
            throw new HgBrasilException("EMPTY_RESULT", "Nenhum valor vigente retornado para o ticker " + ticker);
        }
        JSONObject item = results.getJSONObject(0);
        JSONArray series = item.getJSONArray("series");
        if (series.isEmpty()) {
            throw new HgBrasilException("EMPTY_SERIES", "Série vazia para o ticker " + ticker);
        }
        JSONObject point = series.getJSONObject(0);
        return new IndicatorPoint(point.getString("period"), point.getDouble("value"));
    }

    // ---- parsing ----------------------------------------------------

    private Map<String, Currency> parseCurrencies(JSONObject currenciesJson) {
        Map<String, Currency> currencies = new LinkedHashMap<>();
        for (String iso : currenciesJson.keySet()) {
            if (CURRENCIES_NON_ISO_KEYS.contains(iso)) {
                continue;
            }
            Object value = currenciesJson.get(iso);
            if (!(value instanceof JSONObject block)) {
                continue;
            }
            currencies.put(iso, new Currency(
                    iso,
                    block.optString("name", iso),
                    block.getDouble("buy"),
                    block.isNull("sell") ? null : block.optDouble("sell"),
                    block.optDouble("variation", 0.0)
            ));
        }
        return currencies;
    }

    private Map<String, MarketIndex> parseIndices(JSONObject stocksJson) {
        Map<String, MarketIndex> indices = new LinkedHashMap<>();
        for (String name : stocksJson.keySet()) {
            JSONObject block = stocksJson.getJSONObject(name);
            indices.put(name, new MarketIndex(
                    block.optString("name", name),
                    block.optString("location", null),
                    block.getDouble("points"),
                    block.optDouble("variation", 0.0)
            ));
        }
        return indices;
    }

    private DailyRate parseDailyRate(JSONObject taxes) {
        return new DailyRate(
                LocalDate.parse(taxes.getString("date")),
                taxes.getDouble("cdi"),
                taxes.getDouble("selic"),
                taxes.optDouble("cdi_daily", 0.0),
                taxes.optDouble("selic_daily", 0.0),
                taxes.optDouble("daily_factor", 0.0)
        );
    }

    private List<IndicatorPoint> parseSeries(JSONArray seriesJson) {
        List<IndicatorPoint> series = new ArrayList<>();
        for (int i = 0; i < seriesJson.length(); i++) {
            JSONObject point = seriesJson.getJSONObject(i);
            series.add(new IndicatorPoint(point.getString("period"), point.getDouble("value")));
        }
        return series;
    }

    // ---- tratamento de erro ------------------------------------------

    /**
     * v1 (/finance, /finance/taxes): erro se {@code valid_key} for
     * {@code false}, ou se {@code results} for um objeto com
     * {@code error: true} ({@code results.message} traz o motivo).
     */
    private void checkV1Errors(JSONObject root) {
        if (!root.optBoolean("valid_key", true)) {
            throw new HgBrasilException("INVALID_API_KEY", "Chave da HG Brasil inválida.");
        }
        Object results = root.opt("results");
        if (results instanceof JSONObject resultsObj && resultsObj.optBoolean("error", false)) {
            throw new HgBrasilException("RESULT_ERROR", resultsObj.optString("message", "Erro desconhecido na resposta da HG Brasil."));
        }
    }

    /**
     * v2 (/v2/finance/indicators): erro se o array {@code errors} existir e
     * não estiver vazio. {@code results} pode vir parcial junto com
     * {@code errors} parcial — não trata como tudo-ou-nada: só lança se
     * {@code results} vier vazio E houver erro.
     */
    private JSONArray checkV2Errors(JSONObject root) {
        JSONArray results = root.optJSONArray("results");
        if (results == null) {
            results = new JSONArray();
        }
        JSONArray errors = root.optJSONArray("errors");
        if (errors != null && !errors.isEmpty()) {
            if (results.isEmpty()) {
                JSONObject firstError = errors.getJSONObject(0);
                throw new HgBrasilException(
                        firstError.optString("code", "UNKNOWN_ERROR"),
                        firstError.optString("message", "Erro desconhecido na resposta da HG Brasil."));
            }
            // resultado parcial: quem chamou decide o que fazer com os
            // tickers que falharam (não lançamos, results já tem o que veio).
        }
        return results;
    }

    // ---- HTTP ---------------------------------------------------------

    private JSONObject get(String path, Map<String, String> queryParams) throws HgBrasilException {
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, String> entry : queryParams.entrySet()) {
            if (!query.isEmpty()) {
                query.append('&');
            }
            query.append(entry.getKey())
                    .append('=')
                    .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        URI uri = URI.create(BASE_URL + path + "?" + query);

        HttpRequest request = HttpRequest.newBuilder(uri)
                .GET()
                .build();
        try {
            requestCount++;
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            // A API responde HTTP 200 mesmo em erro — nunca confie só no status.
            return new JSONObject(response.body());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new HgBrasilException("Falha de rede ao chamar a HG Brasil (" + uri + ").", e);
        }
    }
}
