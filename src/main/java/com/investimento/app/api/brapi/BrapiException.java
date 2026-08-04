package com.investimento.app.api.brapi;

/**
 * Erro reportado pela {@code brapi.dev}. Diferente da HG Brasil, a brapi usa
 * status HTTP corretos na maioria dos casos ({@code 401}/{@code 400}), mas
 * {@code NOT_FOUND}/{@code FEATURE_NOT_AVAILABLE} vêm com HTTP 200 — esta
 * exceção carrega tanto o {@code code} do corpo JSON quanto o
 * {@code httpStatus} observado, para não virar um erro genérico de parsing.
 *
 * <p>RuntimeException (não checked) — mesma decisão da ATV-03
 * ({@code HgBrasilException}), para não poluir a assinatura de método em
 * toda a cadeia de chamada.
 */
public class BrapiException extends RuntimeException {

    private final String code;
    private final Integer httpStatus;

    public BrapiException(String code, Integer httpStatus, String message) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public BrapiException(String message, Throwable cause) {
        super(message, cause);
        this.code = null;
        this.httpStatus = null;
    }

    public String getCode() {
        return code;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }
}
