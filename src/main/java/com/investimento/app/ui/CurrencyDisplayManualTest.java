package com.investimento.app.ui;

import com.investimento.app.api.hgbrasil.model.Currency;
import com.investimento.app.api.hgbrasil.model.DailyRate;
import com.investimento.app.api.hgbrasil.model.IndicatorPoint;
import com.investimento.app.api.hgbrasil.model.MacroSnapshot;
import com.investimento.app.data.model.Asset;
import com.investimento.app.dto.SyncEvent;
import com.investimento.app.repository.SettingRepository;
import com.investimento.app.service.MarketService;
import com.investimento.app.ui.screens.SettingsView;
import javafx.concurrent.Task;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Teste manual headless da moeda principal (pendencia 2 de
 * {@code documentacao/pendencias.md}).
 *
 * <p>Nao toca rede, banco nem JavaFX: {@link CurrencyDisplay} depende so de um
 * {@link SettingRepository} e de {@code MarketService.getMacroSnapshot()}, e os
 * dois sao substituidos por fakes em memoria aqui. Isso permite exercitar
 * exatamente os casos que a API real nunca produziria sob demanda — snapshot
 * {@code null}, ISO ausente, taxa zero.</p>
 *
 * <p>Rodar:
 * {@code java -cp "C:/tmp/...;..." com.investimento.app.ui.CurrencyDisplayManualTest}</p>
 */
public final class CurrencyDisplayManualTest {

    private static int failures = 0;

    public static void main(String[] args) {
        scenario1DefaultIsBrl();
        scenario2ConversionFromSettings();
        scenario3BrlNeverTouchesTheNetwork();
        scenario4RateUnavailableFallsBackToBrl();
        scenario5LabelRoundTrip();

        if (failures > 0) {
            System.out.println("\nFALHAS: " + failures);
            System.exit(1);
        }
        System.out.println("\nTodos os cenarios passaram.");
    }

    // =====================================================================
    // Cenario 1 — default
    // =====================================================================

    /**
     * O default importa mais do que parece: os testes headless das ATV-13 a 18
     * constroem {@code *View} diretamente, sem passar por
     * {@link CurrencyDisplay#configure}. Se o default nao fosse BRL/1.0, todos
     * eles passariam a comparar valores convertidos.
     */
    private static void scenario1DefaultIsBrl() {
        System.out.println("== Cenario 1: default e BRL, sem conversao ==");
        CurrencyDisplay.resetForTest();

        check(CurrencyDisplay.isBrl(), "moeda padrao e o real");
        check("R$".equals(CurrencyDisplay.symbol()), "simbolo padrao e R$");
        check("BRL".equals(CurrencyDisplay.code()), "codigo padrao e BRL");
        check(CurrencyDisplay.rate() == 1.0, "taxa padrao e 1.0");
        check(CurrencyDisplay.convert(15757.58) == 15757.58, "converter em BRL e identidade");
        check(!CurrencyDisplay.isRateUnavailable(), "sem alerta de taxa indisponivel no padrao");
    }

    // =====================================================================
    // Cenario 2 — conversao de verdade
    // =====================================================================

    private static void scenario2ConversionFromSettings() {
        System.out.println("\n== Cenario 2: moeda vinda de settings converte os valores ==");
        CurrencyDisplay.resetForTest();

        FakeSettings settings = new FakeSettings();
        settings.save(SettingsView.SETTING_PRIMARY_CURRENCY, "Dólar norte-americano (USD)");
        FakeMarketService market = new FakeMarketService(snapshotWith("USD", 5.35, "EUR", 5.80));

        CurrencyDisplay.configure(settings, market);

        check(!CurrencyDisplay.isBrl(), "moeda em vigor deixou de ser o real");
        check("US$".equals(CurrencyDisplay.symbol()), "simbolo virou US$");
        check("USD".equals(CurrencyDisplay.code()), "codigo virou USD");
        check(Math.abs(CurrencyDisplay.rate() - 5.35) < 1e-9, "taxa veio do MacroSnapshot (5,35)");

        // R$ 10.700,00 / 5,35 = US$ 2.000,00 — a conta que aparece na tela.
        double converted = CurrencyDisplay.convert(10700.00);
        check(Math.abs(converted - 2000.00) < 1e-9,
                "R$ 10.700,00 vira US$ 2.000,00 (" + converted + ")");
        check(Math.abs(CurrencyDisplay.convert(-5350.00) + 1000.00) < 1e-9,
                "valor negativo (prejuizo) converte com o sinal preservado");
        check(CurrencyDisplay.convert(0) == 0, "zero continua zero");

        // Trocar para EUR sem reiniciar nada — e o que Shell.select faz a cada
        // navegacao depois de o usuario salvar em Configuracoes.
        settings.save(SettingsView.SETTING_PRIMARY_CURRENCY, "Euro (EUR)");
        CurrencyDisplay.configure(settings, market);
        check("€".equals(CurrencyDisplay.symbol()), "trocar para EUR sem reiniciar muda o simbolo");
        check(Math.abs(CurrencyDisplay.rate() - 5.80) < 1e-9, "e muda a taxa junto (5,80)");

        // E voltar para o real zera a conversao.
        settings.save(SettingsView.SETTING_PRIMARY_CURRENCY, "Real (BRL)");
        CurrencyDisplay.configure(settings, market);
        check(CurrencyDisplay.isBrl() && CurrencyDisplay.convert(123.45) == 123.45,
                "voltar para o real remove a conversao");
    }

    // =====================================================================
    // Cenario 3 — custo zero no caminho padrao
    // =====================================================================

    /**
     * {@code configure} roda a cada navegacao entre telas ({@code Shell.select}).
     * Se ele consultasse o {@code MarketService} mesmo com a moeda em BRL,
     * telas que hoje nao tocam rede nenhuma (Cadastro, Historico e IR)
     * passariam a arriscar uma chamada HTTP sincrona na FX thread a cada
     * clique na sidebar.
     */
    private static void scenario3BrlNeverTouchesTheNetwork() {
        System.out.println("\n== Cenario 3: com BRL, configure nao consulta o MarketService ==");
        CurrencyDisplay.resetForTest();

        FakeSettings settings = new FakeSettings();
        FakeMarketService market = new FakeMarketService(snapshotWith("USD", 5.35));

        // settings vazio => "Real (BRL)" por fromLabel(null)
        CurrencyDisplay.configure(settings, market);
        check(market.snapshotCalls == 0, "settings sem moeda salva: 0 consultas ao MarketService");

        settings.save(SettingsView.SETTING_PRIMARY_CURRENCY, "Real (BRL)");
        CurrencyDisplay.configure(settings, market);
        CurrencyDisplay.configure(settings, market);
        check(market.snapshotCalls == 0, "moeda salva como BRL: ainda 0 consultas");

        settings.save(SettingsView.SETTING_PRIMARY_CURRENCY, "Dólar norte-americano (USD)");
        CurrencyDisplay.configure(settings, market);
        check(market.snapshotCalls == 1, "so com moeda estrangeira ele consulta (1 chamada)");
    }

    // =====================================================================
    // Cenario 4 — taxa indisponivel
    // =====================================================================

    /**
     * Os 3 jeitos de a taxa faltar. Em todos, exibir o valor em BRL e correto;
     * o que nao pode acontecer e o app mostrar "US$ 15.757,58" com o numero em
     * reais — o usuario leria um patrimonio 5x maior do que tem.
     */
    private static void scenario4RateUnavailableFallsBackToBrl() {
        System.out.println("\n== Cenario 4: sem taxa de cambio, cai para BRL sem mentir ==");

        FakeSettings settings = new FakeSettings();
        settings.save(SettingsView.SETTING_PRIMARY_CURRENCY, "Dólar norte-americano (USD)");

        CurrencyDisplay.resetForTest();
        CurrencyDisplay.configure(settings, new FakeMarketService(null));
        check(CurrencyDisplay.isBrl() && CurrencyDisplay.isRateUnavailable(),
                "snapshot null (API fora do ar) -> exibe em BRL e sinaliza");
        check(CurrencyDisplay.convert(15757.58) == 15757.58, "e nao converte nada");

        CurrencyDisplay.resetForTest();
        CurrencyDisplay.configure(settings, new FakeMarketService(snapshotWith("EUR", 5.80)));
        check(CurrencyDisplay.isBrl() && CurrencyDisplay.isRateUnavailable(),
                "ISO ausente do snapshot -> exibe em BRL e sinaliza");

        CurrencyDisplay.resetForTest();
        CurrencyDisplay.configure(settings, new FakeMarketService(snapshotWith("USD", 0.0)));
        check(CurrencyDisplay.isBrl() && CurrencyDisplay.isRateUnavailable(),
                "taxa zero -> exibe em BRL e sinaliza (nunca divide por zero)");

        // E o sinal tem que sumir quando a cotacao volta.
        CurrencyDisplay.configure(settings, new FakeMarketService(snapshotWith("USD", 5.35)));
        check(!CurrencyDisplay.isRateUnavailable() && !CurrencyDisplay.isBrl(),
                "cotacao volta -> a moeda escolhida entra em vigor sozinha");

        CurrencyDisplay.resetForTest();
        CurrencyDisplay.configure(settings, null);
        check(CurrencyDisplay.isBrl(), "MarketService null (testes headless de tela) -> BRL, sem NPE");
    }

    // =====================================================================
    // Cenario 5 — rotulo persistido
    // =====================================================================

    private static void scenario5LabelRoundTrip() {
        System.out.println("\n== Cenario 5: rotulo persistido em settings ==");

        // Compatibilidade com o banco existente: a ATV-18 ja gravava
        // exatamente este texto como unica opcao do dropdown.
        check(CurrencyDisplay.Primary.fromLabel("Real (BRL)") == CurrencyDisplay.Primary.BRL,
                "\"Real (BRL)\" (valor ja gravado pela ATV-18) continua resolvendo para BRL");
        check(CurrencyDisplay.Primary.fromLabel(null) == CurrencyDisplay.Primary.BRL, "null -> BRL");
        check(CurrencyDisplay.Primary.fromLabel("Dólar") == CurrencyDisplay.Primary.BRL,
                "rotulo desconhecido -> BRL (nunca lanca)");

        for (CurrencyDisplay.Primary primary : CurrencyDisplay.Primary.values()) {
            check(CurrencyDisplay.Primary.fromLabel(primary.label()) == primary,
                    "round-trip do rotulo de " + primary.iso());
        }

        List<String> labels = CurrencyDisplay.labels();
        check(labels.size() == CurrencyDisplay.Primary.values().length,
                "dropdown lista todas as moedas (" + labels.size() + ")");
        check("Real (BRL)".equals(labels.get(0)), "o real e a primeira opcao do dropdown");
    }

    // =====================================================================
    // Fakes
    // =====================================================================

    private static MacroSnapshot snapshotWith(Object... isoAndRate) {
        Map<String, Currency> currencies = new HashMap<>();
        for (int i = 0; i < isoAndRate.length; i += 2) {
            String iso = (String) isoAndRate[i];
            double buy = (Double) isoAndRate[i + 1];
            currencies.put(iso, new Currency(iso, iso, buy, null, 0.0));
        }
        return new MacroSnapshot(currencies, Map.of(),
                new DailyRate(LocalDate.now(), 14.25, 14.25, 0.0, 0.0, 0.0));
    }

    /** {@link SettingRepository} em memoria — sem SQLite, sem arquivo. */
    private static final class FakeSettings implements SettingRepository {
        private final Map<String, String> values = new HashMap<>();

        @Override
        public String get(String key, String defaultValue) {
            return values.getOrDefault(key, defaultValue);
        }

        @Override
        public void save(String key, String value) {
            values.put(key, value);
        }
    }

    /**
     * {@link MarketService} minimo: devolve um snapshot fixo e conta quantas
     * vezes foi consultado (e o contador que prova o cenario 3). Os demais
     * metodos nunca sao chamados por {@link CurrencyDisplay}.
     */
    private static final class FakeMarketService implements MarketService {
        private final MacroSnapshot snapshot;
        private int snapshotCalls;

        private FakeMarketService(MacroSnapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Override
        public MacroSnapshot getMacroSnapshot() {
            snapshotCalls++;
            return snapshot;
        }

        @Override
        public Map<String, List<IndicatorPoint>> getIndicators() {
            return Map.of();
        }

        @Override
        public Task<Void> updateQuotes(List<Asset> assets) {
            throw new UnsupportedOperationException("nao usado por CurrencyDisplay");
        }

        @Override
        public Task<Void> seedInitialHistory(Asset asset) {
            throw new UnsupportedOperationException("nao usado por CurrencyDisplay");
        }

        @Override
        public Map<Long, Double> getDailyChanges(List<Asset> assets) {
            return Map.of();
        }

        @Override
        public Optional<String> getLastFailure() {
            return Optional.empty();
        }

        @Override
        public List<SyncEvent> getRecentSyncs() {
            return List.of();
        }
    }

    private static void check(boolean condition, String description) {
        if (condition) {
            System.out.println("  [ok] " + description);
        } else {
            System.out.println("  [FALHOU] " + description);
            failures++;
        }
    }

    private CurrencyDisplayManualTest() {
    }
}
