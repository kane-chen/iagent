package io.invest.iagent.filingkb;

import io.invest.iagent.filingkb.ingest.PeriodParser;
import io.invest.iagent.filingkb.model.FilingChunk;
import io.invest.iagent.filingkb.retrieve.FilingTagKeys;
import io.invest.iagent.rag.KnowledgeService;
import io.invest.iagent.rag.model.RetrieveRequest;
import io.invest.iagent.rag.model.RetrieveResultItem;
import io.invest.iagent.rag.model.TagCondition;
import io.invest.iagent.rag.model.TagFilter;
import io.invest.iagent.rag.retrieve.enums.RetrieveMode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 财报知识检索服务：构建 domain="filing" 的检索请求，
 * 经通用 RAG pipeline（含 filingkb 业务 handler）返回带标签与引用的片段。
 * <p>本服务不做 LLM 答案合成，片段交由 BossAgent/qaAgent 组织最终答案。
 */
@Slf4j
public class FilingKbQaService {

    private final KnowledgeService knowledgeService;
    private final FilingKbProperties properties;

    public FilingKbQaService(KnowledgeService knowledgeService, FilingKbProperties properties) {
        this.knowledgeService = knowledgeService;
        this.properties = properties;
    }

    /**
     * 检索财报片段。
     *
     * @param question 用户问题
     * @param ticker   调用方已明确的股票代码（可空，空则由 TagParseHandler 从问题解析）
     * @param period   调用方已明确的财报周期（可空，支持 2026Q1/FY2025 等）
     * @param topK     返回片段数，&lt;=0 取配置默认值
     */
    public List<FilingChunk> ask(String question, String ticker, String period, int topK) {
        if (StringUtils.isBlank(question)) {
            throw new IllegalArgumentException("question is required");
        }
        int limit = topK > 0 ? topK : properties.getSearch().getDefaultTopK();

        TagFilter tagFilter = buildPrefillFilter(ticker, period);

        RetrieveRequest request = RetrieveRequest.builder()
                .query(question)
                .knowledgeBaseIds(List.of(properties.getKnowledgeBaseId()))
                .retrieveMode(RetrieveMode.HYBRID)
                .enableRewrite(true)
                .rerankTopK(limit)
                .tagFilter(tagFilter)
                .domain(FilingTagKeys.DOMAIN)
                .build();

        List<RetrieveResultItem> items = knowledgeService.retrieve(request);
        List<FilingChunk> chunks = new ArrayList<>();
        int i = 1;
        for (RetrieveResultItem item : items) {
            Map<String, String> tags = item.getTags();
            chunks.add(FilingChunk.builder()
                    .chunkId(item.getId())
                    .score(item.getScore())
                    .content(item.getContent())
                    .tags(tags)
                    .citation(buildCitation(i, item))
                    .build());
            i++;
        }
        log.debug("FilingKB ask returned {} chunks for question: {}", chunks.size(), question);
        return chunks;
    }

    /**
     * 构造调用方显式指定的预填标签过滤（ticker EQ / period IN）。
     * 未指定的维度留给 pipeline handler 在运行时解析补充。
     */
    private TagFilter buildPrefillFilter(String ticker, String period) {
        TagFilter filter = TagFilter.builder().build();
        if (StringUtils.isNotBlank(ticker)) {
            filter.add(TagCondition.eq(FilingTagKeys.TICKER, ticker.trim().toUpperCase(Locale.ROOT)));
        }
        if (StringUtils.isNotBlank(period)) {
            // period 可能是一个明确区间（如 "2025Q1-2025Q3"），v1 优先尝试解析为单值；
            // 范围枚举由 FilingPeriodNormalizeHandler 负责相对时间表达。
            PeriodParser.ParsedPeriod parsed = PeriodParser.parse(period);
            if (parsed.period() != null) {
                filter.add(TagCondition.eq(FilingTagKeys.FISCAL_PERIOD, parsed.period()));
            }
        }
        return filter.isEmpty() ? null : filter;
    }

    /**
     * 引用格式：[Cn] TICKER YEAR PERIOD heading。
     * v1 不含页码（PdfTextStripper 未保留页边界，后续在 reader 内按页追踪 offset 增强）。
     */
    private String buildCitation(int index, RetrieveResultItem item) {
        // 优先使用 pipeline 中 FilingCitationHandler 产出的引用（编号对应最终 top-K）
        if (item.getMetadata() != null) {
            String produced = item.getMetadata().get("citation");
            if (StringUtils.isNotBlank(produced)) {
                return produced;
            }
        }
        Map<String, String> tags = item.getTags();
        StringBuilder sb = new StringBuilder("[C").append(index).append("]");
        if (tags != null) {
            appendTag(sb, tags.get(FilingTagKeys.TICKER));
            appendTag(sb, tags.get(FilingTagKeys.FISCAL_YEAR));
            appendTag(sb, tags.get(FilingTagKeys.FISCAL_PERIOD));
            String heading = tags.get(FilingTagKeys.HEADING);
            if (StringUtils.isBlank(heading)) {
                heading = item.getMetadata() != null ? item.getMetadata().get("context_header") : null;
            }
            if (StringUtils.isNotBlank(heading)) {
                sb.append(' ').append(heading);
            }
        }
        return sb.toString();
    }

    private void appendTag(StringBuilder sb, String value) {
        if (StringUtils.isNotBlank(value)) {
            sb.append(' ').append(value);
        }
    }
}
