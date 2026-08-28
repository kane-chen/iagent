package io.invest.iagent.rag.filing.retrieve.handler;

import io.invest.iagent.rag.filing.config.FilingKbProperties;
import io.invest.iagent.rag.filing.model.FiscalPeriod;
import io.invest.iagent.rag.filing.retrieve.FilingTagKeys;
import io.invest.iagent.rag.retrieve.dto.PipelineContext;
import io.invest.iagent.rag.retrieve.dto.SearchResult;
import io.invest.iagent.rag.retrieve.handler.RerankHandler;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 财报周期感知重排：在父类 {@link RerankHandler} 相关性重排的基础上叠加"财报周期因子"。
 * <p>
 * 多季度查询（如"最近 8 个季度的资本开支"）时，相关性排序纯按语义打分，最近一期的片段
 * 往往密集霸榜，top-K 截断后其它周期被整体丢失。本 handler 在 {@link #postRerank} 中
 * 按片段的 {@code fiscal_period} 标签分桶：桶内保持父类的相关性顺序，桶间按周期新旧
 * （最近优先）轮转交织（round-robin）。交织后列表自上而下均匀覆盖各周期——前 N 位即覆盖
 * N 个不同周期，使随后的 topK 截断保证各周期均有代表。
 * <ul>
 *   <li>仅 filing 域、候选片段覆盖 ≥2 个不同周期时生效；单周期查询退化为父类相关性序（无副作用）。</li>
 *   <li>无 fiscal_period 标签的片段归入"无周期桶"，轮转时排在所有真实周期之后，不挤占周期覆盖。</li>
 * </ul>
 */
@Service
@Slf4j
public class FilingRerankHandler extends RerankHandler {

    /** 无 fiscal_period 标签片段的兜底桶 key，轮转时排在所有真实周期之后 */
    private static final String NO_PERIOD = "__NO_PERIOD__";

    @Autowired
    private FilingKbProperties properties;

    @Override
    public String name() {
        return "FilingRerank";
    }

    /**
     * 在父类相关性重排结果之上，按财报周期轮转交织。
     *
     * @param reranked 父类 reranker 输出的全量候选（已按相关性降序、score 为组合分）
     * @return 周期交织后的列表；不满足生效条件时原样返回
     */
    @Override
    protected List<SearchResult> postRerank(PipelineContext context, List<SearchResult> reranked) {
        // 仅 filing 域、且开启周期因子时生效；其余场景保持父类相关性排序
        if (!FilingHandlerSupport.isFilingDomain(context) || context.ignoreRetrieval()) {
            return reranked;
        }
        if (!properties.getSearch().isPeriodRerankEnabled()
                || reranked == null || reranked.size() < 2) {
            return reranked;
        }

        // 1. 按财报周期分桶（桶内保持父类重排后的相关性顺序）
        Map<String, List<SearchResult>> buckets = new LinkedHashMap<>();
        for (SearchResult r : reranked) {
            buckets.computeIfAbsent(periodOf(r), k -> new ArrayList<>()).add(r);
        }
        long periodCount = buckets.keySet().stream().filter(k -> !NO_PERIOD.equals(k)).count();
        if (periodCount < 2) {
            // 单周期：周期因子无意义，保持相关性排序
            log.debug("FilingRerank skip: only {} distinct period(s) in candidates", periodCount);
            return reranked;
        }

        // 2. 桶间顺序：真实周期按新旧降序（最近优先），无周期标签片段垫底
        List<String> orderedKeys = new ArrayList<>(buckets.keySet());
        orderedKeys.remove(NO_PERIOD);
        orderedKeys.sort(Comparator.comparingInt(this::sortKeyOf).reversed());
        if (buckets.containsKey(NO_PERIOD)) {
            orderedKeys.add(NO_PERIOD);
        }

        // 3. 轮转交织：每轮依次取各桶当前最相关片段，使前 N 位覆盖 N 个周期，
        //    随后 topK 截断时各周期均有代表，避免单一周期霸榜导致其它周期丢失
        Map<String, Deque<SearchResult>> deques = new LinkedHashMap<>();
        for (String key : orderedKeys) {
            deques.put(key, new ArrayDeque<>(buckets.get(key)));
        }
        List<SearchResult> interleaved = new ArrayList<>(reranked.size());
        boolean progress = true;
        while (progress) {
            progress = false;
            for (String key : orderedKeys) {
                SearchResult r = deques.get(key).pollFirst();
                if (r != null) {
                    interleaved.add(r);
                    progress = true;
                }
            }
        }
        log.debug("FilingRerank: interleaved {} chunks across {} period(s)", interleaved.size(), periodCount);
        return interleaved;
    }

    /** 取片段的财报周期标签；缺失归入兜底桶 */
    private String periodOf(SearchResult r) {
        String p = r.getTags() == null ? null : r.getTags().get(FilingTagKeys.FISCAL_PERIOD);
        return StringUtils.isBlank(p) ? NO_PERIOD : p.trim();
    }

    /** 周期排序键（越新越大）；无法解析的真实周期排到最后 */
    private int sortKeyOf(String canonical) {
        FiscalPeriod fp = FiscalPeriod.parse(canonical);
        return fp == null ? Integer.MIN_VALUE : fp.sortKey();
    }
}
