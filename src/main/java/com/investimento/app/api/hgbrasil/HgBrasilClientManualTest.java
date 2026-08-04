package com.investimento.app.api.hgbrasil;

import com.investimento.app.api.hgbrasil.model.IndicatorPoint;
import com.investimento.app.api.hgbrasil.model.MacroSnapshot;

import java.util.List;
import java.util.Map;

/**
 * Teste manual (não é JUnit — o projeto ainda não tem essa dependência,
 * mesma decisão tomada na ATV-02). Chama a API real da HG Brasil e imprime
 * o resultado para conferência visual contra
 * {@code .claude/skills/hgbrasil-api/references/campos.md}.
 *
 * <p>Roda com:
 * <pre>
 * mvn -q compile
 * java -cp "target/classes;&lt;caminho-sem-espaco-para-sqlite-jdbc.jar&gt;;&lt;caminho-sem-espaco-para-json.jar&gt;" com.investimento.app.api.hgbrasil.HgBrasilClientManualTest
 * </pre>
 */
public class HgBrasilClientManualTest {

    public static void main(String[] args) {
        HgBrasilClient client = new HgBrasilClientImpl();

        System.out.println("=== getSnapshot() ===");
        MacroSnapshot snapshot = client.getSnapshot();
        System.out.println("Moedas: " + snapshot.currencies().keySet());
        snapshot.currencies().forEach((iso, currency) -> System.out.println("  " + iso + " -> " + currency));
        System.out.println("Indices: " + snapshot.indices().keySet());
        snapshot.indices().forEach((name, index) -> System.out.println("  " + name + " -> " + index));
        System.out.println("Taxa do dia: " + snapshot.todayRate());

        System.out.println();
        System.out.println("=== getIndicators(\"IBGE:IPCA\", \"BCB:SELICMETA\") ===");
        Map<String, List<IndicatorPoint>> indicators = client.getIndicators("IBGE:IPCA", "BCB:SELICMETA");
        indicators.forEach((ticker, series) -> {
            System.out.println(ticker + " -> " + series.size() + " pontos, ultimo: "
                    + series.get(series.size() - 1));
        });

        System.out.println();
        System.out.println("=== getCurrentIndicator(\"BCB:SELICMETA\") ===");
        IndicatorPoint current = client.getCurrentIndicator("BCB:SELICMETA");
        System.out.println("Meta SELIC vigente: " + current);

        System.out.println();
        System.out.println("=== Chave invalida propositalmente ===");
        HgBrasilClient invalidClient = new HgBrasilClientImpl("chave-invalida-de-teste");
        try {
            invalidClient.getSnapshot();
            System.out.println("FALHOU: esperava HgBrasilException, nao foi lancada.");
        } catch (HgBrasilException e) {
            System.out.println("OK, HgBrasilException lancada como esperado: code=" + e.getCode()
                    + " message=" + e.getMessage());
        }
    }
}
