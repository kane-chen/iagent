package io.invest.iagent.financial.option;

import io.invest.iagent.financial.config.prop.FinancialProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OptionStrangleService} 宽跨式收益/胜率/手续费计算测试：
 * 用合成期权链（不依赖富途 OpenD）验证盈亏平衡、亏损窗口、对数正态胜率与期望收益方向。
 */
class OptionStrangleServiceTest {

    private OptionStrangleService service;

    /**
     * 合成取数器：现价 100，30 天到期。
     * 正常腿 put 95/93/90、call 105/107/110（IV 28%~30%）；
     * 边界腿 put 76 / call 124（价外 24%，在幅度带内但组合亏损窗口约 50%，应被窗口上限剔除）；
     * 尘屑腿 call 200（价外 100%、IV 90%、极便宜，应被价外幅度带剔除——旧逻辑下它会霸榜）。
     */
    private static OptionChainFetcher.OptionChain syntheticChain() {
        List<OptionChainFetcher.OptionQuote> options = List.of(
                quote("P95", "PUT", 95, 2.0, 1.9, 2.1, 0.30, 1000),
                quote("P93", "PUT", 93, 1.4, 1.3, 1.5, 0.29, 900),
                quote("P90", "PUT", 90, 0.8, 0.75, 0.85, 0.28, 800),
                quote("P76", "PUT", 76, 0.55, 0.45, 0.55, 0.30, 1000),
                quote("C105", "CALL", 105, 2.0, 1.9, 2.1, 0.30, 1000),
                quote("C107", "CALL", 107, 1.4, 1.3, 1.5, 0.29, 900),
                quote("C110", "CALL", 110, 0.8, 0.75, 0.85, 0.28, 800),
                quote("C124", "CALL", 124, 0.55, 0.45, 0.55, 0.30, 1000),
                quote("C200", "CALL", 200, 0.02, 0.01, 0.03, 0.90, 500));
        var expiry = new OptionChainFetcher.OptionExpiry("2026-10-03", 30, options);
        return new OptionChainFetcher.OptionChain("US.TEST", "US", "USD",
                new BigDecimal("100"), List.of(expiry));
    }

    private static OptionChainFetcher.OptionQuote quote(String code, String type, double strike,
                                                        double last, double bid, double ask,
                                                        double iv, long oi) {
        return new OptionChainFetcher.OptionQuote(code, type, BigDecimal.valueOf(strike),
                BigDecimal.valueOf(last), BigDecimal.valueOf(bid), BigDecimal.valueOf(ask),
                BigDecimal.valueOf(iv), oi, 100, null, 100);
    }

    @BeforeEach
    void setUp() {
        service = new OptionStrangleService();
        OptionChainFetcher stubFetcher = new OptionChainFetcher() {
            @Override
            public OptionChainFetcher.OptionChain fetch(String ticker, int expiries) {
                return syntheticChain();
            }
        };
        ReflectionTestUtils.setField(service, "fetcher", stubFetcher);
        ReflectionTestUtils.setField(service, "properties", new FinancialProperties());
    }

    @Test
    void testStrangleRecommendation() {
        OptionStrangleService.StrangleResult r = service.recommend("US.TEST", 1);
        assertTrue(r.hasData(), () -> "应有推荐结果: " + r.message());
        assertNotNull(r.fees());
        assertEquals(5, r.profitTop5().size());
        assertEquals(5, r.winRateTop5().size());

        // (put95, call105) 组合：权利金 4.0/股 → 400 + 期权费 1.99；盈亏平衡约 90.95 / 109.05。
        // 该组合胜率最高（在胜率榜），期望收益最负（可能不在收益榜），故在两个榜单并集中查找。
        var nearest = java.util.stream.Stream.concat(r.profitTop5().stream(), r.winRateTop5().stream())
                .filter(c -> c.putStrike().compareTo(new BigDecimal("95")) == 0
                        && c.callStrike().compareTo(new BigDecimal("105")) == 0)
                .findFirst().orElseThrow();
        // 胜率榜第一应为最靠近平值的 (95,105)
        assertEquals(95, r.winRateTop5().get(0).putStrike().intValue());
        assertEquals(105, r.winRateTop5().get(0).callStrike().intValue());
        assertEquals(401.99, nearest.maxLoss().doubleValue(), 0.01, "最大亏损=权利金400+期权费1.99");
        assertEquals(401.99, nearest.premiumTotal().add(nearest.openFee()).doubleValue(), 0.01);
        assertEquals(90.95, nearest.breakevenDown().doubleValue(), 0.15, "下盈亏平衡≈90.95");
        assertEquals(109.05, nearest.breakevenUp().doubleValue(), 0.15, "上盈亏平衡≈109.05");
        assertTrue(nearest.breakevenDown().doubleValue() < 95.0, "下平衡在 put 行权价外侧");
        assertTrue(nearest.breakevenUp().doubleValue() > 105.0, "上平衡在 call 行权价外侧");
        assertTrue(nearest.lossWindow().doubleValue() > 0);
        // σ√T≈0.086，双尾各约 14.5% → 胜率约 29%
        assertEquals(29.0, nearest.winProb().doubleValue(), 5.0, "胜率约 29%");
        // 做多波动率、按 IV 定价时期望收益为负（时间价值+手续费损耗）
        assertTrue(nearest.expectedProfit().doubleValue() < 0, "买方期望收益为负");
        // 窗口未平仓量：90.95~109.05 覆盖行权价 93/95/105/107（90 与 110 在窗口外）
        assertEquals(900 + 1000 + 1000 + 900, nearest.windowOpenInterest());

        // 新规则：价外幅度带（±25%）——100% OTM 的尘屑 call(200) 不得入选（旧逻辑下会凭虚假期望收益霸榜）
        var all = allCombos(r);
        assertTrue(all.stream().noneMatch(c -> c.callStrike().intValue() == 200),
                "深度虚值尘屑合约不得入选");
        // 新规则：亏损窗口上限（现价 30%）——put76/call124 参与的组合窗口约 34%~50%，应全部剔除
        assertTrue(all.stream().noneMatch(c -> c.putStrike().intValue() == 76
                || c.callStrike().intValue() == 124), "亏损窗口过宽的组合应被剔除");
        // 所有推荐组合亏损窗口均不超过现价 30%
        for (var c : all) {
            assertTrue(c.lossWindow().doubleValue() <= 30.05,
                    "亏损窗口超限: " + c.lossWindow());
        }
        // 筛选过程应给出提示
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("价外")),
                "应提示价外幅度过滤");

        // 收益榜按期望收益率排序后，榜首应为成本占优的窄组合而非尘屑组合
        assertEquals(105, r.profitTop5().get(0).callStrike().intValue());

        // 收益分布：价内深度盈利、两行权价之间为最大亏损
        var curve = nearest.distribution();
        assertNotNull(curve);
        assertFalse(curve.isEmpty());
        assertEquals(-401.99, profitAt(curve, 100.0), 1.0, "现价附近=最大亏损");
        assertTrue(profitAt(curve, curve.get(curve.size() - 1).price().doubleValue()) > 0,
                "分布上端应盈利");
    }

    @Test
    void testUnsupportedMarket() {
        OptionStrangleService.StrangleResult r = service.recommend("SH.600519", 1);
        assertFalse(r.hasData());
        assertTrue(r.message().contains("港美"));
    }

    /** 两个 Top5 榜单并集。 */
    private static List<OptionStrangleService.StrangleCombo> allCombos(OptionStrangleService.StrangleResult r) {
        List<OptionStrangleService.StrangleCombo> all = new java.util.ArrayList<>();
        all.addAll(r.profitTop5());
        all.addAll(r.winRateTop5());
        return all;
    }

    /** 取分布曲线上最接近 price 的采样点净利润。 */
    private static double profitAt(List<OptionStrangleService.PayoffPoint> curve, double price) {
        return curve.stream()
                .min(java.util.Comparator.comparingDouble(p -> Math.abs(p.price().doubleValue() - price)))
                .orElseThrow().profit().doubleValue();
    }
}
