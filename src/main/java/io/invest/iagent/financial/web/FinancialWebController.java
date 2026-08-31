package io.invest.iagent.financial.web;

import io.invest.iagent.financial.service.FinancialQueryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Map;

/**
 * 财务分析 Web 页面入口与 REST 接口。
 * <ul>
 *   <li>页面入口：{@code GET /financial} → 静态页 {@code /financial/index.html}</li>
 *   <li>{@code GET /api/financial/dashboard} — 最近季度核心指标卡片（数值 + 同比）</li>
 *   <li>{@code GET /api/financial/trend} — 指标最近 N 期数值与同比序列（默认 16 期）</li>
 *   <li>{@code GET /api/financial/composition} — 指标构成：所属报表的分层指标树</li>
 * </ul>
 * 数据均来自 {@link FinancialQueryService}，随财务模块开关 app.financial.enabled 启用。
 */
@Slf4j
@Controller
@ConditionalOnProperty(prefix = "app.financial", name = "enabled", havingValue = "true")
public class FinancialWebController {

    private final FinancialQueryService queryService;

    public FinancialWebController(FinancialQueryService queryService) {
        this.queryService = queryService;
    }

    /** 页面入口：/financial 重定向到静态页面。 */
    @GetMapping("/financial")
    public String page() {
        return "redirect:/financial/index.html";
    }

    /** 仪表盘：最近一个季度核心指标（净利润/经营利润/资本开支/资产负债表）。 */
    @GetMapping("/api/financial/dashboard")
    @ResponseBody
    public FinancialQueryService.DashboardResult dashboard(@RequestParam("ticker") String ticker) {
        return queryService.dashboard(ticker);
    }

    /** 趋势：指标最近 N 期（默认 16，可调）数值与同比序列。 */
    @GetMapping("/api/financial/trend")
    @ResponseBody
    public FinancialQueryService.TrendResult trend(
            @RequestParam("ticker") String ticker,
            @RequestParam("metric") String metric,
            @RequestParam(value = "quarters", defaultValue = "16") Integer quarters,
            @RequestParam(value = "periodType", defaultValue = "SINGLE_Q") String periodType) {
        return queryService.trend(ticker, metric, quarters, periodType);
    }

    /** 构成：目标指标所属报表的分层指标 × 最近 N 期表格（默认 8 期，逆序）。 */
    @GetMapping("/api/financial/composition")
    @ResponseBody
    public FinancialQueryService.CompositionResult composition(
            @RequestParam("ticker") String ticker,
            @RequestParam("metric") String metric,
            @RequestParam(value = "quarters", defaultValue = "8") Integer quarters) {
        return queryService.composition(ticker, metric, quarters);
    }

    /** 兜底异常处理：返回 hasData=false 的 JSON，由页面提示。 */
    @ExceptionHandler(Exception.class)
    @ResponseBody
    public Map<String, Object> handleError(Exception e) {
        log.warn("financial web api error: {}", e.getMessage());
        return Map.of("hasData", false, "message", "查询失败: " + e.getMessage());
    }
}
