package com.investimento.app.service;

import com.investimento.app.repository.SettingRepository;

import java.util.HashMap;
import java.util.Map;

/**
 * Teste manual (main(), sem JUnit — mesma decisão das ATV-01 a 19) de
 * {@link ApiKeyResolver} (ATV-18). Não toca banco/rede — usa um
 * {@link SettingRepository} fake (mapa em memória) para controlar
 * exatamente o que está/não está em {@code settings}.
 *
 * <p>Cobre os 3 níveis de prioridade exigidos pelo critério de aceite da
 * ATV-18 ("trocar a chave da HG Brasil na tela... usa a nova chave, não a do
 * ambiente/fallback"): (1) settings vazio + env ausente → fallback; (2)
 * settings preenchido → settings vence, mesmo com env/fallback presentes;
 * (3) settings vazio + env presente → env vence sobre o fallback (usa a
 * variável de ambiente real {@code PATH}, presente em qualquer ambiente
 * Windows/Git Bash, para não depender de conseguir setar uma env var
 * arbitrária a partir do próprio processo Java).</p>
 */
public final class ApiKeyResolverManualTest {

    private ApiKeyResolverManualTest() {
    }

    public static void main(String[] args) {
        // Cenario 1: settings vazio, env inexistente -> fallback.
        FakeSettingRepository empty = new FakeSettingRepository();
        String result1 = ApiKeyResolver.resolve(empty, "hgbrasil.apiKey",
                "ATV18_TEST_ENV_VAR_QUE_NAO_EXISTE", "FALLBACK_PROJETO");
        check("FALLBACK_PROJETO".equals(result1),
                "settings vazio + env ausente deveria usar o fallback do projeto, veio: " + result1);

        // Cenario 2: settings preenchido -> settings vence, mesmo com fallback/env presentes.
        FakeSettingRepository withSetting = new FakeSettingRepository();
        withSetting.save("hgbrasil.apiKey", "CHAVE_DO_USUARIO_NA_TELA");
        String result2 = ApiKeyResolver.resolve(withSetting, "hgbrasil.apiKey", "PATH", "FALLBACK_PROJETO");
        check("CHAVE_DO_USUARIO_NA_TELA".equals(result2),
                "settings preenchido deveria vencer sobre env/fallback, veio: " + result2);

        // Cenario 2b: settings com valor em branco conta como "nao configurado" (mesma regra do
        // SettingsView.saveSettings, que sempre grava mesmo campo vazio) -> cai para o proximo nivel.
        FakeSettingRepository blankSetting = new FakeSettingRepository();
        blankSetting.save("hgbrasil.apiKey", "   ");
        String result2b = ApiKeyResolver.resolve(blankSetting, "hgbrasil.apiKey",
                "ATV18_TEST_ENV_VAR_QUE_NAO_EXISTE", "FALLBACK_PROJETO");
        check("FALLBACK_PROJETO".equals(result2b),
                "settings em branco deveria ser tratado como ausente, veio: " + result2b);

        // Cenario 3: settings vazio, env presente (usa PATH, garantidamente setado) -> env vence sobre fallback.
        String realPath = System.getenv("PATH");
        check(realPath != null && !realPath.isBlank(), "pre-condicao do cenario 3: variavel de ambiente PATH deveria existir neste ambiente");
        FakeSettingRepository emptyForEnvTest = new FakeSettingRepository();
        String result3 = ApiKeyResolver.resolve(emptyForEnvTest, "hgbrasil.apiKey", "PATH", "FALLBACK_PROJETO");
        check(realPath.equals(result3), "settings vazio + env presente deveria usar a variavel de ambiente, nao o fallback");
        check(!"FALLBACK_PROJETO".equals(result3), "settings vazio + env presente NAO deveria cair no fallback");

        System.out.println();
        System.out.println("ApiKeyResolverManualTest: TODOS OS CENARIOS PASSARAM.");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("FALHOU: " + message);
        }
        System.out.println("OK: " + message);
    }

    /** Fake em memória de {@link SettingRepository}, sem tocar banco. */
    private static final class FakeSettingRepository implements SettingRepository {
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
}
