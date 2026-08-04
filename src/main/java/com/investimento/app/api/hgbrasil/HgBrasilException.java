package com.investimento.app.api.hgbrasil;

/**
 * Erro reportado pela API da HG Brasil. A API responde HTTP 200 mesmo em
 * caso de falha — esta exceção carrega o {@code code}/{@code message} que a
 * própria resposta trouxe (v1: {@code results.error}/{@code results.message};
 * v2: {@code errors[].code}/{@code errors[].message}), para não virar um
 * erro genérico de parsing.
 *
 * <p>RuntimeException (não checked) para não poluir a assinatura de método
 * em toda a cadeia de chamada — decisão já registrada na ATV-03.
 */
public class HgBrasilException extends RuntimeException {

    private final String code;

    public HgBrasilException(String code, String message) {
        super(message);
        this.code = code;
    }

    public HgBrasilException(String message, Throwable cause) {
        super(message, cause);
        this.code = null;
    }

    public String getCode() {
        return code;
    }
}
