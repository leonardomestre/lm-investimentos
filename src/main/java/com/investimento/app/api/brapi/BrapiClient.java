package com.investimento.app.api.brapi;

import com.investimento.app.api.brapi.model.AssetQuote;
import com.investimento.app.api.brapi.model.HistoricalPoint;
import com.investimento.app.api.brapi.model.SearchResult;

import java.util.List;
import java.util.Map;

/**
 * Cliente stateless para {@code brapi.dev} — cotação, histórico e
 * busca/autocomplete de ações, FIIs, ETFs e BDRs da B3. Não sabe nada de
 * cache/SQLite — essa decisão é da ATV-06 ({@code MarketService}).
 */
public interface BrapiClient {

    /**
     * {@code GET /v2/stocks/quote?symbols=} — cotação de 1 ticker, 1
     * requisição.
     */
    AssetQuote getQuote(String ticker) throws BrapiException;

    /**
     * Cotação de vários tickers, respeitando o limite real do plano
     * gratuito (ver {@code brapi-api/SKILL.md} seção 3): se {@code tickers}
     * for um subconjunto do grupo demo ({@code PETR4}, {@code VALE3},
     * {@code ITUB4}, {@code MGLU3}), monta 1 única requisição; caso
     * contrário, 1 requisição por ticker, sequencial.
     */
    Map<String, AssetQuote> getQuotes(List<String> tickers) throws BrapiException;

    /**
     * {@code GET /quote/{ticker}?range=&interval=} — histórico de preço,
     * gratuito inclusive com {@code range=max}.
     */
    List<HistoricalPoint> getHistory(String ticker, String range, String interval) throws BrapiException;

    /**
     * {@code GET /v2/tickers?search=} — busca/autocomplete de ticker (RF01,
     * tela 5.2), já traz o preço atual.
     */
    List<SearchResult> searchTickers(String query) throws BrapiException;
}
