package io.invest.iagent.tools.financial;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.invest.iagent.financial.service.FinancialIngestService;
import io.invest.iagent.financial.service.FinancialQueryService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 财务数据服务工具：
 * <ul>
 *   <li>{@code financial_data_build} — 采集指定公司三大表数据入库（futu API，幂等可重复执行）。</li>
 *   <li>{@code financial_data_query} — 查询财务指标，返回分层指标 × 期间的 Markdown 表格；
 *       库中期间不足时自动触发补采。</li>
 *   <li>{@code financial_data_metrics} — 列出标准指标目录（分层、含中英文别名）。</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "app.financial", name = "enabled", havingValue = "true")
public class FinancialDataTool {

    private final FinancialIngestService ingestService;
    private final FinancialQueryService queryService;

    public FinancialDataTool(FinancialIngestService ingestService, FinancialQueryService queryService) {
        this.ingestService = ingestService;
        this.queryService = queryService;
    }

    @Tool(name = "financial_data_build",
            description = "采集指定上市公司的财务三大表（利润表/资产负债表/现金流量表）数据并写入财务数据库。"
                    + "数据来自 futu API，指标已按统一标准模型归一化（港股/A股累计口径自动差分单季，金额单位百万财报币种）。"
                    + "重复执行幂等（按 公司+期间+口径+指标+来源 覆盖更新）。"
                    + "当查询提示数据缺失或期间不足时，也应调用本工具补采。"
                    + "首次执行依赖 futu OpenD 网关，耗时可能较长（约 1-3 分钟）。")
    public String build(
            @ToolParam(name = "ticker", description = "股票代码，可带或不带市场前缀，如 00700、HK.00700、BABA、600519") String ticker,
            @ToolParam(name = "periods", required = false, description = "拉取的财报期数（每种报表），默认 16") Integer periods
    ) {
        try {
            if (StringUtils.isBlank(ticker)) {
                return "financial_data_build failed: ticker is required";
            }
            FinancialIngestService.BuildResult r = ingestService.build(ticker.trim(), periods);
            StringBuilder sb = new StringBuilder(r.message());
            if (r.warnings() != null && !r.warnings().isEmpty()) {
                sb.append("\n警告/部分失败：\n");
                for (String w : r.warnings()) {
                    sb.append(" - ").append(w).append("\n");
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "financial_data_build failed: " + e.getMessage();
        }
    }

    @Tool(name = "financial_data_query",
            description = "查询上市公司财务指标数据，返回'分层指标 × 财报期间'的 Markdown 表格（金额单位百万财报币种，比率为百分比，括号内为同比）。"
                    + "指标体系分层可展开（如 营业费用→销售费用/管理费用/研发费用），支持中文指标名、英文名或标准编码，"
                    + "可用 financial_data_metrics 查看完整指标目录。若库中数据期间不足会自动补采（首次较慢）。")
    public String query(
            @ToolParam(name = "ticker", description = "股票代码，可带或不带市场前缀，如 00700、BABA、600519") String ticker,
            @ToolParam(name = "metrics", required = false,
                    description = "指标列表，逗号分隔，可用中文名/英文名/编码，如 '营业收入,净利润,REVENUE'；不填返回全部有值指标") String metrics,
            @ToolParam(name = "periods", required = false,
                    description = "财报期间列表，逗号分隔，如 '2025Q1,2025Q2,FY2024'；不填返回最近 6 期") String periods,
            @ToolParam(name = "period_type", required = false,
                    description = "期间口径：SINGLE_Q=单季度（默认）、CUMULATIVE=年内累计（港股/A股的半年报/三季报口径）、FY=年报") String periodType
    ) {
        try {
            if (StringUtils.isBlank(ticker)) {
                return "financial_data_query failed: ticker is required";
            }
            List<String> metricList = splitList(metrics);
            List<String> periodList = splitList(periods);
            FinancialQueryService.QueryResult r = queryService.query(ticker.trim(), metricList, periodList, periodType);
            StringBuilder sb = new StringBuilder(r.markdown());
            if (r.warnings() != null && !r.warnings().isEmpty()) {
                sb.append("\n\n注意：\n");
                for (String w : r.warnings()) {
                    sb.append(" - ").append(w).append("\n");
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "financial_data_query failed: " + e.getMessage();
        }
    }

    @Tool(name = "financial_segment_query",
            description = "查询上市公司分部业务数据（分业务/分地区的收入、利润、EBITA/EBITDA 等），"
                    + "返回'分部 × 指标 × 财报期间'的 Markdown 表格，分部按层级缩进展示，括号内为同比。"
                    + "数据从本地财报文件解析（segment-financial-report skill）：首次查询自动提取，"
                    + "需该公司财报已通过 futu-filing 下载且已有分部配置；若未准备好会直接提示原因。")
    public String segmentQuery(
            @ToolParam(name = "ticker", description = "股票代码（不带市场前缀），如 00700、BABA、83690") String ticker,
            @ToolParam(name = "periods", required = false,
                    description = "财报期间列表，逗号分隔，如 'FY2023,FY2024,FY2025'；不填返回最近 6 期") String periods
    ) {
        try {
            if (StringUtils.isBlank(ticker)) {
                return "financial_segment_query failed: ticker is required";
            }
            FinancialQueryService.QueryResult r = queryService.querySegments(ticker.trim(), splitList(periods));
            StringBuilder sb = new StringBuilder(r.markdown());
            if (r.warnings() != null && !r.warnings().isEmpty()) {
                sb.append("\n\n注意：\n");
                for (String w : r.warnings()) {
                    sb.append(" - ").append(w).append("\n");
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "financial_segment_query failed: " + e.getMessage();
        }
    }

    @Tool(name = "financial_data_metrics",
            description = "列出财务数据服务的标准指标目录：分层展示指标编码、中文名与别名，可按报表过滤。"
                    + "查询 financial_data_query 前可用本工具确认指标的标准名称。")
    public String metrics(
            @ToolParam(name = "statement", required = false,
                    description = "报表过滤：income=利润表、balance=资产负债表、cashflow=现金流量表、derived=派生指标；不填返回全部") String statement
    ) {
        try {
            return queryService.listMetrics(statement);
        } catch (Exception e) {
            return "financial_data_metrics failed: " + e.getMessage();
        }
    }

    /** 逗号分隔（兼容中文逗号）→ 列表；空白返回 null。 */
    private static List<String> splitList(String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        return Arrays.stream(raw.split("[,，]"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
