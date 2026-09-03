package io.invest.iagent.financial.option;

import io.invest.iagent.financial.config.FinancialProperties;
import io.invest.iagent.financial.ingest.FutuCodeUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 期权「买入宽跨式（long strangle）」推荐服务。
 *
 * <p>策略：同一到期日买入一张 OTM put（Kp &lt; 现价）与一张 OTM call（Kc &gt; 现价），
 * 到期价格落在两个盈亏平衡点之外即盈利。为控制亏损窗口，候选腿限制价外幅度
 * （默认 ±{@code app.financial.option.max-otm-pct=25%}）、最低未平仓量，且亏损窗口宽度
 * 超过现价 {@code max-loss-window-pct=30%} 的组合直接剔除。对每个到期日枚举全部 put×call 组合，计算：
 * <ul>
 *   <li>成本：权利金 + 期权开仓手续费（两条腿）；</li>
 *   <li>到期收益：ITM 腿内在价值 − 成本 − 行权后正股平仓手续费；</li>
 *   <li>盈亏平衡点 / 亏损窗口；</li>
 *   <li>胜率：对数正态分布（σ 取两腿隐含波动率均值，r=0）下到期价落在盈亏平衡区间外的概率；</li>
 *   <li>期望收益/期望收益率：收益函数对到期价对数正态密度数值积分；</li>
 *   <li>窗口未平仓量：同一到期日、行权价落在亏损窗口 [下盈亏平衡, 上盈亏平衡] 内的期权 OI 合计。</li>
 * </ul>
 * 分别按期望收益最大化、胜率最大化给出 Top 5。金额均按 1 张 put + 1 张 call 计。
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "app.financial", name = "enabled", havingValue = "true")
public class OptionStrangleService {

    /** 期望收益数值积分：到期价网格 [spot*LO_MULT, spot*HI_MULT]，对数等分。 */
    private static final double LO_MULT = 0.15;
    private static final double HI_MULT = 4.0;
    private static final int INTEGRATION_STEPS = 480;

    @Autowired
    private OptionChainFetcher fetcher;

    @Autowired
    private FinancialProperties properties;

    /** 推荐结果。 */
    public record StrangleResult(String ticker, String currency, BigDecimal spot,
                                 boolean hasData, String message, List<String> warnings,
                                 FeeInfo fees, List<StrangleCombo> profitTop5,
                                 List<StrangleCombo> winRateTop5) {}

    /** 手续费口径说明（随结果返回，便于页面展示假设）。 */
    public record FeeInfo(String market, String currency, String note,
                          double optionFeePerContract, double optionMinFeePerOrder,
                          double stockFeeRate, double stockFeePerShare, double stockMinFee) {}

    /** 宽跨式组合：合约/成本/盈亏平衡/胜率/期望收益 + 收益分布曲线。 */
    public record StrangleCombo(String expiry, int daysToExpiry,
                                BigDecimal putStrike, BigDecimal callStrike,
                                String putCode, String callCode,
                                BigDecimal putPrice, BigDecimal callPrice, int contractSize,
                                BigDecimal premiumTotal, BigDecimal openFee, BigDecimal maxLoss,
                                BigDecimal breakevenDown, BigDecimal breakevenUp,
                                BigDecimal lossWindow, BigDecimal winProb,
                                BigDecimal expectedProfit, BigDecimal expectedRoi,
                                long putOpenInterest, long callOpenInterest, long windowOpenInterest,
                                List<PayoffPoint> distribution) {}

    /** 收益分布采样点：到期价 price → 到期净利润 profit（1 张 put + 1 张 call）。 */
    public record PayoffPoint(BigDecimal price, BigDecimal profit) {}

    /**
     * 计算宽跨式推荐。
     *
     * @param ticker   股票代码（裸 ticker 或带市场前缀）
     * @param expiries 扫描最近到期日数；null 用配置默认
     */
    public StrangleResult recommend(String ticker, Integer expiries) {
        List<String> warnings = new ArrayList<>();
        String futuCode = FutuCodeUtil.toFutuCode(ticker);
        String bare = FutuCodeUtil.bareTicker(futuCode);
        String market = FutuCodeUtil.marketOf(futuCode);
        if (!"US".equals(market) && !"HK".equals(market)) {
            return new StrangleResult(bare, null, null, false,
                    "期权链仅支持港美正股/ETF（不支持 " + market + " 市场）。",
                    warnings, null, List.of(), List.of());
        }

        int n = expiries == null ? properties.getOption().getExpiries()
                : Math.max(1, Math.min(8, expiries));
        OptionChainFetcher.OptionChain chain;
        try {
            chain = fetcher.fetch(bare, n);
        } catch (Exception e) {
            log.warn("期权链取数失败: ticker={}, {}", bare, e.getMessage());
            return new StrangleResult(bare, null, null, false,
                    "期权链取数失败：" + e.getMessage() + "（需富途 OpenD 已启动并登录，且正股支持期权）。",
                    warnings, null, List.of(), List.of());
        }

        BigDecimal spot = chain.spot();
        if (spot == null || spot.signum() <= 0 || chain.expiries().isEmpty()) {
            return new StrangleResult(bare, chain.currency(), spot, false,
                    "未查询到 " + bare + " 的期权链快照数据（可能在非交易时段或无期权报价）。",
                    warnings, null, List.of(), List.of());
        }

        FinancialProperties.Commission commission =
                properties.getOption().getCommissions().getOrDefault(market,
                        properties.getOption().getCommissions().get("US"));
        FinancialProperties.Option optCfg = properties.getOption();
        int minOi = optCfg.getMinOpenInterest();
        double spotD = spot.doubleValue();
        // 价外幅度带：put/call 行权价限制在现价 ±maxOtmPct 之内，过宽的尘屑合约不参与组合
        double putMinStrike = spotD * (1.0 - optCfg.getMaxOtmPct());
        double callMaxStrike = spotD * (1.0 + optCfg.getMaxOtmPct());
        double maxLossWindow = spotD * optCfg.getMaxLossWindowPct();

        List<Eval> evals = new ArrayList<>();
        int skippedLegs = 0;
        int skippedWideCombos = 0;
        for (OptionChainFetcher.OptionExpiry expiry : chain.expiries()) {
            if (expiry.daysToExpiry() <= 0) {
                continue;
            }
            List<OptionChainFetcher.OptionQuote> puts = new ArrayList<>();
            List<OptionChainFetcher.OptionQuote> calls = new ArrayList<>();
            for (OptionChainFetcher.OptionQuote q : expiry.options()) {
                if (entryPrice(q) == null || ivFraction(q.iv()) == null
                        || q.openInterest() < minOi || q.strike() == null) {
                    skippedLegs++;
                    continue;
                }
                double k = q.strike().doubleValue();
                int cmp = q.strike().compareTo(spot);
                if ("PUT".equals(q.type()) && cmp < 0 && k >= putMinStrike) {
                    puts.add(q);
                } else if ("CALL".equals(q.type()) && cmp > 0 && k <= callMaxStrike) {
                    calls.add(q);
                } else if (("PUT".equals(q.type()) && cmp < 0)
                        || ("CALL".equals(q.type()) && cmp > 0)) {
                    // 有报价但超出价外幅度带
                    skippedLegs++;
                }
            }
            for (OptionChainFetcher.OptionQuote put : puts) {
                for (OptionChainFetcher.OptionQuote call : calls) {
                    Eval e = evaluate(expiry, put, call, spotD, commission, maxLossWindow);
                    if (e != null) {
                        evals.add(e);
                    } else {
                        skippedWideCombos++;
                    }
                }
            }
        }
        if (skippedLegs > 0) {
            warnings.add(String.format(
                    "已跳过 %d 条无报价/无隐含波动率/未平仓量过低或价外超过 %.0f%% 的期权腿。",
                    skippedLegs, optCfg.getMaxOtmPct() * 100));
        }
        if (skippedWideCombos > 0) {
            warnings.add(String.format(
                    "已剔除 %d 个亏损窗口超过现价 %.0f%% 的过宽组合。",
                    skippedWideCombos, optCfg.getMaxLossWindowPct() * 100));
        }
        if (evals.isEmpty()) {
            return new StrangleResult(bare, chain.currency(), spot, false,
                    "最近 " + n + " 个到期日内未找到可计算的宽跨式组合（需 put/call 均有报价与隐含波动率）。",
                    warnings, feeInfo(market, chain.currency(), commission), List.of(), List.of());
        }

        // 收益榜按期望收益率（期望收益/成本）排序：绝对期望收益会偏袒成本极低的尘屑组合
        List<Eval> byProfit = evals.stream()
                .sorted(Comparator.comparingDouble((Eval x) -> x.expectedRoi).reversed())
                .limit(5).toList();
        List<Eval> byWin = evals.stream()
                .sorted(Comparator.comparingDouble((Eval x) -> x.winProb).reversed()
                        .thenComparingDouble(x -> x.lossWindow))
                .limit(5).toList();

        // 收益分布曲线仅对入选两个榜单的组合计算（并集去重）
        Map<String, Eval> selected = new LinkedHashMap<>();
        for (Eval e : byProfit) {
            selected.put(e.key(), e);
        }
        for (Eval e : byWin) {
            selected.putIfAbsent(e.key(), e);
        }
        int points = Math.max(11, properties.getOption().getDistributionPoints());
        for (Eval e : selected.values()) {
            e.distribution = buildDistribution(e, spot.doubleValue(), commission, points);
        }

        List<StrangleCombo> profitTop5 = byProfit.stream().map(e -> e.toCombo()).toList();
        List<StrangleCombo> winTop5 = byWin.stream().map(e -> e.toCombo()).toList();
        return new StrangleResult(bare, chain.currency(), spot, true, null, warnings,
                feeInfo(market, chain.currency(), commission), profitTop5, winTop5);
    }

    // =========================================================
    //  组合计算
    // =========================================================

    /** 组合中间计算结果（double 口径，输出时转 BigDecimal）。 */
    private static final class Eval {
        OptionChainFetcher.OptionExpiry expiry;
        OptionChainFetcher.OptionQuote put;
        OptionChainFetcher.OptionQuote call;
        double putPrice;
        double callPrice;
        int mult;
        double sigma;
        double premiumTotal;
        double openFee;
        double debit;
        double beDown;
        double beUp;
        double lossWindow;
        double winProb;
        double expectedProfit;
        double expectedRoi;
        long windowOi;
        List<PayoffPoint> distribution;

        String key() {
            return expiry.strikeTime() + "|" + put.strike() + "|" + call.strike();
        }

        StrangleCombo toCombo() {
            return new StrangleCombo(
                    expiry.strikeTime(), expiry.daysToExpiry(),
                    money(put.strike().doubleValue()), money(call.strike().doubleValue()),
                    put.code(), call.code(),
                    money(putPrice), money(callPrice), mult,
                    money(premiumTotal), money(openFee), money(debit),
                    money(beDown), money(beUp), money(lossWindow),
                    pct(winProb * 100), money(expectedProfit), pct(expectedRoi * 100),
                    put.openInterest(), call.openInterest(), windowOi, distribution);
        }
    }

    /**
     * 评估单个 put×call 组合；无法计算（IV/期限异常、盈亏平衡异常、亏损窗口超上限）返回 null。
     *
     * @param maxLossWindow 亏损窗口宽度上限（现价 × maxLossWindowPct）
     */
    private Eval evaluate(OptionChainFetcher.OptionExpiry expiry,
                          OptionChainFetcher.OptionQuote put, OptionChainFetcher.OptionQuote call,
                          double spot, FinancialProperties.Commission fee, double maxLossWindow) {
        Double pp = entryPrice(put);
        Double cp = entryPrice(call);
        Double ivP = ivFraction(put.iv());
        Double ivC = ivFraction(call.iv());
        if (pp == null || cp == null || ivP == null || ivC == null || pp <= 0 || cp <= 0) {
            return null;
        }
        double sigma = (ivP + ivC) / 2.0;
        double t = expiry.daysToExpiry() / 365.0;
        if (sigma <= 0 || t <= 0) {
            return null;
        }
        int mult = put.contractSize() > 0 ? put.contractSize() : 100;
        double kp = put.strike().doubleValue();
        double kc = call.strike().doubleValue();

        Eval e = new Eval();
        e.expiry = expiry;
        e.put = put;
        e.call = call;
        e.putPrice = pp;
        e.callPrice = cp;
        e.mult = mult;
        e.sigma = sigma;
        e.premiumTotal = (pp + cp) * mult;
        // 两条腿同一笔订单：2 张期权费与单笔最低取大
        e.openFee = Math.max(2.0 * fee.getOptionFeePerContract(), fee.getOptionMinFeePerOrder());
        e.debit = e.premiumTotal + e.openFee;

        // 每股固定成本（权利金 + 期权费）与行权后正股平仓费参数
        double fixedPerShare = (pp + cp) + e.openFee / mult;
        double stockPerShare = fee.getStockFeePerShare() + fee.getStockMinFee() / mult;
        double r = fee.getStockFeeRate();
        // 上盈亏平衡：S-Kc = fixed + r*S + stockPerShare → S(1-r) = Kc + A
        double a = fixedPerShare + stockPerShare;
        e.beUp = (kc + a) / (1.0 - r);
        e.beDown = (kp - a) / (1.0 + r);
        if (e.beDown <= 0 || e.beUp <= spot || e.beDown >= spot) {
            return null;
        }
        e.lossWindow = e.beUp - e.beDown;
        // 亏损窗口过宽（两行权价相隔太远）的组合直接剔除，保证推荐组合胜率可控
        if (e.lossWindow > maxLossWindow) {
            return null;
        }

        // 胜率：到期价落在盈亏平衡区间外的对数正态概率（r_f=0）。
        // BSM 中 P(S_T > K) = Φ(d2)、P(S_T < K) = Φ(-d2)。
        double sqrtT = Math.sqrt(t);
        double pDown = normalCdf(-d2(spot, e.beDown, sigma, t, sqrtT));
        double pUp = normalCdf(d2(spot, e.beUp, sigma, t, sqrtT));
        e.winProb = pDown + pUp;

        // 期望收益：收益函数 × 对数正态密度数值积分
        e.expectedProfit = integrateExpectedProfit(e, spot, fee, sigma, t);
        e.expectedRoi = e.debit > 0 ? e.expectedProfit / e.debit : 0.0;

        // 亏损窗口内未平仓量（同一到期日，行权价落在 [beDown, beUp] 的 CALL+PUT OI 合计）
        long oi = 0;
        for (OptionChainFetcher.OptionQuote q : expiry.options()) {
            if (q.strike() == null) {
                continue;
            }
            double k = q.strike().doubleValue();
            if (k >= e.beDown && k <= e.beUp) {
                oi += q.openInterest();
            }
        }
        e.windowOi = oi;
        return e;
    }

    /** 到期净利润（1 张 put + 1 张 call，含全部手续费）。 */
    private double payoff(Eval e, double s, FinancialProperties.Commission fee) {
        double gross;
        if (s > e.call.strike().doubleValue()) {
            gross = (s - e.call.strike().doubleValue()) * e.mult;
        } else if (s < e.put.strike().doubleValue()) {
            gross = (e.put.strike().doubleValue() - s) * e.mult;
        } else {
            gross = 0.0;
        }
        double stockFee = 0.0;
        if (gross > 0) {
            // ITM 腿行权后按到期价平仓正股，收一次正股交易费（按金额费率 + 每股费，与单笔最低取大）
            double notionalFee = s * e.mult * fee.getStockFeeRate()
                    + e.mult * fee.getStockFeePerShare();
            stockFee = Math.max(notionalFee, fee.getStockMinFee());
        }
        return gross - e.debit - stockFee;
    }

    /** 对数正态密度下的期望净利润：对数等距网格梯形积分。 */
    private double integrateExpectedProfit(Eval e, double spot,
                                           FinancialProperties.Commission fee, double sigma, double t) {
        double lo = spot * LO_MULT;
        double hi = spot * HI_MULT;
        double dLog = Math.log(hi / lo) / INTEGRATION_STEPS;
        double var = sigma * sigma * t;
        double prevS = lo;
        double prevF = payoff(e, prevS, fee) * logNormalPdf(prevS, spot, var);
        double sum = 0.0;
        for (int i = 1; i <= INTEGRATION_STEPS; i++) {
            double s = lo * Math.exp(dLog * i);
            double f = payoff(e, s, fee) * logNormalPdf(s, spot, var);
            // 梯形积分（x 轴为实际价格 s）
            sum += (prevF + f) * 0.5 * (s - prevS);
            prevS = s;
            prevF = f;
        }
        return sum;
    }

    /** 收益分布曲线：覆盖两个盈亏平衡点的价格区间内线性采样。 */
    private List<PayoffPoint> buildDistribution(Eval e, double spot,
                                                FinancialProperties.Commission fee, int points) {
        double lo = Math.min(e.beDown * 0.97, spot * 0.88);
        double hi = Math.max(e.beUp * 1.03, spot * 1.12);
        List<PayoffPoint> curve = new ArrayList<>(points);
        for (int i = 0; i < points; i++) {
            double s = lo + (hi - lo) * i / (points - 1);
            curve.add(new PayoffPoint(money(s), money(payoff(e, s, fee))));
        }
        return curve;
    }

    /** BSM d2（r_f=0）：看涨 ITM 概率 P(S_T &gt; K) = Φ(d2)。 */
    private static double d2(double spot, double k, double sigma, double t, double sqrtT) {
        return (Math.log(spot / k) - 0.5 * sigma * sigma * t) / (sigma * sqrtT);
    }

    /** 到期价 S 的对数正态密度（r_f=0：ln(S/S0) ~ N(-0.5σ²T, σ²T)，var=σ²T）。 */
    private static double logNormalPdf(double s, double spot, double var) {
        double z = Math.log(s / spot) + 0.5 * var;
        return Math.exp(-z * z / (2.0 * var)) / (s * Math.sqrt(2.0 * Math.PI * var));
    }

    // =========================================================
    //  工具
    // =========================================================

    /** 腿入场价：买卖一档均有效时取中间价（更接近可成交价），否则取最新价。 */
    private static Double entryPrice(OptionChainFetcher.OptionQuote q) {
        if (q.bid() != null && q.ask() != null && q.bid().signum() > 0
                && q.ask().signum() > 0 && q.ask().compareTo(q.bid()) >= 0) {
            return q.bid().add(q.ask()).doubleValue() / 2.0;
        }
        return q.last() != null && q.last().signum() > 0 ? q.last().doubleValue() : null;
    }

    /**
     * IV 归一化为小数：快照可能返回 28.5（百分比）或 0.285（小数），&gt;1.5 视为百分比。
     * 合理区间 0.5%~150%：深度虚值尘屑合约常基于过期成交算出畸高 IV（200%+），
     * 会污染两腿均值波动率并造成虚假期望收益，直接剔除。
     */
    private static Double ivFraction(BigDecimal iv) {
        if (iv == null || iv.signum() <= 0) {
            return null;
        }
        double v = iv.doubleValue();
        if (v > 1.5) {
            v /= 100.0;
        }
        return (v >= 0.005 && v <= 1.5) ? v : null;
    }

    /** 标准正态 CDF（Abramowitz-Stegun erf 近似，误差 &lt; 1.5e-7）。 */
    private static double normalCdf(double x) {
        return 0.5 * (1.0 + erf(x / Math.sqrt(2.0)));
    }

    private static double erf(double x) {
        double sign = x < 0 ? -1.0 : 1.0;
        double ax = Math.abs(x);
        double t = 1.0 / (1.0 + 0.3275911 * ax);
        double y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t
                - 0.284496736) * t + 0.254829592) * t * Math.exp(-ax * ax);
        return sign * y;
    }

    private static BigDecimal money(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal pct(double v) {
        return BigDecimal.valueOf(v).setScale(1, RoundingMode.HALF_UP);
    }

    private FeeInfo feeInfo(String market, String currency, FinancialProperties.Commission f) {
        FinancialProperties.Option o = properties.getOption();
        String note = String.format(
                "按%s市场近似费率估算（币种 %s）：期权开仓 %.2f/张（每单最低 %.2f）；"
                        + "ITM 腿行权后正股平仓按金额 %.2f%% + %.4f/股 收费（每单最低 %.2f）。"
                        + "收益按 1 张 put + 1 张 call、持有至到期计算。"
                        + "仅推荐行权价距现价 ±%.0f%% 以内、亏损窗口不超过现价 %.0f%%、未平仓量≥%d 的组合。",
                market, currency == null ? "" : currency,
                f.getOptionFeePerContract(), f.getOptionMinFeePerOrder(),
                f.getStockFeeRate() * 100, f.getStockFeePerShare(), f.getStockMinFee(),
                o.getMaxOtmPct() * 100, o.getMaxLossWindowPct() * 100, o.getMinOpenInterest());
        return new FeeInfo(market, currency, note,
                f.getOptionFeePerContract(), f.getOptionMinFeePerOrder(),
                f.getStockFeeRate(), f.getStockFeePerShare(), f.getStockMinFee());
    }
}
