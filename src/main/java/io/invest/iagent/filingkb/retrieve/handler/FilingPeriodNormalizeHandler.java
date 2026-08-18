package io.invest.iagent.filingkb.retrieve.handler;

import io.invest.iagent.filingkb.config.FilingKbProperties;
import io.invest.iagent.filingkb.model.FiscalPeriod;
import io.invest.iagent.filingkb.retrieve.FilingTagKeys;
import io.invest.iagent.rag.model.TagCondition;
import io.invest.iagent.rag.model.TagFilter;
import io.invest.iagent.rag.repository.ChunkRepository;
import io.invest.iagent.rag.retrieve.dto.ChatManage;
import io.invest.iagent.rag.retrieve.dto.PipelineContext;
import io.invest.iagent.rag.retrieve.enums.EventType;
import io.invest.iagent.rag.retrieve.handler.Handler;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 业务 handler #3：把相对时间表达（最新财报、去年同期、近 N 期、上季度/环比）
 * 归一化为实际存在的 {@code fiscal_period IN (...)} 列表。
 * <p>
 * 周期范围由应用层枚举（SQL 不做 >= / BETWEEN）。
 * 仅在查询包含相对时间线索、且未显式指定周期时运行。
 */
@Slf4j
@Order(20)
public class FilingPeriodNormalizeHandler implements Handler {

    private static final Pattern LATEST = Pattern.compile("最新财报|最新一期|最近一期|最新季报|最新年报|最新的");
    private static final Pattern YEAR_AGO = Pattern.compile("去年同期|同比");
    private static final Pattern PREVIOUS = Pattern.compile("上季度|上一季|环比|前一季");
    private static final Pattern LAST_N = Pattern.compile("(?:最近|近|过去)(\\d+)(?:个季度|期|季)");

    @Autowired
    private ChunkRepository chunkRepository;
    @Autowired
    private FilingKbProperties properties;


    @Override
    public List<EventType> activationEvents() {
        return Collections.singletonList(EventType.QUERY_UNDERSTAND);
    }

    @Override
    public void onEvent(PipelineContext ctx, EventType eventType, ChatManage cm) {
        if (!FilingHandlerSupport.isFilingDomain(cm)) return;

        String query = cm.getState().getRewriteQuery() != null
                ? cm.getState().getRewriteQuery() : cm.getQuery();
        if (StringUtils.isBlank(query)) return;

        // 显式周期已由 TagParseHandler 解析，不做相对归一化
        TagFilter filter = cm.getState().tagFilter != null ? cm.getState().tagFilter : cm.getRequest().tagFilter;
        if (filter != null && FilingHandlerSupport.findCondition(filter, FilingTagKeys.FISCAL_PERIOD).isPresent()) {
            return;
        }

        RelativeIntent intent = detectIntent(query);
        if (intent == null) return;

        // scope：带 ticker 的已有条件（拷贝，避免污染原 filter 作为 scope）
        TagFilter scope = buildTickerScope(filter);
        List<FiscalPeriod> available = loadAvailablePeriods(scope);
        if (available.isEmpty()) {
            log.debug("FilingKB period normalize: no available periods in KB (ticker scope={})", scope);
            return;
        }
        FiscalPeriod latest = available.get(available.size() - 1); // 已升序

        List<String> resolved = switch (intent.kind()) {
            case LATEST -> List.of(latest.canonical());
            case PREVIOUS -> latest.previous() != null
                    ? List.of(latest.previous().canonical()) : List.of(latest.canonical());
            case YEAR_AGO -> latest.yearAgo() != null
                    ? List.of(latest.yearAgo().canonical()) : List.of(latest.canonical());
            case LAST_N -> latestN(available, intent.n());
        };

        TagFilter stateFilter = FilingHandlerSupport.mutableStateFilter(cm);
        // 移除可能存在的空占位，再写入枚举结果
        stateFilter.add(resolved.size() == 1
                ? TagCondition.eq(FilingTagKeys.FISCAL_PERIOD, resolved.get(0))
                : TagCondition.in(FilingTagKeys.FISCAL_PERIOD, resolved));
        log.debug("FilingKB normalized period intent={} -> {}", intent.kind(), resolved);
    }

    private List<FiscalPeriod> loadAvailablePeriods(TagFilter scope) {
        List<String> values;
        try {
            values = chunkRepository.findTagValues(properties.getKnowledgeBaseId(), FilingTagKeys.FISCAL_PERIOD, scope);
        } catch (Exception e) {
            log.warn("FilingKB findTagValues failed: {}", e.getMessage());
            return List.of();
        }
        List<FiscalPeriod> periods = new ArrayList<>();
        for (String v : values) {
            FiscalPeriod fp = FiscalPeriod.parse(v);
            if (fp != null) periods.add(fp);
        }
        Collections.sort(periods);
        return periods;
    }

    private List<String> latestN(List<FiscalPeriod> availableAsc, int n) {
        int count = Math.max(1, Math.min(n, availableAsc.size()));
        List<String> out = new ArrayList<>(count);
        for (int i = availableAsc.size() - count; i < availableAsc.size(); i++) {
            out.add(availableAsc.get(i).canonical());
        }
        return out;
    }

    private TagFilter buildTickerScope(TagFilter filter) {
        if (filter == null) return null;
        TagCondition ticker = FilingHandlerSupport.findCondition(filter, FilingTagKeys.TICKER).orElse(null);
        return ticker == null ? null : TagFilter.of(ticker);
    }

    private RelativeIntent detectIntent(String query) {
        Matcher m = LAST_N.matcher(query);
        if (m.find()) {
            return new RelativeIntent(IntentKind.LAST_N, Integer.parseInt(m.group(1)));
        }
        if (YEAR_AGO.matcher(query).find()) return new RelativeIntent(IntentKind.YEAR_AGO, 0);
        if (PREVIOUS.matcher(query).find()) return new RelativeIntent(IntentKind.PREVIOUS, 0);
        if (LATEST.matcher(query).find()) return new RelativeIntent(IntentKind.LATEST, 0);
        return null;
    }

    private enum IntentKind { LATEST, PREVIOUS, YEAR_AGO, LAST_N }

    private record RelativeIntent(IntentKind kind, int n) {}
}
