package io.invest.iagent.service.extraction.parser;

import com.fasterxml.jackson.databind.JsonNode;
import io.invest.iagent.service.extraction.model.CompanyConfig;
import io.invest.iagent.service.extraction.model.Segment;

import java.util.List;
import java.util.Map;

/**
 * PDF 分部表 layout 处理策略：把"一张已解析的表 + 一条 mapping"落到 Segment/metric 里。
 *
 * <p>不同公司的港股财报分部表结构差异非常大：
 * <ul>
 *   <li>腾讯：每列一个分部、每行一个指标（SEGMENTS_AS_COLUMNS）；
 *       或每行一个分部、每列一个周期（SEGMENTS_AS_ROWS）</li>
 *   <li>美团：列=L1 分部（LOCAL_SERVICE/NEW_SERVICE/未分配/合计），
 *       行分成两段——前半行=L2 子分部的 REVENUE，后半行=L1 的 COST/OP_INCOME
 *       （SUBSEGMENT_MATRIX）</li>
 * </ul>
 *
 * 为避免 {@link PdfReportParser} 里堆积特化逻辑，把每种 layout 抽成一个 handler，
 * 公共的数值/校验/组装逻辑放在 {@link PdfExtractionSupport}。
 * 新增公司只需要：
 * <ol>
 *   <li>如果已有 handler 能处理其表格结构，直接在 config 里写 mapping；</li>
 *   <li>否则加一个新的 Layout 枚举值 + 一个 handler 实现。</li>
 * </ol>
 */
interface PdfLayoutHandler {

    /** 声明本 handler 能处理的 layout 值。 */
    CompanyConfig.PdfColumnMapping.Layout layout();

    /**
     * 用一条 mapping 尝试消费一张表。命中即写入 segmentsByCode 并返回本次产生的指标条数；
     * 未命中返回 0。
     *
     * @param mapping        当前 layout 的 mapping 配置
     * @param dataRows       已经 tidy 过的数据行（每行 = 一 List&lt;String&gt;）
     * @param tableId        原始表 id，用于 sourceLocation
     * @param currency       原始表币种
     * @param unit           原始表单位
     * @param context        filing 上下文（用来 resolve CURRENT_Q / PRIOR_Q 等占位符）
     * @param sink           输出 segmentCode → Segment 的 map
     */
    int apply(CompanyConfig.PdfColumnMapping mapping,
              List<List<String>> dataRows,
              String tableId, String currency, String unit,
              PdfReportParser.FilingContext context,
              Map<String, Segment> sink);

    /**
     * 把 python 侧的 dataRows 转成 List&lt;List&lt;String&gt;&gt; —— 所有 handler 共用。
     */
    static List<List<String>> asRows(JsonNode dataRowsNode) {
        java.util.List<java.util.List<String>> rows = new java.util.ArrayList<>();
        if (dataRowsNode == null || !dataRowsNode.isArray()) return rows;
        for (JsonNode rowNode : dataRowsNode) {
            java.util.List<String> cells = new java.util.ArrayList<>();
            for (JsonNode cellNode : rowNode) {
                cells.add(cellNode.asText(""));
            }
            rows.add(cells);
        }
        return rows;
    }
}
