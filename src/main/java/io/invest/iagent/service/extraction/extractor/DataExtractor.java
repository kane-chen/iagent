package io.invest.iagent.service.extraction.extractor;

import io.invest.iagent.service.extraction.mapper.MetricMapper;
import io.invest.iagent.service.extraction.model.*;
import io.invest.iagent.service.extraction.recognizer.SegmentRecognizer;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 数据提取器
 * 从财务表格中提取业务分部的财务指标数据
 */
public class DataExtractor {

    private static final Logger logger = LoggerFactory.getLogger(DataExtractor.class);

    private final SegmentRecognizer segmentRecognizer;
    private final MetricMapper metricMapper;

    public DataExtractor(SegmentRecognizer segmentRecognizer, MetricMapper metricMapper) {
        this.segmentRecognizer = segmentRecognizer;
        this.metricMapper = metricMapper;
    }

    /**
     * 从表格中提取分部财务数据
     */
    public List<Segment> extractSegmentData(FinancialTable table) {
        logger.info("Extracting segment data from table: {}", table.getTitle());

        // 1. 识别业务分部
        List<Segment> segments = segmentRecognizer.recognizeSegments(table);

        // 2. 为每个分部提取指标数据
        for (Segment segment : segments) {
            extractMetricsForSegment(segment, table);
            // 递归处理子分部
            extractMetricsForChildren(segment, table);
        }

        // 3. 从叶子节点向上累加数据，填充父节点数据
        aggregateChildMetrics(segments);

        // filter
        segments = filterByPeriodType(segments);
        logger.info("Extracted data for {} segments", segments.size());
        return segments;
    }

    private List<Segment> filterByPeriodType(List<Segment> segments){
        filterSegmentsByPeriodType(segments);
        return segments.stream()
                .filter(t-> Objects.nonNull(t.getSegmentCode()) || Objects.nonNull(t.getChildren()))
                .toList() ;
    }


    /**
     * 递归处理子分部
     */
    private void extractMetricsForChildren(Segment parent, FinancialTable table) {
        for (Segment child : parent.getChildren()) {
            extractMetricsForSegment(child, table);
            extractMetricsForChildren(child, table);
        }
    }

    /**
     * 为单个分部提取指标数据 - 提取所有周期的数据
     * 支持在同一表格中提取多种指标（如收入和营业利润在同一表格）
     */
    private void extractMetricsForSegment(Segment segment, FinancialTable table) {
        // 首先从表格标题判断可能的指标类型
        List<String> possibleMetrics = inferPossibleMetricsFromTable(table);
        if (possibleMetrics.isEmpty()) {
            logger.trace("Could not infer any metric type from table: {}", table.getTitle());
            return;
        }

        // 对每个可能的指标类型，尝试找到对应的行并提取数据
        for (String metricCode : possibleMetrics) {
            // 找到该指标对应的分部行
            TableRow row = findSegmentRowForMetric(segment, metricCode, table);
            if (row == null) {
                logger.trace("No row found for segment: {} metric: {} in table: {}",
                    segment.getSegmentName(), metricCode, table.getTitle());
                continue;
            }

            logger.trace("Found row for segment: {} metric: {} in table: {}, row label: {}, cells: {}",
                segment.getSegmentName(), metricCode, table.getTitle(), row.getLabel(), row.getCells().size());

            // 提取该指标的数值数据
            extractMetricDataForRow(segment, metricCode, row, table);
        }
    }

    /**
     * 为指定行和指标类型提取数值数据
     *
     * 核心思路：表头按顺序定义了多个"周期列"（如 Three Months 2023, Three Months 2024, Nine Months 2023, Nine Months 2024）
     * 数据行的有效数值按相同顺序对应这些周期列
     * 我们按出现顺序构建一个 周期列表，然后按出现顺序匹配数据行的有效数值
     */
    private void extractMetricDataForRow(Segment segment, String metricCode, TableRow row, FinancialTable table) {
        List<TableCell> cells = row.getCells();

        // 从表格标题中提取年份
        String title = table.getTitle() != null ? table.getTitle() : "";
        int titleYear = extractYearFromText(title);

        // 确定季度类型（Q1-Q4）作为默认周期后缀
        String quarterType = PeriodTypeUtil.determinePeriodType(table);
        if (StringUtils.isBlank(quarterType)) {
            quarterType = "Q3"; // 默认Q3
        }

        // 步骤1：构建有序的周期列表（按出现顺序）
        // 从表头扫描"Three Months / Nine Months / Year Ended"等周期分组标记
        // 以及每个分组下的年份顺序
        List<String> periodSequence = buildPeriodSequence(table, quarterType);

        if (periodSequence.isEmpty()) {
            // 没有识别到任何周期，回退到旧的逻辑（标题年份）
            if (titleYear > 0) {
                periodSequence.add((titleYear - 1) + quarterType);
                periodSequence.add(titleYear + quarterType);
            } else {
                logger.trace("No period sequence found for table: {}", table.getTitle());
                return;
            }
        }

        // 步骤2：从数据行中提取有效数值，按出现顺序对应周期列表
        List<TableCell> validNumericCells = new java.util.ArrayList<>();
        for (int i = 0; i < cells.size(); i++) {
            TableCell cell = cells.get(i);
            if (!cell.isNumeric()) {
                continue;
            }
            // 跳过百分比数据
            boolean isPercentage = false;
            if (cell.getText() != null && cell.getText().contains("%")) {
                isPercentage = true;
            }
            if (i + 1 < cells.size()) {
                String nextCellText = cells.get(i + 1).getText();
                if ("%".equals(nextCellText)) {
                    isPercentage = true;
                }
            }
            if (isPercentage) {
                continue;
            }
            validNumericCells.add(cell);
        }

        // 步骤3：按位置匹配 - 第N个有效数值对应第N个周期
        // 只保留季度类型的周期（跳过累计周期如 QTD9、H）
        int matchCount = Math.min(validNumericCells.size(), periodSequence.size());
        for (int idx = 0; idx < matchCount; idx++) {
            String period = periodSequence.get(idx);
            TableCell cell = validNumericCells.get(idx);

            // 跳过累计周期（QTD9 / H 等），只保留季度数据
            if (period.endsWith("QTD9") || period.endsWith("QTD6") || period.endsWith("H")) {
                continue;
            }

            // 如果这个周期的指标已经存在，跳过（避免重复）
            if (segment.getMetric(metricCode, period) != null) {
                logger.trace("Metric {} for period '{}' already exists for segment: {}",
                        metricCode, period, segment.getSegmentName());
                continue;
            }

            // 为每个周期创建独立的指标
            SegmentMetric metric = createMetricWithPeriod(metricCode, cell, table, period);
            segment.addMetric(metric);
            logger.debug("Extracted metric for {}: {} = {} (period: {}, table: {})",
                    segment.getSegmentName(), metricCode, cell.getNumericValue(),
                    period, table.getTableId());
        }
    }

    /**
     * 从表格表头按出现顺序构建周期列表
     * 例如表头是：
     *   行0: Three Months Ended | Nine Months Ended
     *   行2: 2023 | 2024 | 2023 | 2024
     * 则返回: [2023Q3, 2024Q3, 2023QTD9, 2024QTD9]
     *
     * 默认周期类型为传入的 defaultQuarter（如Q3）
     */
    private List<String> buildPeriodSequence(FinancialTable table, String defaultQuarter) {
        List<String> periodSequence = new java.util.ArrayList<>();

        // 1) 扫描表头前5行，按出现顺序收集"周期分组标记"列表和"年份"列表
        //    周期分组列表按出现位置排序 [{列, 后缀}, ...]
        //    年份列表按出现位置排序 [{列, 年份}, ...]
        List<int[]> groupColumns = new java.util.ArrayList<>(); // [列, 后缀index]
        List<String> groupSuffixes = new java.util.ArrayList<>(); // suffix序列
        List<int[]> yearColumns = new java.util.ArrayList<>(); // [列, 年份]

        int headerRowEnd = Math.min(5, table.getRows().size());
        for (int r = 0; r < headerRowEnd; r++) {
            TableRow headerRow = table.getRows().get(r);
            for (int c = 0; c < headerRow.getCells().size(); c++) {
                String cellText = headerRow.getCells().get(c).getText();
                if (cellText == null || cellText.trim().isEmpty()) {
                    continue;
                }
                String lowerText = cellText.toLowerCase();

                // 识别累计/季度分组标记
                String suffix = null;
                if (lowerText.contains("nine month") || lowerText.contains("9 month") ||
                    lowerText.contains("nine-month") || lowerText.contains("year to date") ||
                    lowerText.contains("ytd")) {
                    suffix = "QTD9";
                } else if (lowerText.contains("six month") || lowerText.contains("6 month") ||
                           lowerText.contains("six-month")) {
                    suffix = "QTD6";
                } else if (lowerText.contains("year ended") || lowerText.contains("twelve months") ||
                           lowerText.contains("12 months") || lowerText.contains("full year") ||
                           lowerText.contains("fiscal year")) {
                    suffix = "FY";
                } else if (lowerText.contains("three month") || lowerText.contains("3 month") ||
                           lowerText.contains("quarter")) {
                    suffix = defaultQuarter;
                }
                if (suffix != null) {
                    groupColumns.add(new int[]{c, groupSuffixes.size()});
                    groupSuffixes.add(suffix);
                }

                // 提取年份
                int year = extractYearFromText(cellText);
                if (year > 0) {
                    yearColumns.add(new int[]{c, year});
                }
            }
        }

        // 按列号升序排序
        yearColumns.sort((a, b) -> Integer.compare(a[0], b[0]));
        groupColumns.sort((a, b) -> Integer.compare(a[0], b[0]));

        // 2) 关键算法：按出现顺序分组年份
        //    将年份序列按顺序分组，每组的大小等于该分组下的年份数量
        //    例如：分组数=2 (Three Months, Nine Months)，年份数=4 (2023,2024,2023,2024)
        //    则每组有 4/2 = 2 个年份：第1组 [2023,2024]→Three Months，第2组 [2023,2024]→Nine Months

        if (groupSuffixes.isEmpty()) {
            // 没有分组标记，全部使用默认季度
            for (int[] yc : yearColumns) {
                periodSequence.add(yc[1] + defaultQuarter);
            }
            return periodSequence;
        }

        int numGroups = groupSuffixes.size();
        int numYears = yearColumns.size();

        if (numYears % numGroups == 0) {
            // 均匀分组
            int yearsPerGroup = numYears / numGroups;
            for (int g = 0; g < numGroups; g++) {
                String suffix = groupSuffixes.get(g);
                for (int y = 0; y < yearsPerGroup; y++) {
                    int year = yearColumns.get(g * yearsPerGroup + y)[1];
                    periodSequence.add(year + suffix);
                }
            }
        } else {
            // 不均匀分组：按列号位置匹配（落在哪个分组列之后）
            // 用 floorEntry 风格的匹配
            for (int[] yc : yearColumns) {
                int col = yc[0];
                int year = yc[1];
                String suffix = defaultQuarter;
                // 找到 col 之前最近的分组标记
                for (int[] gc : groupColumns) {
                    if (gc[0] <= col) {
                        suffix = groupSuffixes.get(gc[1]);
                    } else {
                        break;
                    }
                }
                periodSequence.add(year + suffix);
            }
        }

        return periodSequence;
    }

    /**
     * 找到分部对应的表格行（兼容老接口）
     */
    private TableRow findSegmentRow(Segment segment, FinancialTable table) {
        return findSegmentRowForMetric(segment, null, table);
    }

    /**
     * 找到分部和指定指标对应的表格行
     * 支持分节表格结构（如谷歌财报：先有Revenues标题和所有分部的收入，然后有Operating Income标题和所有分部的营业利润）
     */
    private TableRow findSegmentRowForMetric(Segment segment, String metricCode, FinancialTable table) {
        String segmentName = segment.getSegmentName();
        String segmentCode = segment.getSegmentCode();

        // 步骤1：找到指标对应的节标题位置
        int sectionHeaderRow = findMetricSectionHeaderRow(metricCode, table);
        logger.trace("Section header for metric '{}' found at row: {}", metricCode, sectionHeaderRow);

        // 步骤2：确定搜索范围
        int startRow = (sectionHeaderRow >= 0) ? sectionHeaderRow + 1 : 0;
        int endRow = table.getRows().size();

        // 步骤3：如果找到了节标题，需要找到下一个节标题的位置作为结束边界
        if (sectionHeaderRow >= 0) {
            int nextSectionHeader = findNextSectionHeaderRow(table, sectionHeaderRow + 1);
            if (nextSectionHeader >= 0) {
                endRow = nextSectionHeader;
            }
            logger.trace("Searching for segment '{}' in rows {} to {}", segmentName, startRow, endRow);
        }

        // 步骤4：在指定范围内搜索匹配的分部行
        for (int i = startRow; i < endRow && i < table.getRows().size(); i++) {
            TableRow row = table.getRows().get(i);
            if (row.getLabel() == null) continue;
            String lowerLabel = row.getLabel().toLowerCase().trim();

            // 检查是否匹配分部编码或名称（优先精确匹配）
            if (matchesSegment(lowerLabel, segmentCode, segmentName)) {
                logger.trace("Found row for segment '{}' metric '{}' at row {}: {}",
                    segmentName, metricCode, i, row.getLabel());
                return row;
            }
        }

        // 如果没有找到，尝试不使用节边界搜索（整个表格）
        if (sectionHeaderRow >= 0) {
            logger.trace("No match found in section, searching entire table for segment '{}'", segmentName);
            return findSegmentRowForMetric(segment, null, table);
        }

        return null;
    }

    /**
     * 检查标签是否匹配分部（支持编码、名称的精确匹配、前缀匹配、后缀匹配）
     */
    private boolean matchesSegment(String lowerLabel, String segmentCode, String segmentName) {
        // config match
        boolean configMatches = segmentRecognizer.match(lowerLabel, segmentCode);
        if (configMatches) {
            return true;
        }
        // 构建需要匹配的候选名称列表
        List<String> candidates = new java.util.ArrayList<>();
        if (segmentCode != null) {
            candidates.add(segmentCode.toLowerCase());
            candidates.add(segmentCode.replace("_", " ").toLowerCase());
        }
        if (segmentName != null) {
            candidates.add(segmentName.toLowerCase().trim());
        }

        String trimmedLowerLabel = lowerLabel.trim();

        // 匹配策略：按优先级尝试
        for (String candidate : candidates) {
            // 1. 精确匹配整个标签（最高优先级）
            if (trimmedLowerLabel.equals(candidate)) {
                return true;
            }
            // 2. 标签以分部名称结尾（如 "China commerce retail - Customer management" 匹配 "Customer management"）
            // 格式：父分部 - 子分部
            if (trimmedLowerLabel.endsWith(" - " + candidate)) {
                return true;
            }
            // 3. 标签以分部名称结尾，前面是连字符加空格
            if (trimmedLowerLabel.endsWith("- " + candidate)) {
                return true;
            }
            // 4. 标签以分部名称开头，后面有连字符和空格
            // 如 "- Direct sales and others" 匹配 "Direct sales and others"
            if (trimmedLowerLabel.startsWith("- " + candidate)) {
                return true;
            }
            // 5. 标签以分部名称开头（优先匹配更长的前缀，避免短前缀误匹配
            if (trimmedLowerLabel.startsWith(candidate + " ") ||
                trimmedLowerLabel.startsWith(candidate + "(") ||
                trimmedLowerLabel.startsWith(candidate + "-")) {
                return true;
            }
            // 6. 标签包含整个分部名称（前后有空格或边界）
            if (lowerLabel.contains(" " + candidate + " ") ||
                lowerLabel.startsWith(candidate + " ") ||
                lowerLabel.endsWith(" " + candidate)) {
                return true;
            }
        }

        // 6. 模糊匹配（必须匹配所有单词）
        // 只有当分部名称包含多个单词时才尝试模糊匹配
        if (segmentName != null) {
            String[] nameParts = segmentName.toLowerCase().split("\\s+");
            if (nameParts.length >= 2) {
                // 必须匹配所有单词才能认为是同一个分部
                int matchCount = 0;
                for (String part : nameParts) {
                    if (lowerLabel.contains(part)) {
                        matchCount++;
                    }
                }
                if (matchCount == nameParts.length) {
                    // 额外检查：避免通用词匹配导致的错误
                    // 检查是否包含 "international" 但分部名称不包含
                    if (lowerLabel.contains("international") &&
                        !segmentName.toLowerCase().contains("international")) {
                        return false;
                    }
                    // 检查是否包含 "china" 但分部名称不包含
                    if (lowerLabel.contains("china") &&
                        !segmentName.toLowerCase().contains("china")) {
                        return false;
                    }
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * 找到指标对应的节标题行位置
     */
    private int findMetricSectionHeaderRow(String metricCode, FinancialTable table) {
        if (metricCode == null) {
            return -1;
        }

        List<String> keywords = getMetricKeywords(metricCode);
        if (keywords.isEmpty()) {
            return -1;
        }

        // 搜索包含指标关键词的标题行
        for (int i = 0; i < table.getRows().size(); i++) {
            TableRow row = table.getRows().get(i);
            if (row.getLabel() == null) continue;
            String lowerLabel = row.getLabel().toLowerCase().trim();

            // 检查是否是指标节标题（包含关键词且以冒号结尾）
            for (String keyword : keywords) {
                if (lowerLabel.contains(keyword)) {
                    // 典型的节标题格式："Revenues:", "Operating income (loss):"
                    if (lowerLabel.endsWith(":") || lowerLabel.contains("total")) {
                        return i;
                    }
                    // 也可能是表格标题的一部分
                    if (table.getTitle() != null && table.getTitle().toLowerCase().contains(keyword)) {
                        return -1; // 指标在表格标题中，不需要节边界
                    }
                }
            }
        }

        return -1;
    }

    /**
     * 找到下一个节标题行的位置
     */
    private int findNextSectionHeaderRow(FinancialTable table, int startRow) {
        for (int i = startRow; i < table.getRows().size(); i++) {
            TableRow row = table.getRows().get(i);
            if (row.getLabel() == null) continue;
            String lowerLabel = row.getLabel().toLowerCase().trim();

            // 检查是否是另一个节的标题（以冒号结尾，或者包含指标关键词）
            if (lowerLabel.endsWith(":") &&
                (lowerLabel.contains("revenue") || lowerLabel.contains("income") ||
                 lowerLabel.contains("profit") || lowerLabel.contains("expense") ||
                 lowerLabel.contains("cost") || lowerLabel.contains("loss") ||
                 lowerLabel.contains("supplemental"))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 获取指标的关键词列表，用于行匹配
     */
    private List<String> getMetricKeywords(String metricCode) {
        List<String> keywords = new java.util.ArrayList<>();
        if (metricCode == null) {
            return keywords;
        }
        switch (metricCode) {
            case "REVENUE":
                keywords.add("revenue");
                keywords.add("revenues");
                keywords.add("收入");
                break;
            case "OPERATING_INCOME":
                keywords.add("operating");
                keywords.add("income");
                keywords.add("profit");
                keywords.add("利润");
                keywords.add("收益");
                break;
            case "ADJUSTED_EBITA":
                keywords.add("ebita");
                keywords.add("ebit");
                keywords.add("调整后");
                break;
        }
        return keywords;
    }

    /**
     * 从表格标题推断可能的所有指标类型
     * 支持同一表格包含多种指标的情况（如收入和营业利润在同一表格）
     */
    private List<String> inferPossibleMetricsFromTable(FinancialTable table) {
        String title = table.getTitle();
        String lowerTitle = title != null ? title.toLowerCase() : "";

        // 从行标签中识别节标题，例如 "Revenues:" 或 "Operating income (loss):"
        // 没有标题或标题无法推断时，回退到扫描节标题行
        boolean hasRevenueSection = false;
        boolean hasOperatingIncomeSection = false;
        boolean hasEbitaSection = false;
        for (TableRow r : table.getRows()) {
            String label = r.getLabel();
            if (label == null) continue;
            String ll = label.toLowerCase().trim();
            if (ll.endsWith(":") || ll.contains("total")) {
                if (ll.contains("revenue") || ll.contains("revenues")) {
                    hasRevenueSection = true;
                }
                if (ll.contains("operating income") || ll.contains("operating profit") ||
                    ll.contains("income from operations")) {
                    hasOperatingIncomeSection = true;
                }
                if (ll.contains("ebita")) {
                    hasEbitaSection = true;
                }
            }
        }

        List<String> metrics = new java.util.ArrayList<>();

        // 特殊处理包含"segment"或多个指标关键词的表格
        boolean isSegmentTable = lowerTitle.contains("segment");
        boolean hasRevenue = lowerTitle.contains("revenue") || lowerTitle.contains("revenues") || hasRevenueSection;
        boolean hasProfitability = lowerTitle.contains("profitability") || lowerTitle.contains("profit") ||
                                  lowerTitle.contains("operating income") || lowerTitle.contains("经营利润") ||
                                  hasOperatingIncomeSection;
        // 检查是否有更具体的利润指标关键词
        boolean hasAdjustedEbita = lowerTitle.contains("adjusted ebita") || lowerTitle.contains("调整后ebita")
                || lowerTitle.contains("经调整ebita") || lowerTitle.contains("ebita by segment") || hasEbitaSection;
        boolean hasEbita = lowerTitle.contains("ebita") && !hasAdjustedEbita;
        boolean hasEbitda = lowerTitle.contains("ebitda");
        boolean hasEbit = lowerTitle.contains("ebit") && !hasEbita && !hasEbitda;

        // 如果通过行扫描发现了多个指标节，或检测到任何节标题，按segment table处理
        if (isSegmentTable || hasRevenueSection || hasOperatingIncomeSection || hasEbitaSection) {
            // 分节表格：根据标题中的具体指标关键词来推断
            if (hasRevenue) {
                metrics.add("REVENUE");
            }
            // 优先匹配更具体的指标
            if (hasAdjustedEbita) {
                metrics.add("ADJUSTED_EBITA");
            } else if (hasEbitda) {
                metrics.add("EBITDA");
            } else if (hasEbita) {
                metrics.add("ADJUSTED_EBITA");
            } else if (hasEbit) {
                metrics.add("EBIT");
            } else if (hasProfitability) {
                metrics.add("OPERATING_INCOME");
            }
            // 如果是segment表格但没有明确的指标关键词，尝试所有常见指标
            if (metrics.isEmpty()) {
                metrics.add("REVENUE");
                metrics.add("OPERATING_INCOME");
                metrics.add("ADJUSTED_EBITA");
            }
            logger.trace("Segment table detected, possible metrics: {}", metrics);
            return metrics;
        }

        // 对于普通表格，按原来的方式处理

        // 优先检查更具体的指标

        // 检查是否包含 EBITA
        if (lowerTitle.contains("adjusted ebita") || lowerTitle.contains("经调整ebita") ||
            lowerTitle.contains("调整后ebita") || lowerTitle.contains("ebita by segment")) {
            metrics.add("ADJUSTED_EBITA");
        }

        // 检查是否包含 EBIT
        if (lowerTitle.contains("ebit") && !metrics.contains("EBIT")) {
            metrics.add("EBIT");
        }

        // 检查是否包含营业利润/经营利润
        if (lowerTitle.contains("operating income") || lowerTitle.contains("经营利润") ||
            lowerTitle.contains("营业利润")) {
            metrics.add("OPERATING_INCOME");
        }

        // 检查是否包含收入
        if (lowerTitle.contains("revenue") || lowerTitle.contains("收入") ||
            lowerTitle.contains("营收") || lowerTitle.contains("revenues")) {
            metrics.add("REVENUE");
        }

        // 如果没有识别到任何指标，尝试使用原方法
        if (metrics.isEmpty()) {
            String singleMetric = inferMetricFromTable(table);
            if (singleMetric != null) {
                metrics.add(singleMetric);
            }
        }

        logger.trace("Inferred possible metrics from table '{}': {}", title, metrics);
        return metrics;
    }

    /**
     * 根据表格标题推断单一指标类型（原方法保留）
     */
    private String inferMetricFromTable(FinancialTable table) {
        String title = table.getTitle();
        if (title == null) {
            return null;
        }

        String lowerTitle = title.toLowerCase();

        // EBITA - 优先匹配（更具体）
        if (lowerTitle.contains("adjusted ebita") || lowerTitle.contains("经调整ebita") ||
            lowerTitle.contains("调整后ebita") || lowerTitle.contains("ebita by segment")) {
            return "ADJUSTED_EBITA";
        }

        // 收入表 - 包含segment的收入表优先匹配
        if (lowerTitle.contains("revenue") || lowerTitle.contains("收入") ||
            lowerTitle.contains("营收") || lowerTitle.contains("revenues")) {
            return "REVENUE";
        }

        // EBITDA
        if (lowerTitle.contains("ebitda")) {
            return "EBITDA";
        }

        // EBIT
        if (lowerTitle.contains("ebit")) {
            return "EBIT";
        }

        // 经营利润
        if (lowerTitle.contains("operating income") || lowerTitle.contains("经营利润") ||
            lowerTitle.contains("营业利润")) {
            return "OPERATING_INCOME";
        }

        // 净利润
        if (lowerTitle.contains("net income") || lowerTitle.contains("净利润")) {
            return "NET_INCOME";
        }

        // 成本表
        if (lowerTitle.contains("cost") || lowerTitle.contains("成本")) {
            return "COST_OF_REVENUE";
        }

        // 费用表
        if (lowerTitle.contains("expense") || lowerTitle.contains("费用")) {
            return "OPERATING_EXPENSES";
        }

        logger.trace("Could not infer metric type from table title: {}", title);
        return null;
    }


    /**
     * 从表头提取周期信息（不含年份）
     */
    private String extractPeriodFromHeader(String header) {
        if (header == null || header.isEmpty()) {
            return "";
        }
        // 提取如："March 31" 或 "Year March 31"，移除冗余词
        String result = header.replaceAll("(?i)three months ended", "")
                .replaceAll("(?i)year ended", "")
                .replaceAll("(?i)for the", "")
                .replaceAll("(?i)ended", "")
                .replaceAll("(?i)as of", "")
                .replaceAll("\\b20\\d{2}\\b", "")  // 移除年份
                .trim();
        // 如果结果只是逗号或空，返回空
        if (result.replaceAll("[,\\s]+", "").isEmpty()) {
            return "";
        }
        return result;
    }

    /**
     * 从文本中提取年份信息
     * 支持格式：2025, FY2025, 2025Q1, March 31, 2025 等
     */
    private int extractYearFromText(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        // 使用正则表达式提取四位数年份
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\b(20\\d{2})\\b");
        java.util.regex.Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return 0;
    }

    /**
     * 创建指标对象（带周期）
     */
    private SegmentMetric createMetricWithPeriod(String metricCode, TableCell cell, FinancialTable table, String period) {
        SegmentMetric metric = new SegmentMetric();
        metric.setMetricCode(metricCode);
        metric.setPeriod(period);

        // 获取指标名称
        MetricDict dict = metricMapper.getMetricByCode(metricCode);
        if (dict != null) {
            metric.setMetricName(dict.getMetricName());
        }

        metric.setValue(cell.getNumericValue());
        metric.setCurrency(table.getCurrency());
        metric.setUnit(table.getUnit());
        metric.setSourceType("TABLE_EXTRACT");
        metric.setSourceLocation(table.getTableId());

        // 计算置信度
        int confidence = calculateConfidence(cell, table);
        metric.setConfidenceScore(confidence);

        return metric;
    }


    /**
     * 计算置信度
     */
    private int calculateConfidence(TableCell cell, FinancialTable table) {
        int confidence = 80; // 基础分

        // 如果数值是从括号负数解析的，扣5分
        if (cell.isParentheses()) {
            confidence -= 5;
        }

        // 如果表格有明确的单位，加5分
        if (table.getUnit() != null) {
            confidence += 5;
        }

        // 如果表格有明确的币种，加5分
        if (table.getCurrency() != null) {
            confidence += 5;
        }

        return Math.max(0, confidence);
    }

    /**
     * 从多个表格中提取并合并数据
     */
    public List<Segment> extractFromMultipleTables(List<FinancialTable> tables) {
        List<Segment> allSegments = new ArrayList<>();

        for (FinancialTable table : tables) {
            List<Segment> tableSegments = extractSegmentData(table);
            mergeSegments(allSegments, tableSegments);
        }

        // 过滤无效segment
        allSegments = allSegments.stream().filter(Objects::nonNull)
                .filter(t->StringUtils.isNotBlank(t.getSegmentCode())).toList();

        return allSegments;
    }

    /**
     * 合并分部数据
     */
    private void mergeSegments(List<Segment> target, List<Segment> source) {
        for (Segment sourceSegment : source) {
            Segment targetSegment = findSegmentByName(target, sourceSegment.getSegmentName());
            if (targetSegment == null) {
                target.add(sourceSegment);
            } else {
                // 合并指标（按指标代码和周期区分）
                for (SegmentMetric metric : sourceSegment.getMetrics()) {
                    if (targetSegment.getMetric(metric.getMetricCode(), metric.getPeriod()) == null) {
                        targetSegment.addMetric(metric);
                    }
                }
                // 递归合并子分部
                mergeSegments(targetSegment.getChildren(), sourceSegment.getChildren());
            }
        }
    }

    /**
     * 根据名称查找分部
     */
    private Segment findSegmentByName(List<Segment> segments, String name) {
        if (name == null) return null;
        for (Segment segment : segments) {
            if (name.equalsIgnoreCase(segment.getSegmentName())) {
                return segment;
            }
            Segment found = findSegmentByName(segment.getChildren(), name);
            if (found != null) return found;
        }
        return null;
    }

    /**
     * 从叶子节点向上累加指标数据，填充父节点数据
     * 当父节点没有数据但子节点有数据时，使用子节点数据的总和
     */
    private void aggregateChildMetrics(List<Segment> segments) {
        for (Segment segment : segments) {
            // 先递归处理子节点
            aggregateChildMetrics(segment.getChildren());

            // 如果当前节点没有指标，但子节点有，则累加子节点的指标
            if (segment.getMetrics().isEmpty() && !segment.getChildren().isEmpty()) {
                aggregateFromChildren(segment);
            }
        }
    }

    /**
     * 从子节点累加指标数据到父节点
     */
    private void aggregateFromChildren(Segment parent) {
        // 按指标代码和周期分组，累加子节点的数值
        java.util.Map<String, java.util.Map<String, Double>> aggregatedValues = new java.util.HashMap<>();
        java.util.Map<String, String> currencyMap = new java.util.HashMap<>();
        java.util.Map<String, String> unitMap = new java.util.HashMap<>();

        // 收集所有子节点的指标数据
        for (Segment child : parent.getChildren()) {
            for (SegmentMetric childMetric : child.getMetrics()) {
                String key = childMetric.getMetricCode() + "|" + childMetric.getPeriod();
                aggregatedValues.computeIfAbsent(key, k -> new java.util.HashMap<>())
                        .merge("value", childMetric.getValue(), Double::sum);

                // 记录币种和单位（假设所有子节点一致）
                if (childMetric.getCurrency() != null) {
                    currencyMap.put(childMetric.getMetricCode(), childMetric.getCurrency());
                }
                if (childMetric.getUnit() != null) {
                    unitMap.put(childMetric.getMetricCode(), childMetric.getUnit());
                }
            }
        }

        // 创建累加后的指标
        for (java.util.Map.Entry<String, java.util.Map<String, Double>> entry : aggregatedValues.entrySet()) {
            String[] parts = entry.getKey().split("\\|", 2);
            String metricCode = parts[0];
            String period = parts.length > 1 ? parts[1] : "";
            Double totalValue = entry.getValue().get("value");

            if (totalValue != null) {
                SegmentMetric aggregatedMetric = new SegmentMetric();
                aggregatedMetric.setMetricCode(metricCode);
                aggregatedMetric.setMetricName(getMetricName(metricCode));
                aggregatedMetric.setValue(totalValue);
                aggregatedMetric.setPeriod(period);
                aggregatedMetric.setCurrency(currencyMap.get(metricCode));
                aggregatedMetric.setUnit(unitMap.get(metricCode));
                aggregatedMetric.setSourceType("AGGREGATED");
                aggregatedMetric.setSourceLocation("aggregated from children");
                aggregatedMetric.setConfidenceScore(70); // 累加数据置信度稍低

                parent.addMetric(aggregatedMetric);

                logger.debug("Aggregated metric for {}: {} = {} (period: {})",
                        parent.getSegmentName(), metricCode, totalValue, period);
            }
        }
    }

    /**
     * 根据指标代码获取指标名称
     */
    private String getMetricName(String metricCode) {
        MetricDict dict = metricMapper.getMetricByCode(metricCode);
        return dict != null ? dict.getMetricName() : metricCode;
    }

    /**
     * 按配置的 includePeriodTypes 过滤分部的指标周期
     * 递归处理所有子分部
     */
    private void filterSegmentsByPeriodType(List<Segment> segments) {
        List<String> includePeriodTypes = segmentRecognizer.getCompanyConfig().getIncludePeriodTypes();
        if(CollectionUtils.isEmpty(includePeriodTypes)){
            return ;
        }
        for (Segment segment : segments) {
            filterSegmentMetricsByPeriodType(segment, includePeriodTypes);
            // 递归处理子分部
            if (segment.getChildren() != null && !segment.getChildren().isEmpty()) {
                filterSegmentsByPeriodType(segment.getChildren());
            }
        }
    }

    /**
     * 过滤单个分部的指标，只保留配置中指定的周期类型
     */
    private void filterSegmentMetricsByPeriodType(Segment segment, List<String> includePeriodTypes) {
        if (segment.getMetrics() == null || segment.getMetrics().isEmpty()) {
            return;
        }

        List<SegmentMetric> filteredMetrics = segment.getMetrics().stream()
                .filter(metric -> {
                    String period = metric.getPeriod();
                    if (StringUtils.isBlank(period)) {
                        return true; // 保留无周期的指标
                    }
                    // 从周期字符串中提取类型部分（如 "2025FY" -> "FY", "2024Q1" -> "Q1"）
                    String periodType = extractPeriodType(period);
                    return includePeriodTypes.contains(periodType);
                })
                .collect(java.util.stream.Collectors.toList());

        int removedCount = segment.getMetrics().size() - filteredMetrics.size();
        if (removedCount > 0) {
            logger.debug("Filtered {} metrics for segment {} by period types: {}",
                    removedCount, segment.getSegmentCode(), includePeriodTypes);
        }

        segment.setMetrics(filteredMetrics);
    }

    /**
     * 从周期字符串中提取周期类型
     * 例如: "2025FY" -> "FY", "2024Q1" -> "Q1", "2023H1" -> "H1"
     */
    private String extractPeriodType(String period) {
        if (StringUtils.isBlank(period)) {
            return "";
        }
        // 匹配 FY, Q1-Q4, H1-H2 后缀
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("(FY|Q[1-4]|H[12])$", java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher matcher = pattern.matcher(period.trim());
        if (matcher.find()) {
            return matcher.group(1).toUpperCase();
        }
        return "";
    }

}
