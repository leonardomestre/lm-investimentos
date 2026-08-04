package com.investimento.app.api.coingecko;

/**
 * Erro do cliente CoinGecko. Diferente da HG Brasil e da brapi.dev, a
 * CoinGecko <strong>não sinaliza erro nenhum</strong> em
 * {@code GET /simple/price} — id inválido devolve HTTP 200 com corpo
 * {@code {}}. Por isso esta exceção não carrega {@code code}/{@code
 * httpStatus} de resposta: ela é lançada pelo próprio cliente ao detectar
 * uma condição de erro que a API não sinaliza (id ausente na resposta,
 * símbolo fora do {@code ID_MAP}), ou ao repassar a mensagem de erro
 * específica de {@code market_chart} ({@code error.status.error_message}).
 *
 * <p>RuntimeException (não checked) — mesma decisão das ATV-03/04
 * ({@code HgBrasilException}/{@code BrapiException}), para não poluir a
 * assinatura de método em toda a cadeia de chamada.
 */
public class CoinGeckoException extends RuntimeException {

    public CoinGeckoException(String message) {
        super(message);
    }

    public CoinGeckoException(String message, Throwable cause) {
        super(message, cause);
    }
}
