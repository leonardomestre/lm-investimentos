package com.investimento.app.service;

import com.investimento.app.api.hgbrasil.model.Currency;
import com.investimento.app.api.hgbrasil.model.MacroSnapshot;
import com.investimento.app.data.model.Asset;
import com.investimento.app.data.model.IndicatorHistory;
import com.investimento.app.data.model.OperationType;
import com.investimento.app.data.model.QuoteHistory;
import com.investimento.app.data.model.Category;
import com.investimento.app.data.model.Transaction;
import com.investimento.app.dto.AssetDTO;
import com.investimento.app.dto.FixedIncomeProjectionPoint;
import com.investimento.app.dto.PortfolioSummary;
import com.investimento.app.dto.Position;
import com.investimento.app.dto.RealizedSale;
import com.investimento.app.mapper.AssetMapper;
import com.investimento.app.repository.AssetRepository;
import com.investimento.app.repository.IndicatorHistoryRepository;
import com.investimento.app.repository.QuoteHistoryRepository;
import com.investimento.app.repository.RateHistoryRepository;
import com.investimento.app.repository.TransactionRepository;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Implementação de {@link PositionService} — agrega as {@code transactions}
 * de cada ativo (ATV-09) via custo médio ponderado e busca o preço atual
 * conforme a categoria do ativo (ATV-06): {@code quote_history} para
 * ações/FIIs/ETF/BDR/cripto, {@link MacroSnapshot} para câmbio, e uma fórmula
 * de juros compostos para renda fixa (não há preço de mercado para consultar).
 */
public class PositionServiceImpl implements PositionService {

    private final AssetRepository assetRepository;
    private final TransactionRepository transactionRepository;
    private final MarketService marketService;
    private final QuoteHistoryRepository quoteHistoryRepository;
    private final RateHistoryRepository rateHistoryRepository;
    private final IndicatorHistoryRepository indicatorHistoryRepository;

    public PositionServiceImpl(AssetRepository assetRepository,
                                TransactionRepository transactionRepository,
                                MarketService marketService,
                                QuoteHistoryRepository quoteHistoryRepository,
                                RateHistoryRepository rateHistoryRepository,
                                IndicatorHistoryRepository indicatorHistoryRepository) {
        this.assetRepository = assetRepository;
        this.transactionRepository = transactionRepository;
        this.marketService = marketService;
        this.quoteHistoryRepository = quoteHistoryRepository;
        this.rateHistoryRepository = rateHistoryRepository;
        this.indicatorHistoryRepository = indicatorHistoryRepository;
    }

    @Override
    public Position calculatePosition(long assetId) {
        return calculatePosition(findAssetOrThrow(assetId));
    }

    private Position calculatePosition(Asset asset) {
        long assetId = asset.getId();
        List<Transaction> transactions = transactionRepository.listByAsset(assetId);

        CostBasisWalk walk = walkCostBasis(asset, transactions);
        double currentQuantity = walk.currentQuantity();
        double investedValue = walk.accumulatedCost();
        double averagePrice = currentQuantity > 0 ? investedValue / currentQuantity : 0;

        double currentValue = calculateCurrentValue(asset, currentQuantity, transactions);

        double gainLossAmount = currentValue - investedValue;
        double gainLossPercent = investedValue > 0 ? (gainLossAmount / investedValue) * 100 : 0;

        return new Position(
                AssetMapper.INSTANCE.toDto(asset),
                currentQuantity,
                averagePrice,
                investedValue,
                currentValue,
                gainLossAmount,
                gainLossPercent
        );
    }

    @Override
    public List<Position> calculateAllPositions(boolean includeZeroed) {
        // Reaproveita o Asset que listAssets ja trouxe, em vez de chamar
        // calculatePosition(id) e fazer o repositorio buscar de novo, um
        // findById por ativo, o que dobrava as consultas so para reler linhas
        // que ja estavam em maos.
        return assetRepository.listAssets(false).stream()
                .map(this::calculatePosition)
                .filter(position -> includeZeroed || position.currentQuantity() > 0)
                .toList();
    }

    @Override
    public PortfolioSummary calculatePortfolioSummary() {
        return calculatePortfolioSummary(calculateAllPositions(false));
    }

    @Override
    public PortfolioSummary calculatePortfolioSummary(List<Position> positions) {
        double totalInvested = positions.stream().mapToDouble(Position::investedValue).sum();
        double totalCurrent = positions.stream().mapToDouble(Position::currentValue).sum();
        double gainLossAmount = totalCurrent - totalInvested;
        double gainLossPercent = totalInvested > 0 ? (gainLossAmount / totalInvested) * 100 : 0;
        double cdiReturnPct = calculateCdiReturnSincePeriodStart();

        return new PortfolioSummary(totalInvested, totalCurrent, gainLossAmount, gainLossPercent, cdiReturnPct);
    }

    @Override
    public List<RealizedSale> calculateRealizedSales(long assetId) {
        Asset asset = findAssetOrThrow(assetId);
        List<Transaction> transactions = transactionRepository.listByAsset(assetId);
        return walkCostBasis(asset, transactions).realizedSales();
    }

    private Asset findAssetOrThrow(long assetId) {
        return assetRepository.findById(assetId)
                .orElseThrow(() -> new IllegalArgumentException("Ativo não encontrado: " + assetId));
    }

    // ---- Renda fixa: valor líquido (IR regressivo) e projeção (ATV-15) ----

    @Override
    public double calculateFixedIncomeNetValue(long assetId) {
        Asset asset = findFixedIncomeAssetOrThrow(assetId);
        Position position = calculatePosition(assetId);
        if (asset.getInvestmentDate() == null) {
            // Sem data de aplicacao nao ha como saber o prazo decorrido -
            // devolve o bruto (mesma resiliencia da ATV-10/ATV-11: nunca
            // lanca excecao por dado ausente, so nao aplica desconto).
            return position.currentValue();
        }
        long elapsedDays = Math.max(0, ChronoUnit.DAYS.between(asset.getInvestmentDate(), LocalDate.now()));
        return netValue(position.investedValue(), position.currentValue(), elapsedDays);
    }

    @Override
    public List<FixedIncomeProjectionPoint> calculateFixedIncomeProjection(long assetId) {
        Asset asset = findFixedIncomeAssetOrThrow(assetId);
        LocalDate start = asset.getInvestmentDate();
        LocalDate end = asset.getMaturityDate();
        if (start == null || end == null || !end.isAfter(start)) {
            return List.of();
        }

        List<Transaction> transactions = transactionRepository.listByAsset(assetId);
        double initialInvestedAmount = transactions.stream()
                .filter(t -> t.getOperationType() == OperationType.BUY)
                .mapToDouble(t -> (t.getUnitPrice() * t.getQuantity()) + t.getFees())
                .sum();
        double equivalentAnnualRate = equivalentAnnualRate(asset);

        List<FixedIncomeProjectionPoint> points = new ArrayList<>();
        LocalDate cursor = start;
        while (cursor.isBefore(end)) {
            points.add(projectionPoint(cursor, start, initialInvestedAmount, equivalentAnnualRate));
            cursor = cursor.plusMonths(1);
        }
        // Garante que o ultimo ponto seja exatamente maturityDate - o loop
        // acima para antes de ultrapassar `end`, entao o ultimo `cursor` pode
        // ser ate 1 mes antes do vencimento (armadilha/criterio de aceite:
        // "gráfico de projeção termina na maturityDate, não continua além dela").
        points.add(projectionPoint(end, start, initialInvestedAmount, equivalentAnnualRate));
        return points;
    }

    private FixedIncomeProjectionPoint projectionPoint(LocalDate date, LocalDate start,
                                                         double initialInvestedAmount, double equivalentAnnualRate) {
        long elapsedDays = ChronoUnit.DAYS.between(start, date);
        double elapsedYears = elapsedDays / 365.0;
        double grossValue = initialInvestedAmount * Math.pow(1 + equivalentAnnualRate, elapsedYears);
        double netValue = netValue(initialInvestedAmount, grossValue, elapsedDays);
        return new FixedIncomeProjectionPoint(date, grossValue, netValue);
    }

    /**
     * Aplica a tabela de IR regressivo (ATV-15) sobre o rendimento ({@code
     * currentValue - investedValue}), nunca sobre o valor total. Se o
     * rendimento for negativo ou nulo, devolve o valor bruto (não há imposto
     * a aplicar) — garante o critério de aceite "valor líquido é sempre {@code
     * <=} valor bruto projetado".
     */
    private double netValue(double investedValue, double currentValue, long elapsedDays) {
        double grossGain = currentValue - investedValue;
        if (grossGain <= 0) {
            return currentValue;
        }
        double taxRate = regressiveIncomeTaxRate(elapsedDays);
        return investedValue + grossGain * (1 - taxRate);
    }

    /** Tabela de IR regressivo para renda fixa (ATV-15) — fração decimal. */
    private double regressiveIncomeTaxRate(long elapsedDays) {
        if (elapsedDays <= 180) {
            return 0.225;
        } else if (elapsedDays <= 360) {
            return 0.20;
        } else if (elapsedDays <= 720) {
            return 0.175;
        }
        return 0.15;
    }

    private Asset findFixedIncomeAssetOrThrow(long assetId) {
        Asset asset = findAssetOrThrow(assetId);
        if (asset.getCategory() != Category.FIXED_INCOME) {
            throw new IllegalArgumentException("Ativo não é de renda fixa: " + assetId);
        }
        return asset;
    }

    // ---- Custo médio ponderado (compartilhado entre calculatePosition e calculateRealizedSales) ----

    /**
     * Resultado de percorrer as {@code transactions} de um ativo em ordem
     * cronológica aplicando o algoritmo de custo médio: {@code
     * currentQuantity}/{@code accumulatedCost} finais (usados por {@link
     * #calculatePosition(long)}) e a lista de {@link RealizedSale}, uma por
     * transação {@code SELL}, com o preço médio vigente no momento de cada
     * venda (usada por {@link #calculateRealizedSales(long)}). Um único
     * método monta os dois resultados para não duplicar/divergir a lógica de
     * custo médio entre as duas operações.
     */
    private record CostBasisWalk(double currentQuantity, double accumulatedCost, List<RealizedSale> realizedSales) {
    }

    private CostBasisWalk walkCostBasis(Asset asset, List<Transaction> transactions) {
        AssetDTO assetDto = AssetMapper.INSTANCE.toDto(asset);
        double currentQuantity = 0;
        double accumulatedCost = 0;
        List<RealizedSale> realizedSales = new ArrayList<>();

        for (Transaction transaction : transactions) {
            if (transaction.getOperationType() == OperationType.BUY) {
                accumulatedCost += (transaction.getUnitPrice() * transaction.getQuantity()) + transaction.getFees();
                currentQuantity += transaction.getQuantity();
            } else { // SELL
                double currentAveragePrice = currentQuantity > 0 ? accumulatedCost / currentQuantity : 0;
                double realizedGainAmount =
                        (transaction.getUnitPrice() - currentAveragePrice) * transaction.getQuantity() - transaction.getFees();
                realizedSales.add(new RealizedSale(
                        transaction.getId(),
                        assetDto,
                        transaction.getDate(),
                        transaction.getQuantity(),
                        transaction.getUnitPrice(),
                        currentAveragePrice,
                        transaction.getFees(),
                        realizedGainAmount
                ));
                // Reduz accumulatedCost/currentQuantity proporcionalmente -
                // o preço médio NÃO muda numa venda (RF06/ATV-10).
                //
                // A baixa e limitada ao que existe em carteira. TransactionService
                // nao valida saldo ao cadastrar uma venda (decisao registrada la),
                // entao uma venda maior que a posicao - digitacao errada, ou
                // compra antiga que o usuario ainda nao cadastrou - deixava
                // quantidade e custo NEGATIVOS. Isso nao ficava contido no ativo:
                // investedValue negativo entra na soma de calculatePortfolioSummary
                // e reduz o total investido da carteira inteira, e o
                // gainLossPercent do ativo passa a ser calculado sobre uma base
                // negativa. A venda em si continua registrada integralmente na
                // lista de RealizedSale (o relatorio de IR usa a quantidade que o
                // usuario informou); o que se limita aqui e so a baixa de estoque.
                double soldFromPosition = Math.min(transaction.getQuantity(), Math.max(currentQuantity, 0));
                accumulatedCost = Math.max(accumulatedCost - currentAveragePrice * soldFromPosition, 0);
                currentQuantity = Math.max(currentQuantity - soldFromPosition, 0);
            }
        }

        return new CostBasisWalk(currentQuantity, accumulatedCost, realizedSales);
    }

    // ---- Valor atual, por categoria ----

    private double calculateCurrentValue(Asset asset, double currentQuantity, List<Transaction> transactions) {
        return switch (asset.getCategory()) {
            case STOCKS, FIIS, CRYPTO -> {
                double value = latestQuotePrice(asset.getId()) * currentQuantity;
                yield convertToBrlIfNeeded(value, asset.getCurrency());
            }
            case FOREX -> {
                Double buyRate = fxBuyRate(asset.getCurrency());
                // Sem taxa disponivel (rede fora do ar e sem cache anterior) -
                // devolve 0 em vez de propagar excecao (resiliencia, mesmo
                // padrao da ATV-06).
                yield buyRate != null ? currentQuantity * buyRate : 0;
            }
            case FIXED_INCOME -> calculateFixedIncomeCurrentValue(asset, transactions);
        };
    }

    /**
     * Último preço de {@code quote_history} para o ativo (0 se ainda não há
     * nenhuma linha).
     *
     * <p>Usa {@code findLatestByAsset} (1 linha) e não {@code listByAsset}: o
     * seed inicial grava a série completa da brapi ({@code range=max}, ~6,5 mil
     * linhas por ativo antigo), e este método é chamado uma vez por ativo a
     * cada refresh de tela — carregar o histórico inteiro só para ler a última
     * linha travava a UI proporcionalmente ao tamanho da carteira.</p>
     */
    private double latestQuotePrice(long assetId) {
        return quoteHistoryRepository.findLatestByAsset(assetId)
                .map(QuoteHistory::getPrice)
                .orElse(0.0);
    }

    /**
     * Ativo em moeda estrangeira (ex.: cripto/BDR cotado em USD) precisa ter
     * o {@code currentValue} convertido para BRL antes de entrar na soma do
     * {@link PortfolioSummary} — armadilha documentada na ATV-10. Não se
     * aplica a {@code FOREX} (já convertido dentro do próprio case, ver
     * {@link #calculateCurrentValue}) nem a {@code FIXED_INCOME} (fórmula já
     * devolve BRL, não consulta preço de mercado nenhum).
     */
    private double convertToBrlIfNeeded(double value, String currency) {
        if (currency == null || "BRL".equalsIgnoreCase(currency)) {
            return value;
        }
        Double buyRate = fxBuyRate(currency);
        // Sem taxa disponivel - mantem o valor bruto (resiliencia) em vez de
        // lancar excecao/zerar a posicao inteira.
        return buyRate != null ? value * buyRate : value;
    }

    /**
     * Taxa de compra da moeda, <b>sempre a partir do cache</b> do painel
     * macro.
     *
     * <p>Usa {@code getCachedMacroSnapshot()} e não {@code getMacroSnapshot()}
     * de propósito: cálculo de posição é chamado em série, um ativo por vez,
     * direto da FX thread durante o {@code refresh()} de quatro telas. Com a
     * versão que busca, bastava o TTL de 5 min vencer para a montagem da tela
     * disparar uma requisição HTTP síncrona e travar a janela.</p>
     *
     * <p>Quem atualiza o cache é o {@code Task} de background das telas. Numa
     * primeira abertura sem cache ainda, a conversão cai no mesmo caminho de
     * resiliência que já existia para "API fora do ar" (valor sem converter /
     * posição de câmbio em 0), e a tela se corrige no redesenho seguinte.</p>
     */
    private Double fxBuyRate(String isoCurrency) {
        if (isoCurrency == null || isoCurrency.isBlank()) {
            return null;
        }
        MacroSnapshot snapshot = marketService.getCachedMacroSnapshot();
        if (snapshot == null) {
            return null;
        }
        Currency currency = snapshot.currencies().get(isoCurrency.toUpperCase());
        return currency == null ? null : currency.buy();
    }

    /**
     * Aproximação de valor projetado por juros compostos (não é cálculo de
     * precisão bancária ao centavo, dia útil por dia útil — suficiente para
     * acompanhamento pessoal, conforme a atividade).
     */
    private double calculateFixedIncomeCurrentValue(Asset asset, List<Transaction> transactions) {
        double initialInvestedAmount = transactions.stream()
                .filter(t -> t.getOperationType() == OperationType.BUY)
                .mapToDouble(t -> (t.getUnitPrice() * t.getQuantity()) + t.getFees())
                .sum();

        if (asset.getInvestmentDate() == null) {
            return initialInvestedAmount;
        }

        long elapsedDays = ChronoUnit.DAYS.between(asset.getInvestmentDate(), LocalDate.now());
        double elapsedYears = Math.max(elapsedDays, 0) / 365.0;
        double equivalentAnnualRate = equivalentAnnualRate(asset);

        return initialInvestedAmount * Math.pow(1 + equivalentAnnualRate, elapsedYears);
    }

    private double equivalentAnnualRate(Asset asset) {
        if (asset.getBenchmark() == null || asset.getContractedRatePct() == null) {
            return 0;
        }
        double contractedRate = asset.getContractedRatePct() / 100.0;
        return switch (asset.getBenchmark()) {
            case FIXED_RATE -> contractedRate;
            case CDI -> mostRecentRatePct(true) * contractedRate;
            case SELIC -> mostRecentRatePct(false) * contractedRate;
            case IPCA -> ipca12mAccumulated() + contractedRate; // IPCA + spread
        };
    }

    /**
     * CDI/SELIC do dia mais recente gravado em {@code rate_history}, como
     * fração decimal (ex.: 0.1425 para 14,25%). Se a tabela ainda estiver
     * vazia (app recém-instalado, sem internet na primeira execução), trata
     * como 0% em vez de lançar exceção — mesma resiliência exigida
     * explicitamente para IPCA na ATV-10.
     */
    private double mostRecentRatePct(boolean cdi) {
        return rateHistoryRepository.findMostRecent()
                .map(rate -> (cdi ? rate.getCdi() : rate.getSelic()) / 100.0)
                .orElse(0.0);
    }

    /**
     * IPCA acumulado dos últimos 12 meses (fração decimal) a partir de
     * {@code indicator_history} (ticker {@code IBGE:IPCA}, populado pela
     * ATV-06). Se a tabela estiver vazia, devolve 0 em vez de lançar exceção
     * (armadilha documentada na ATV-10).
     */
    private double ipca12mAccumulated() {
        List<IndicatorHistory> history = indicatorHistoryRepository.listByTicker("IBGE:IPCA");
        if (history.isEmpty()) {
            return 0;
        }
        // listByTicker ja devolve ordenado por period ASC (IndicatorHistoryRepositoryImpl).
        List<IndicatorHistory> last12Months = history.size() > 12
                ? history.subList(history.size() - 12, history.size())
                : history;
        double accumulatedFactor = 1.0;
        for (IndicatorHistory point : last12Months) {
            accumulatedFactor *= (1 + point.getValue() / 100.0);
        }
        return accumulatedFactor - 1;
    }

    // ---- Comparação com CDI do período (PortfolioSummary) ----

    /**
     * CDI acumulado (juros compostos, mesma aproximação da renda fixa) desde
     * a menor {@code investmentDate}/{@code date} de transação entre todos os
     * ativos — "aproximação razoável de período" (texto da própria ATV-10).
     */
    private double calculateCdiReturnSincePeriodStart() {
        List<Transaction> allTransactions = transactionRepository.listAll();
        List<Asset> assets = assetRepository.listAssets(false);

        Optional<LocalDate> earliestDate = Stream.concat(
                allTransactions.stream().map(Transaction::getDate),
                assets.stream().map(Asset::getInvestmentDate).filter(Objects::nonNull)
        ).min(LocalDate::compareTo);

        if (earliestDate.isEmpty()) {
            return 0;
        }

        long elapsedDays = ChronoUnit.DAYS.between(earliestDate.get(), LocalDate.now());
        double elapsedYears = Math.max(elapsedDays, 0) / 365.0;
        double annualCdi = mostRecentRatePct(true);

        return (Math.pow(1 + annualCdi, elapsedYears) - 1) * 100;
    }
}
