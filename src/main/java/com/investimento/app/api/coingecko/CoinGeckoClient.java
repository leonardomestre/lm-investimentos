package com.investimento.app.api.coingecko;

import com.investimento.app.api.coingecko.model.CryptoPrice;
import com.investimento.app.api.coingecko.model.HistoricalPoint;

import java.util.List;
import java.util.Map;

/**
 * Cliente stateless para {@code api.coingecko.com} — cotação e histórico de
 * criptomoedas. Usado para <strong>toda</strong> cripto da carteira,
 * incluindo Bitcoin (decisão do planejamento: não usar o BTC da HG Brasil,
 * para não ter duas fontes de preço diferentes pro mesmo ativo). Não sabe
 * nada de cache — essa decisão é da ATV-06 ({@code MarketService}).
 */
public interface CoinGeckoClient {

    /**
     * {@code GET /simple/price?ids=...&vs_currencies=brl} — preço atual de
     * 1+ criptomoedas, sempre agrupadas numa única requisição (o oposto da
     * brapi.dev). Lança {@link CoinGeckoException} se algum símbolo não
     * estiver no mapa fixo suportado, ou se algum id pedido não vier
     * presente na resposta (a API não sinaliza erro nenhum para id
     * inválido — HTTP 200 com corpo vazio).
     */
    Map<String, CryptoPrice> getPrices(List<String> symbols) throws CoinGeckoException;

    /**
     * {@code GET /coins/{id}/market_chart?vs_currency=brl&days=} — histórico
     * de preço. Teto de 365 dias no plano gratuito — {@code days > 365}
     * lança {@link CoinGeckoException} imediatamente, antes de tocar a rede.
     */
    List<HistoricalPoint> getHistory(String symbol, int days) throws CoinGeckoException;
}
