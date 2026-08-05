package com.investimento.app.api.coingecko;

import com.investimento.app.api.coingecko.model.CryptoPrice;
import com.investimento.app.api.coingecko.model.HistoricalPoint;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Implementação HTTP (síncrona, stateless) de {@link CoinGeckoClient}. API
 * pública, sem chave — construtor sem parâmetros. Não conhece cache/SQLite —
 * quem decide se precisa buscar de novo é a ATV-06 ({@code MarketService}).
 */
public class CoinGeckoClientImpl implements CoinGeckoClient {

    private static final String BASE_URL = "https://api.coingecko.com/api/v3";

    /**
     * Mapa fixo símbolo -> id CoinGecko, testado (ver
     * {@code coingecko-api/references/moedas.md}). Escopo de criptomoedas
     * suportadas no MVP — nunca resolver id a partir de texto livre do
     * usuário em runtime (símbolos são ambíguos, ~18 mil moedas cadastradas
     * compartilham símbolo).
     */
    private static final Map<String, String> ID_MAP = Map.of(
            "BTC", "bitcoin",
            "ETH", "ethereum",
            "XRP", "ripple",
            "BNB", "binancecoin",
            "ADA", "cardano",
            "SOL", "solana",
            "DOGE", "dogecoin",
            "LTC", "litecoin"
    );

    private static final int MAX_HISTORY_DAYS = 365;

    private final HttpClient httpClient;

    /** Contador simples de requisições HTTP feitas — usado nos testes desta atividade. */
    private int requestCount = 0;

    public CoinGeckoClientImpl() {
        this.httpClient = HttpClient.newHttpClient();
    }

    public int getRequestCount() {
        return requestCount;
    }

    @Override
    public Set<String> supportedSymbols() {
        return ID_MAP.keySet();
    }

    @Override
    public Map<String, CryptoPrice> getPrices(List<String> symbols) throws CoinGeckoException {
        if (symbols == null || symbols.isEmpty()) {
            throw new IllegalArgumentException("Informe ao menos 1 símbolo.");
        }

        List<String> upperSymbols = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (String symbol : symbols) {
            String upper = symbol.toUpperCase();
            String id = ID_MAP.get(upper);
            if (id == null) {
                throw new CoinGeckoException(
                        "Símbolo '" + symbol + "' não está no mapa fixo de moedas suportadas ("
                                + String.join(", ", ID_MAP.keySet()) + ").");
            }
            upperSymbols.add(upper);
            ids.add(id);
        }

        // Sempre agrupa todos os ids numa única chamada — o oposto da brapi.dev
        // (SKILL.md seção 6).
        JSONObject body = get("/simple/price", Map.of(
                "ids", String.join(",", ids),
                "vs_currencies", "brl",
                "include_24hr_change", "true",
                "include_last_updated_at", "true"
        ));

        // /simple/price NUNCA sinaliza erro (HTTP 200 + {} para id inválido) —
        // a única forma de detectar falha é checar presença de cada id pedido
        // na resposta (SKILL.md seção 4, obrigatório).
        checkMissingIds(body, upperSymbols, ids);

        Map<String, CryptoPrice> result = new LinkedHashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            String symbol = upperSymbols.get(i);
            JSONObject item = body.getJSONObject(ids.get(i));
            result.put(symbol, new CryptoPrice(
                    symbol,
                    optDouble(item, "brl"),
                    optDouble(item, "brl_24h_change"),
                    // last_updated_at vem em SEGUNDOS aqui — diferente de
                    // market_chart, que vem em milissegundos (getHistory).
                    item.has("last_updated_at") && !item.isNull("last_updated_at")
                            ? LocalDateTime.ofInstant(Instant.ofEpochSecond(item.getLong("last_updated_at")), ZoneOffset.UTC)
                            : null
            ));
        }
        return result;
    }

    @Override
    public List<HistoricalPoint> getHistory(String symbol, int days) throws CoinGeckoException {
        // Valida localmente ANTES de tocar a rede — falha rápida com mensagem
        // clara, em vez de round-trip para um erro que a API já rejeitaria
        // (SKILL.md seção 5).
        if (days > MAX_HISTORY_DAYS) {
            throw new CoinGeckoException(
                    "days=" + days + " excede o teto do plano gratuito (" + MAX_HISTORY_DAYS + ").");
        }

        String upper = symbol.toUpperCase();
        String id = ID_MAP.get(upper);
        if (id == null) {
            throw new CoinGeckoException(
                    "Símbolo '" + symbol + "' não está no mapa fixo de moedas suportadas ("
                            + String.join(", ", ID_MAP.keySet()) + ").");
        }

        JSONObject body = get("/coins/" + id + "/market_chart", Map.of(
                "vs_currency", "brl",
                "days", String.valueOf(days)
        ));

        // market_chart SINALIZA erro normalmente, mas com formato próprio
        // (error.status.error_message) — diferente de tudo mais nesta API,
        // não reusar o parser de /simple/price para este endpoint.
        if (body.has("error")) {
            Object errorField = body.get("error");
            String message;
            if (errorField instanceof JSONObject errorObj) {
                JSONObject status = errorObj.optJSONObject("status");
                message = status != null ? status.optString("error_message", errorObj.toString()) : errorObj.toString();
            } else {
                message = String.valueOf(errorField);
            }
            throw new CoinGeckoException(message);
        }

        JSONArray prices = body.optJSONArray("prices");
        if (prices == null) {
            prices = new JSONArray();
        }

        List<HistoricalPoint> history = new ArrayList<>();
        for (int i = 0; i < prices.length(); i++) {
            JSONArray point = prices.getJSONArray(i);
            // Timestamp em MILISSEGUNDOS aqui — diferente de /simple/price
            // (last_updated_at em segundos, ver getPrices).
            LocalDate date = Instant.ofEpochMilli(point.getLong(0)).atZone(ZoneOffset.UTC).toLocalDate();
            history.add(new HistoricalPoint(date, point.getDouble(1)));
        }
        return history;
    }

    // ---- parsing --------------------------------------------------------

    /**
     * Confirma que cada {@code id} pedido está presente como chave em
     * {@code body} — a única forma de detectar erro em
     * {@code /simple/price}, que nunca sinaliza falha (HTTP 200 + corpo
     * {@code {}} para id inválido). Extraído como método próprio (equivalente
     * a {@code _check_missing_ids()} do cliente Python de referência) para
     * poder ser exercitado de forma determinística em teste, sem depender de
     * a API real "por acaso" omitir um id.
     *
     * <p>Package-private para ser visível ao teste manual desta atividade.
     */
    static void checkMissingIds(JSONObject body, List<String> upperSymbols, List<String> ids) {
        List<String> missing = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            if (!body.has(ids.get(i))) {
                missing.add(upperSymbols.get(i));
            }
        }
        if (!missing.isEmpty()) {
            throw new CoinGeckoException(
                    "Sem preço para: " + String.join(", ", missing)
                            + " — id inválido ou moeda sem par nas moedas de referência pedidas. "
                            + "A API não informa o motivo.");
        }
    }

    private static Double optDouble(JSONObject json, String key) {
        return (json.has(key) && !json.isNull(key)) ? json.getDouble(key) : null;
    }

    // ---- HTTP -------------------------------------------------------------

    private JSONObject get(String path, Map<String, String> queryParams) throws CoinGeckoException {
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
                .header("User-Agent", "investimento/coingecko-client")
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            requestCount++;

            try {
                return new JSONObject(response.body());
            } catch (JSONException e) {
                throw new CoinGeckoException("Resposta inválida da CoinGecko (" + uri + ").", e);
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new CoinGeckoException("Falha de rede ao chamar a CoinGecko (" + uri + ").", e);
        }
    }
}
