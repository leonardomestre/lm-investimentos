package com.investimento.app.service;

import com.investimento.app.repository.SettingRepository;

/**
 * Resolve a chave/token de uma API externa seguindo a prioridade definida na
 * ATV-18: {@code settings} (banco, configurado pelo usuário na tela de
 * Configurações) &gt; variável de ambiente &gt; fallback embutido no projeto
 * (mesma ordem já documentada nas skills de API, só que agora a configuração
 * do usuário vem primeiro).
 *
 * <p>Usado pelo composition root ({@code App.buildShell}) para montar
 * {@code HgBrasilClient}/{@code BrapiClient} — nenhuma tela chama isto
 * diretamente.</p>
 */
public final class ApiKeyResolver {

    private ApiKeyResolver() {
    }

    /**
     * @param settingRepository repositório de {@code settings} (ATV-02)
     * @param settingKey        chave em {@code settings} (ex.: {@code "hgbrasil.apiKey"})
     * @param envVarName        nome da variável de ambiente (ex.: {@code "HG_BRASIL_KEY"})
     * @param fallback          valor embutido no projeto, usado só se nenhuma das 2 fontes acima tiver valor
     */
    public static String resolve(SettingRepository settingRepository, String settingKey, String envVarName, String fallback) {
        String fromSettings = settingRepository.get(settingKey, null);
        if (fromSettings != null && !fromSettings.isBlank()) {
            return fromSettings;
        }
        String fromEnv = System.getenv(envVarName);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        return fallback;
    }
}
