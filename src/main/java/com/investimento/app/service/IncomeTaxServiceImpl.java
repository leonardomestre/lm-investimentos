package com.investimento.app.service;

import com.investimento.app.data.model.Asset;
import com.investimento.app.data.model.Category;
import com.investimento.app.dto.AnnualIncomeTaxSummary;
import com.investimento.app.dto.MonthlyTaxAssessment;
import com.investimento.app.dto.RealizedSale;
import com.investimento.app.repository.AssetRepository;

import java.time.YearMonth;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Implementação de {@link IncomeTaxService} — a regra de isenção por
 * categoria é um GATILHO BINÁRIO por mês (não uma faixa progressiva de
 * dedução): ultrapassou o teto de venda do mês, todo o {@code grossGain}
 * daquele mês vira tributável; não ultrapassou, o mês inteiro fica
 * isento (Instrução Normativa RFB nº 1.585/2015, art. 56 — ver ATV-11).
 *
 * <p>A alíquota é 15% em ações e cripto e 20% em FII (art. 62 da mesma
 * instrução normativa).</p>
 */
public class IncomeTaxServiceImpl implements IncomeTaxService {

    private static final double STOCKS_MONTHLY_EXEMPTION_THRESHOLD = 20_000;
    private static final double CRYPTO_MONTHLY_EXEMPTION_THRESHOLD = 35_000;
    private static final double CAPITAL_GAIN_TAX_RATE = 0.15;

    /**
     * Ganho de capital na alienação de cota de FII é tributado a <b>20%</b>,
     * não aos 15% das demais categorias (IN RFB nº 1.585/2015, art. 62).
     *
     * <p>O planejamento e a ATV-11 diziam 15% para FII; era um erro da
     * especificação, não uma simplificação deliberada — os três documentos
     * foram corrigidos junto com esta constante.</p>
     */
    private static final double FII_CAPITAL_GAIN_TAX_RATE = 0.20;

    private final AssetRepository assetRepository;
    private final PositionService positionService;

    public IncomeTaxServiceImpl(AssetRepository assetRepository, PositionService positionService) {
        this.assetRepository = assetRepository;
        this.positionService = positionService;
    }

    @Override
    public MonthlyTaxAssessment assessMonth(Category category, YearMonth month) {
        validateTaxableCategory(category);
        List<RealizedSale> monthSales = realizedSalesForCategory(category).stream()
                .filter(sale -> YearMonth.from(sale.date()).equals(month))
                .toList();
        return buildAssessment(category, month, monthSales);
    }

    @Override
    public List<MonthlyTaxAssessment> assessYear(Category category, int year) {
        validateTaxableCategory(category);
        List<RealizedSale> allSales = realizedSalesForCategory(category);

        Map<YearMonth, List<RealizedSale>> salesByMonth = allSales.stream()
                .map(sale -> YearMonth.from(sale.date()))
                .distinct()
                .filter(yearMonth -> yearMonth.getYear() == year)
                .collect(Collectors.toMap(
                        yearMonth -> yearMonth,
                        yearMonth -> allSales.stream()
                                .filter(sale -> YearMonth.from(sale.date()).equals(yearMonth))
                                .toList()
                ));

        return salesByMonth.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> buildAssessment(category, entry.getKey(), entry.getValue()))
                .toList();
    }

    @Override
    public AnnualIncomeTaxSummary assessAnnualSummary(int year) {
        Map<Category, List<MonthlyTaxAssessment>> assessmentsByCategory = new EnumMap<>(Category.class);
        double totalTaxDue = 0;

        for (Category category : List.of(Category.STOCKS, Category.FIIS, Category.CRYPTO)) {
            List<MonthlyTaxAssessment> assessments = assessYear(category, year);
            assessmentsByCategory.put(category, assessments);
            totalTaxDue += assessments.stream().mapToDouble(MonthlyTaxAssessment::taxDue).sum();
        }

        return new AnnualIncomeTaxSummary(year, assessmentsByCategory, totalTaxDue);
    }

    @Override
    public double getAccumulatedLoss(Category category, YearMonth until) {
        validateTaxableCategory(category);
        Map<YearMonth, List<RealizedSale>> salesByMonth = realizedSalesForCategory(category).stream()
                .collect(Collectors.groupingBy(sale -> YearMonth.from(sale.date())));

        double accumulatedLoss = 0;
        for (Map.Entry<YearMonth, List<RealizedSale>> entry : salesByMonth.entrySet()) {
            if (!entry.getKey().isBefore(until)) {
                continue;
            }
            double grossGain = entry.getValue().stream().mapToDouble(RealizedSale::realizedGainAmount).sum();
            if (grossGain < 0) {
                accumulatedLoss += -grossGain;
            }
        }
        return accumulatedLoss;
    }

    // ---- Regra de isenção por categoria ----

    private MonthlyTaxAssessment buildAssessment(Category category, YearMonth month, List<RealizedSale> sales) {
        double totalSold = sales.stream().mapToDouble(sale -> sale.quantity() * sale.salePrice()).sum();
        double grossGain = sales.stream().mapToDouble(RealizedSale::realizedGainAmount).sum();

        boolean taxable;
        if (category == Category.FIIS) {
            taxable = true; // FII nunca tem isenção, independente do valor vendido.
        } else {
            double threshold = category == Category.STOCKS
                    ? STOCKS_MONTHLY_EXEMPTION_THRESHOLD
                    : CRYPTO_MONTHLY_EXEMPTION_THRESHOLD;
            // Gatilho binário: compara o TOTAL VENDIDO com o teto, nunca o ganho.
            taxable = totalSold > threshold;
        }

        // Prejuizo (grossGain < 0) nunca gera imposto negativo, mesmo em mes tributavel.
        double rate = category == Category.FIIS ? FII_CAPITAL_GAIN_TAX_RATE : CAPITAL_GAIN_TAX_RATE;
        double taxDue = (taxable && grossGain > 0) ? grossGain * rate : 0;

        return new MonthlyTaxAssessment(category, month, totalSold, grossGain, !taxable, taxDue);
    }

    // ---- Insumo: vendas realizadas (ATV-10) de todos os ativos de UMA categoria ----

    /**
     * Inclui ativos com soft delete (is_active = 0) — o histórico de
     * transações de um ativo "removido" continua valendo para o relatório de
     * IR (comentário já existente em {@code AssetRepository.remove}).
     */
    private List<RealizedSale> realizedSalesForCategory(Category category) {
        return assetRepository.listAssets(true).stream()
                .filter(asset -> asset.getCategory() == category)
                .map(Asset::getId)
                .flatMap(assetId -> positionService.calculateRealizedSales(assetId).stream())
                .toList();
    }

    private static void validateTaxableCategory(Category category) {
        if (category != Category.STOCKS && category != Category.FIIS && category != Category.CRYPTO) {
            throw new IllegalArgumentException(
                    "IncomeTaxService nao apura a categoria " + category
                            + " - FIXED_INCOME e tratada na ATV-15 (projecao de valor liquido) e FOREX nao tem "
                            + "regra de tributacao definida no planejamento (ver ATV-11)");
        }
    }
}
