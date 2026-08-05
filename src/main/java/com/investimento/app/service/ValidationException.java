package com.investimento.app.service;

/**
 * Erro de validação de regra de negócio em qualquer {@code *Service} —
 * mensagem já pronta para exibir ao usuário (a UI, ex.: ATV-13, não deveria
 * precisar montar a mensagem sozinha). Compartilhada entre serviços (ex.:
 * {@code AssetService}, ATV-08; {@code TransactionService}, ATV-09) — não
 * recrie uma exceção equivalente por serviço.
 *
 * <p>RuntimeException (não checked) — mesma decisão de {@code
 * HgBrasilException}/{@code BrapiException}/{@code CoinGeckoException}
 * (ATV-03/04/05): não poluir a assinatura de método em toda a cadeia de
 * chamada. Ainda assim é declarada explicitamente em {@code throws} nas
 * interfaces de serviço, como documentação de que o método pode falhar por
 * validação (mesmo padrão já usado para as exceções de cliente de API).
 */
public class ValidationException extends RuntimeException {

    public ValidationException(String message) {
        super(message);
    }

    public ValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
