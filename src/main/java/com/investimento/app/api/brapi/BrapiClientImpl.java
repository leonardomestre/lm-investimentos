package com.investimento.app.api.brapi;

import com.investimento.app.api.brapi.model.AssetQuote;
import com.investimento.app.api.brapi.model.HistoricalPoint;
import com.investimento.app.api.brapi.model.SearchResult;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Implementação HTTP (síncrona, stateless) de {@link BrapiClient}. Não
 * conhece cache/SQLite — quem decide se precisa buscar de novo é a ATV-06
 * ({@code MarketService}).
 */
public class BrapiClientImpl implements BrapiClient {

    private static final String BASE_URL = "https://brapi.dev/api";

    /**
     * Único subconjunto de tickers que aceita mais de 1 símbolo por
     * requisição, testado e documentado em {@code brapi-api/SKILL.md} seção
     * 3 — qualquer outra combinação falha com
     * {@code QUOTES_PER_REQUEST_EXCEEDED}. É detalhe de implementação da
     * chamada HTTP, não pertence à interface.
     */
    private static final Set<String> DEMO_GROUP = Set.of("PETR4", "VALE3", "ITUB4", "MGLU3");

    /**
     * Sem timeout, uma conexao aceita mas nunca respondida trava a thread
     * chamadora indefinidamente — inclusive a FX thread, via o caminho
     * sincrono de validacao de ticker no cadastro de ativo. Ver a mesma nota em
     * {@code HgBrasilClientImpl}.
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    private final String token;
    private final HttpClient httpClient;

    /** Contador simples de requisições HTTP feitas — útil para depuração e para os testes desta atividade. */
    private int requestCount = 0;

    public BrapiClientImpl(String token) {
        this.token = token;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    /**
     * Resolve o token por {@code BRAPI_TOKEN}. Nao ha token embutido no
     * codigo: este repositorio e publico e qualquer literal aqui vira
     * credencial vazada. O app real cadastra o token em Configuracoes
     * ({@code settings.brapi.token}, ver {@code ApiKeyResolver}); este
     * construtor existe so para os {@code main()} de teste manual.
     */
    public BrapiClientImpl() {
        this(System.getenv("BRAPI_TOKEN"));
    }

    public int getRequestCount() {
        return requestCount;
    }

    @Override
    public AssetQuote getQuote(String ticker) throws BrapiException {
        JSONObject root = get("/v2/stocks/quote", Map.of("symbols", ticker));
        JSONArray results = root.optJSONArray("results");
        if (results == null || results.isEmpty()) {
            throw new BrapiException("NOT_FOUND", 200, "Ticker não encontrado: " + ticker);
        }
        return toAssetQuote(ticker, results.getJSONObject(0));
    }

    @Override
    public Map<String, AssetQuote> getQuotes(List<String> tickers) throws BrapiException {
        if (tickers == null || tickers.isEmpty()) {
            throw new IllegalArgumentException("Informe ao menos 1 ticker.");
        }

        boolean allDemo = tickers.stream().allMatch(t -> DEMO_GROUP.contains(t.toUpperCase()));
        Map<String, AssetQuote> byTicker = new LinkedHashMap<>();

        if (allDemo) {
            // Grupo demo aceita combinação numa única chamada (SKILL.md seção 3).
            String symbolsCsv = String.join(",", tickers);
            JSONObject root = get("/v2/stocks/quote", Map.of("symbols", symbolsCsv));
            JSONArray results = root.optJSONArray("results");
            if (results == null) {
                results = new JSONArray();
            }

            // Correlaciona cada item da resposta ao ticker original (preservando
            // a grafia pedida pelo chamador) por comparação do símbolo
            // resolvido — sem depender do campo "requestedSymbol" do corpo.
            Map<String, String> upperToOriginal = new LinkedHashMap<>();
            for (String ticker : tickers) {
                upperToOriginal.put(ticker.toUpperCase(), ticker);
            }
            for (int i = 0; i < results.length(); i++) {
                JSONObject item = results.getJSONObject(i);
                String resolved = item.getString("symbol");
                String original = upperToOriginal.getOrDefault(resolved.toUpperCase(), resolved);
                byTicker.put(original, toAssetQuote(original, item));
            }
            return byTicker;
        }

        // Fora do grupo demo: 1 requisição por ticker, sequencial — agrupar
        // vai sempre falhar com QUOTES_PER_REQUEST_EXCEEDED (SKILL.md seção 3).
        for (String ticker : tickers) {
            byTicker.put(ticker, getQuote(ticker));
        }
        return byTicker;
    }

    @Override
    public List<HistoricalPoint> getHistory(String ticker, String range, String interval) throws BrapiException {
        // Ticker entra num segmento de PATH (nao de query), entao precisa ser
        // escapado aqui: um valor com "/", "?" ou espaco montaria outra URL
        // (ou faria URI.create lancar IllegalArgumentException crua, fora do
        // contrato BrapiException do metodo).
        JSONObject root = get("/quote/" + encodePathSegment(ticker), Map.of("range", range, "interval", interval));
        JSONArray results = root.optJSONArray("results");
        if (results == null || results.isEmpty()) {
            throw new BrapiException("NOT_FOUND", 200, "Ticker não encontrado: " + ticker);
        }
        JSONObject item = results.getJSONObject(0);
        JSONArray points = item.optJSONArray("historicalDataPrice");
        if (points == null) {
            points = new JSONArray();
        }

        List<HistoricalPoint> history = new ArrayList<>();
        try {
            for (int i = 0; i < points.length(); i++) {
                JSONObject point = points.getJSONObject(i);
                LocalDate date = Instant.ofEpochSecond(point.getLong("date")).atZone(ZoneOffset.UTC).toLocalDate();
                history.add(new HistoricalPoint(
                        date,
                        optDouble(point, "open"),
                        optDouble(point, "high"),
                        optDouble(point, "low"),
                        optDouble(point, "close"),
                        optLong(point, "volume"),
                        optDouble(point, "adjustedClose")
                ));
            }
        } catch (JSONException e) {
            throw new BrapiException("UNEXPECTED_PAYLOAD", 200,
                    "Formato inesperado no historico de " + ticker + ": " + e.getMessage());
        }
        return history;
    }

    @Override
    public List<SearchResult> searchTickers(String query) throws BrapiException {
        JSONObject root = get("/v2/tickers", Map.of("search", query));
        JSONArray results = root.optJSONArray("results");
        if (results == null) {
            results = new JSONArray();
        }

        List<SearchResult> searchResults = new ArrayList<>();
        try {
            for (int i = 0; i < results.length(); i++) {
                JSONObject item = results.getJSONObject(i);
                JSONObject quote = item.optJSONObject("quote");
                searchResults.add(new SearchResult(
                        item.optString("symbol", null),
                        item.optString("name", null),
                        item.optString("assetType", null),
                        item.optString("sector", null),
                        quote == null ? null : optDouble(quote, "lastPrice"),
                        item.optString("logoUrl", null)
                ));
            }
        } catch (JSONException e) {
            throw new BrapiException("UNEXPECTED_PAYLOAD", 200,
                    "Formato inesperado na busca de tickers: " + e.getMessage());
        }
        return searchResults;
    }

    // ---- parsing --------------------------------------------------------

    /**
     * Converte {@link JSONException}/{@link DateTimeParseException} em {@link
     * BrapiException}: os chamadores (MarketService, AssetService) so tratam a
     * excecao de dominio, entao uma mudanca de formato da API precisa chegar
     * como falha de fonte de dados, nao como RuntimeException solta que derruba
     * a tela.
     */
    private AssetQuote toAssetQuote(String requestedTicker, JSONObject item) {
        try {
            // "resolvedSymbol" é sempre lido do campo "symbol" da resposta
            // (ticker resolvido) — nunca de "requestedSymbol" (SKILL.md seção 7).
            String resolvedSymbol = item.getString("symbol");
            boolean changed = item.optBoolean("changed", false);
            JSONObject data = item.getJSONObject("data");

            return new AssetQuote(
                    requestedTicker,
                    resolvedSymbol,
                    changed,
                    optDouble(data, "regularMarketPrice"),
                    optDouble(data, "regularMarketChangePercent"),
                    optDouble(data, "regularMarketChange"),
                    optLong(data, "regularMarketVolume"),
                    optDouble(data, "marketCap"),
                    parseDateTime(data.optString("regularMarketTime", null))
            );
        } catch (JSONException | DateTimeParseException e) {
            throw new BrapiException("UNEXPECTED_PAYLOAD", 200,
                    "Formato inesperado na cotacao de " + requestedTicker + ": " + e.getMessage());
        }
    }

    private static Double optDouble(JSONObject json, String key) {
        return (json.has(key) && !json.isNull(key)) ? json.getDouble(key) : null;
    }

    private static Long optLong(JSONObject json, String key) {
        return (json.has(key) && !json.isNull(key)) ? json.getLong(key) : null;
    }

    private static LocalDateTime parseDateTime(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        return java.time.OffsetDateTime.parse(iso).toLocalDateTime();
    }

    /**
     * Escapa um valor para uso como segmento de path. {@code URLEncoder} e
     * feito para query string, onde espaco vira {@code "+"} — invalido num
     * path, por isso a troca por {@code %20}.
     */
    private static String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    // ---- HTTP -------------------------------------------------------------

    private JSONObject get(String path, Map<String, String> queryParams) throws BrapiException {
        // Falha rapida e acionavel: sem token a brapi responde 401 com uma
        // mensagem generica que nao diz ao usuario onde configurar.
        if (token == null || token.isBlank()) {
            throw new BrapiException("MISSING_TOKEN", 0,
                    "Token da brapi.dev nao configurado — cadastre em Configuracoes > Chaves de API "
                            + "ou defina a variavel de ambiente BRAPI_TOKEN.");
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
                .header("Authorization", "Bearer " + token)
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            requestCount++;

            JSONObject body;
            try {
                body = new JSONObject(response.body());
            } catch (JSONException e) {
                throw new BrapiException("Resposta inválida da brapi.dev (" + uri + ").", e);
            }

            // status >= 400 -> erro (o corpo ainda tem JSON com code/message).
            if (response.statusCode() >= 400) {
                throw new BrapiException(
                        body.optString("code", null),
                        response.statusCode(),
                        body.optString("message", "Erro HTTP " + response.statusCode() + " em " + path));
            }
            // status == 200 mas corpo tem "error": true -> erro tambem
            // (NOT_FOUND, FEATURE_NOT_AVAILABLE — SKILL.md seção 5).
            if (body.optBoolean("error", false)) {
                throw new BrapiException(
                        body.optString("code", "UNKNOWN_ERROR"),
                        response.statusCode(),
                        body.optString("message", "Erro desconhecido na resposta da brapi.dev."));
            }
            return body;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new BrapiException("Falha de rede ao chamar a brapi.dev (" + uri + ").", e);
        }
    }
}
