package com.investimento.app.api.coingecko;

import com.investimento.app.api.coingecko.model.CryptoPrice;
import com.investimento.app.api.coingecko.model.HistoricalPoint;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;

/**
 * Teste manual (não é JUnit — o projeto ainda não tem essa dependência,
 * mesma decisão tomada nas ATV-02/03/04). Chama a API real da CoinGecko e
 * imprime o resultado para conferência visual contra
 * {@code .claude/skills/coingecko-api/references/campos.md}.
 *
 * <p>Roda com (copie {@code target/classes} E os jars de dependência para um
 * caminho sem espaço antes — gotcha registrado na ATV-04/CLAUDE.md, o
 * classpath quebra inteiro se qualquer entrada tiver espaço no caminho, ex.:
 * a própria pasta do projeto "LM Investimentos"):
 * <pre>
 * mvn -q compile
 * java -cp "&lt;target-classes-sem-espaco&gt;;&lt;sqlite-jdbc.jar-sem-espaco&gt;;&lt;json.jar-sem-espaco&gt;" com.investimento.app.api.coingecko.CoinGeckoClientManualTest
 * </pre>
 */
public class CoinGeckoClientManualTest {

    public static void main(String[] args) {
        CoinGeckoClientImpl client = new CoinGeckoClientImpl();

        System.out.println("=== getPrices([BTC, ETH, XRP]) ===");
        int before = client.getRequestCount();
        Map<String, CryptoPrice> prices = client.getPrices(List.of("BTC", "ETH", "XRP"));
        int used = client.getRequestCount() - before;
        prices.forEach((symbol, price) -> System.out.println("  " + symbol + " -> " + price));
        System.out.println("Requisicoes usadas para 3 simbolos (esperado 1): " + used);
        if (used != 1) {
            System.out.println("FALHOU: esperava exatamente 1 requisicao para os 3 simbolos.");
        }

        System.out.println();
        System.out.println("=== getHistory(\"ETH\", 30) ===");
        List<HistoricalPoint> history30 = client.getHistory("ETH", 30);
        System.out.println(history30.size() + " pontos (esperado ~721), primeiro: " + history30.get(0)
                + ", ultimo: " + history30.get(history30.size() - 1));
        if (history30.size() < 700 || history30.size() > 745) {
            System.out.println("ATENCAO: contagem de pontos fora do esperado (~721).");
        }

        System.out.println();
        System.out.println("=== Id ausente na resposta (simulado, sem depender do acaso da API) ===");
        // Constroi uma resposta fake em que o id "ripple" (XRP) NAO veio,
        // como se a API tivesse devolvido {} pra ele (comportamento real de
        // id invalido nesta API: HTTP 200, corpo sem a chave esperada).
        JSONObject fakeBody = new JSONObject();
        fakeBody.put("bitcoin", new JSONObject().put("brl", 330000.0));
        try {
            CoinGeckoClientImpl.checkMissingIds(fakeBody, List.of("BTC", "XRP"), List.of("bitcoin", "ripple"));
            System.out.println("FALHOU: esperava CoinGeckoException, nao foi lancada.");
        } catch (CoinGeckoException e) {
            System.out.println("OK, CoinGeckoException lancada como esperado: " + e.getMessage());
        }

        System.out.println();
        System.out.println("=== Simbolo fora do ID_MAP (erro esperado, instantaneo) ===");
        try {
            client.getPrices(List.of("BTC", "NAOEXISTE"));
            System.out.println("FALHOU: esperava CoinGeckoException, nao foi lancada.");
        } catch (CoinGeckoException e) {
            System.out.println("OK, CoinGeckoException lancada como esperado: " + e.getMessage());
        }

        System.out.println();
        System.out.println("=== getHistory(\"BTC\", 400) — deve falhar SEM tocar a rede ===");
        int beforeHistoryFail = client.getRequestCount();
        long start = System.nanoTime();
        try {
            client.getHistory("BTC", 400);
            System.out.println("FALHOU: esperava CoinGeckoException, nao foi lancada.");
        } catch (CoinGeckoException e) {
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            int usedForFail = client.getRequestCount() - beforeHistoryFail;
            System.out.println("OK, CoinGeckoException lancada como esperado: " + e.getMessage());
            System.out.println("Tempo decorrido: " + elapsedMs + "ms, requisicoes de rede usadas: " + usedForFail);
            if (usedForFail != 0) {
                System.out.println("FALHOU: esperava 0 requisicoes de rede (validacao deveria ser local).");
            }
        }
    }
}
