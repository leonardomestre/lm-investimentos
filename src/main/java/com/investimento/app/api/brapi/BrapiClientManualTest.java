package com.investimento.app.api.brapi;

import com.investimento.app.api.brapi.model.AssetQuote;
import com.investimento.app.api.brapi.model.HistoricalPoint;
import com.investimento.app.api.brapi.model.SearchResult;

import java.util.List;
import java.util.Map;

/**
 * Teste manual (não é JUnit — o projeto ainda não tem essa dependência,
 * mesma decisão tomada na ATV-02/03). Chama a API real da brapi.dev e
 * imprime o resultado para conferência visual contra
 * {@code .claude/skills/brapi-api/references/campos.md}.
 *
 * <p>Roda com (copie {@code target/classes} E os jars de dependência para um
 * caminho sem espaço antes — ver gotcha da ATV-04 no {@code CLAUDE.md}, o
 * classpath quebra inteiro se qualquer entrada tiver espaço no caminho, ex.:
 * a própria pasta do projeto "LM Investimentos"):
 * <pre>
 * mvn -q compile
 * java -cp "&lt;caminho-sem-espaco-para-target-classes&gt;;&lt;caminho-sem-espaco-para-sqlite-jdbc.jar&gt;;&lt;caminho-sem-espaco-para-json.jar&gt;" com.investimento.app.api.brapi.BrapiClientManualTest
 * </pre>
 */
public class BrapiClientManualTest {

    public static void main(String[] args) {
        BrapiClientImpl client = new BrapiClientImpl();

        System.out.println("=== getQuote(\"PETR4\") ===");
        AssetQuote petr4 = client.getQuote("PETR4");
        System.out.println(petr4);
        System.out.println("Requisicoes ate aqui: " + client.getRequestCount());

        System.out.println();
        System.out.println("=== getQuotes(grupo demo inteiro) ===");
        int before = client.getRequestCount();
        Map<String, AssetQuote> demoQuotes = client.getQuotes(List.of("PETR4", "VALE3", "ITUB4", "MGLU3"));
        int usedForDemo = client.getRequestCount() - before;
        demoQuotes.forEach((ticker, quote) -> System.out.println("  " + ticker + " -> " + quote));
        System.out.println("Requisicoes usadas para o grupo demo (esperado 1): " + usedForDemo);
        if (usedForDemo != 1) {
            System.out.println("FALHOU: esperava exatamente 1 requisicao para o grupo demo inteiro.");
        }

        System.out.println();
        System.out.println("=== getQuotes(fora do grupo demo, N tickers) ===");
        before = client.getRequestCount();
        Map<String, AssetQuote> mixedQuotes = client.getQuotes(List.of("PETR4", "MXRF11"));
        int usedForMixed = client.getRequestCount() - before;
        mixedQuotes.forEach((ticker, quote) -> System.out.println("  " + ticker + " -> " + quote));
        System.out.println("Requisicoes usadas para 2 tickers fora do grupo demo (esperado 2): " + usedForMixed);
        if (usedForMixed != 2) {
            System.out.println("FALHOU: esperava exatamente N=2 requisicoes (1 por ticker).");
        }

        System.out.println();
        System.out.println("=== getHistory(\"PETR4\", \"3mo\", \"1d\") ===");
        List<HistoricalPoint> history = client.getHistory("PETR4", "3mo", "1d");
        System.out.println(history.size() + " pontos, primeiro: " + history.get(0)
                + ", ultimo: " + history.get(history.size() - 1));

        System.out.println();
        System.out.println("=== searchTickers(\"petrobras\") ===");
        List<SearchResult> search = client.searchTickers("petrobras");
        search.forEach(System.out::println);

        System.out.println();
        System.out.println("=== ticker renomeado (BIDI11 -> INBR32) ===");
        AssetQuote renamed = client.getQuote("BIDI11");
        System.out.println(renamed);
        if (!renamed.changed() || !"INBR32".equalsIgnoreCase(renamed.resolvedSymbol())) {
            System.out.println("FALHOU: esperava changed=true e resolvedSymbol=INBR32, veio " + renamed);
        }

        System.out.println();
        System.out.println("=== Ticker inexistente (erro esperado) ===");
        try {
            client.getQuote("ZZZZ99");
            System.out.println("FALHOU: esperava BrapiException, nao foi lancada.");
        } catch (BrapiException e) {
            System.out.println("OK, BrapiException lancada como esperado: code=" + e.getCode()
                    + " httpStatus=" + e.getHttpStatus() + " message=" + e.getMessage());
        }
    }
}
