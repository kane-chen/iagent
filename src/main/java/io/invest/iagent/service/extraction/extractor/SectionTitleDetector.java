package io.invest.iagent.service.extraction.extractor;

import io.invest.iagent.service.extraction.model.TableCell;
import io.invest.iagent.service.extraction.model.TableRow;

/**
 * 表格"节标题行"的识别器 —— 把三种常见 HTML 财报节标题写法收敛成一处。
 *
 * <p>财报 HTML 里的节标题行只是"承上启下"的分隔行，本身不含数据；下方紧跟的
 * 是相应指标（Revenue / Operating Income / Gross Profit ...）下每个分部的数值行。
 * 不同公司用的格式千奇百怪：
 * <ul>
 *   <li>BABA / GOOGL 用带冒号的"Revenues:" / "Operating income (loss):"；</li>
 *   <li>PDD 用 {@code <B>Revenues</B>}；</li>
 *   <li>MSFT 10-Q 用 {@code <td><p><span style="font-weight:bold">Revenue</span></p></td>}
 *       —— 粗体挂在最内层 span 上，parser 层不一定能识别到 {@code row.isBold()=true}，
 *       但整行数据格全空，这是稳定的结构信号。</li>
 * </ul>
 *
 * <p>本类只提供"是不是节标题"的判定；具体属于哪个 metric 由调用方按关键字过滤。
 * 这样各公司差异不会渗透到 DataExtractor 的多个方法里。
 */
final class SectionTitleDetector {

    private SectionTitleDetector() {}

    /**
     * 是否属于"独立标签行"（标签短、几乎不含数字）。粗体/空数据行/冒号 的进一步识别都需要这个前置条件。
     */
    static boolean isStandaloneLabel(String label) {
        if (label == null) return false;
        if (label.length() >= 50) return false;
        int digitCount = 0;
        for (int i = 0; i < label.length(); i++) {
            if (Character.isDigit(label.charAt(i))) digitCount++;
        }
        return digitCount == 0 || (double) digitCount / label.length() < 0.2;
    }

    /**
     * 整行数据单元格是否都为空 —— MSFT 型 span 粗体识别失败时的兜底信号。
     */
    static boolean hasNoDataCells(TableRow row) {
        if (row == null || row.getCells() == null || row.getCells().isEmpty()) {
            return true;
        }
        for (TableCell c : row.getCells()) {
            String t = c.getText();
            if (t != null && !t.trim().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断一行是否是任意 metric 的"节标题"（不关心是哪个 metric）。
     * 三种命中方式：
     * <ol>
     *   <li>带冒号；或标签包含 "total"（BABA/GOOGL 格式）</li>
     *   <li>标签独立 + 行被识别为粗体（PDD 格式）</li>
     *   <li>标签独立 + 数据格全空（MSFT 格式的结构兜底）</li>
     * </ol>
     */
    static boolean isSectionTitleRow(TableRow row, String lowerLabel) {
        if (lowerLabel == null || lowerLabel.isEmpty()) return false;
        // 格式1: 带冒号 或 包含 total
        if (lowerLabel.endsWith(":") || lowerLabel.contains("total")) return true;
        if (!isStandaloneLabel(lowerLabel)) return false;
        // 格式2: 粗体
        if (row.isBold()) return true;
        // 格式3: 空数据行
        return hasNoDataCells(row);
    }
}
