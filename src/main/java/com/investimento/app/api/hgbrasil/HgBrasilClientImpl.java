package com.investimento.app.api.hgbrasil;

import com.investimento.app.api.hgbrasil.model.Currency;
import com.investimento.app.api.hgbrasil.model.DailyRate;
import com.investimento.app.api.hgbrasil.model.IndicatorPoint;
import com.investimento.app.api.hgbrasil.model.MacroSnapshot;
import com.investimento.app.api.hgbrasil.model.MarketIndex;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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

    /**
     * Sem timeout, uma conexao que o servidor aceita mas nunca responde
     * (rede corporativa/captive portal que engole o pacote em vez de recusar)
     * trava a thread chamadora para sempre — e {@code DashboardView.refresh()}
     * chama {@code getMacroSnapshot()} de forma sincrona na FX thread, ou seja,
     * a janela inteira congela sem nunca se recuperar. Os dois limites abaixo
     * transformam esse cenario numa {@code HgBrasilException} tratavel.
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    /** Chaves de {@code results.currencies} que não são blocos de moeda. */
    private static final List<String> CURRENCIES_NON_ISO_KEYS = List.of("source", "available_sources");

    private final String apiKey;
    private final HttpClient httpClient;

    /** Contador simples de requisicoes HTTP feitas — mesmo padrao da ATV-04/05, usado pelos testes da ATV-06 (cache). */
    private int requestCount = 0;

    public HgBrasilClientImpl(String apiKey) {
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    /**
     * Resolve a chave por {@code HG_BRASIL_KEY}. Nao ha chave embutida no
     * codigo: este repositorio e publico e qualquer literal aqui vira
     * credencial vazada. Quem roda o app de verdade cadastra a chave em
     * Configuracoes ({@code settings.hgbrasil.apiKey}, ver
     * {@code ApiKeyResolver}); este construtor existe so para os {@code main()}
     * de teste manual.
     */
    public HgBrasilClientImpl() {
        this(System.getenv("HG_BRASIL_KEY"));
    }

    public int getRequestCount() {
        return requestCount;
    }

    @Override
    public MacroSnapshot getSnapshot() throws HgBrasilException {
        JSONObject root = get("/finance", Map.of("key", apiKey));
        checkV1Errors(root);

        // Todo `getX` do org.json lanca JSONException (RuntimeException) quando
        // o campo falta ou muda de tipo. Convertida aqui para a excecao de
        // dominio porque e so ela que MarketService captura — sem isso, uma
        // mudanca no formato da API derruba a tela em vez de cair no cache.
        try {
            JSONObject results = root.getJSONObject("results");

            Map<String, Currency> currencies = parseCurrencies(results.getJSONObject("currencies"));
            Map<String, MarketIndex> indices = parseIndices(results.getJSONObject("stocks"));
            DailyRate todayRate = parseDailyRate(results.getJSONArray("taxes").getJSONObject(0));

            return new MacroSnapshot(currencies, indices, todayRate);
        } catch (JSONException e) {
            throw new HgBrasilException("UNEXPECTED_PAYLOAD",
                    "Formato inesperado no /finance da HG Brasil: " + e.getMessage());
        }
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
        try {
            for (int i = 0; i < results.length(); i++) {
                JSONObject item = results.getJSONObject(i);
                String ticker = item.getString("ticker");
                byTicker.put(ticker, parseSeries(item.getJSONArray("series")));
            }
        } catch (JSONException e) {
            throw new HgBrasilException("UNEXPECTED_PAYLOAD",
                    "Formato inesperado nos indicadores da HG Brasil: " + e.getMessage());
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
        try {
            JSONObject item = results.getJSONObject(0);
            JSONArray series = item.getJSONArray("series");
            if (series.isEmpty()) {
                throw new HgBrasilException("EMPTY_SERIES", "Série vazia para o ticker " + ticker);
            }
            JSONObject point = series.getJSONObject(0);
            return new IndicatorPoint(point.getString("period"), point.getDouble("value"));
        } catch (JSONException e) {
            throw new HgBrasilException("UNEXPECTED_PAYLOAD",
                    "Formato inesperado no indicador " + ticker + ": " + e.getMessage());
        }
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
        // Falha rapida e com mensagem acionavel quando ninguem configurou a
        // chave: sem isto a requisicao sai com "key=null" e volta um erro
        // generico de chave invalida, que nao diz ao usuario o que fazer.
        if (apiKey == null || apiKey.isBlank()) {
            throw new HgBrasilException("MISSING_API_KEY",
                    "Chave da HG Brasil nao configurada — cadastre em Configuracoes > Chaves de API "
                            + "ou defina a variavel de ambiente HG_BRASIL_KEY.");
        }

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
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
        try {
            requestCount++;
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            // A API responde HTTP 200 mesmo em erro — nunca confie só no status.
            try {
                return new JSONObject(response.body());
            } catch (JSONException e) {
                // Corpo nao-JSON (pagina de erro HTML do gateway, rate limit,
                // manutencao). Sem esta conversao a JSONException crua sobe ate
                // a FX thread: MarketService so captura HgBrasilException, entao
                // a tela quebraria em vez de cair no ultimo valor cacheado.
                throw new HgBrasilException("INVALID_RESPONSE",
                        "Resposta invalida da HG Brasil (" + uri + "): " + summarize(response.body()));
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new HgBrasilException("Falha de rede ao chamar a HG Brasil (" + uri + ").", e);
        }
    }

    /** Primeiros caracteres do corpo, para a mensagem de erro nao despejar uma pagina HTML inteira no log. */
    private static String summarize(String body) {
        if (body == null || body.isBlank()) {
            return "(corpo vazio)";
        }
        String flat = body.strip().replaceAll("\\s+", " ");
        return flat.length() <= 120 ? flat : flat.substring(0, 120) + "...";
    }
}
