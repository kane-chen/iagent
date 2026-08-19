package io.invest.iagent.rag.filing.retrieve.handler;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import io.invest.iagent.rag.filing.ingest.PeriodParser;
import io.invest.iagent.rag.filing.retrieve.FilingTagKeys;
import io.invest.iagent.rag.chatting.Chatter;
import io.invest.iagent.rag.model.TagCondition;
import io.invest.iagent.rag.model.TagFilter;
import io.invest.iagent.rag.retrieve.dto.ChatManage;
import io.invest.iagent.rag.retrieve.dto.PipelineContext;
import io.invest.iagent.rag.retrieve.handler.Handler;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 业务 handler #1：从查询中解析股票代码与明确财报周期，写入 {@code state.tagFilter}。
 * <p>
 * ticker 解析：LLM 优先（短 prompt 返回 JSON），失败回退正则；
 * 若调用方已通过预填 tagFilter 指定 ticker/period 则不覆盖。
 * 周期解析：仅处理显式周期（2026Q1/FY2025 等）；相对时间（"最新财报"）由
 * {@link FilingPeriodNormalizeHandler} 处理。
 */
@Service
@Slf4j
@Order(10)
public class FilingTagParseHandler implements Handler {

    // 港股 5 位数字（可带 .HK 后缀）、A 股 6 位数字、美股 2-5 位大写字母
    private static final Pattern TICKER_PATTERN =
            Pattern.compile("\\b(\\d{4,6})(?:\\.HK)?\\b|\\b([A-Z]{2,5})\\b");

    @Autowired
    private Chatter chatter;

    @Override
    public String name() {
        return "FilingTagParse";
    }

    @Override
    public void handle(PipelineContext ctx, ChatManage cm) {
        if (!FilingHandlerSupport.isFilingDomain(cm)) return;

        String query = cm.getState().getRewriteQuery() != null
                ? cm.getState().getRewriteQuery() : cm.getQuery();
        if (StringUtils.isBlank(query)) return;

        TagFilter filter = FilingHandlerSupport.mutableStateFilter(cm);

        // ticker：预填优先
        if (FilingHandlerSupport.findCondition(filter, FilingTagKeys.TICKER).isEmpty()) {
            String ticker = parseTicker(query);
            if (ticker != null) {
                filter.add(TagCondition.eq(FilingTagKeys.TICKER, ticker));
                log.debug("FilingKB parsed ticker={}", ticker);
            }
        }

        // 显式周期：预填优先（相对时间交给 PeriodNormalizeHandler）
        if (FilingHandlerSupport.findCondition(filter, FilingTagKeys.FISCAL_PERIOD).isEmpty()) {
            PeriodParser.ParsedPeriod parsed = PeriodParser.parse(query);
            if (parsed.period() != null) {
                filter.add(TagCondition.eq(FilingTagKeys.FISCAL_PERIOD, parsed.period()));
                log.debug("FilingKB parsed fiscal_period={}", parsed.period());
            }
        }
    }

    private String parseTicker(String query) {
        String fromLlm = parseTickerByLlm(query);
        if (fromLlm != null) return fromLlm;
        return parseTickerByRegex(query);
    }

    private String parseTickerByLlm(String query) {
        if (chatter == null) return null;
        try {
            String systemPrompt = """
                    你是股票代码抽取器。从用户问题中识别所询问的公司股票代码，输出 JSON：
                    {"ticker":"<代码>"}，代码大写、去除交易所后缀（如 00700、AAPL、600519）。
                    若问题未指明具体公司，输出 {"ticker":null}。只输出 JSON。""";
            String raw = chatter.chat(systemPrompt, query);
            if (StringUtils.isBlank(raw)) return null;
            JSONObject json = JSON.parseObject(raw);
            String ticker = json == null ? null : json.getString("ticker");
            return StringUtils.isBlank(ticker) ? null : ticker.trim().toUpperCase();
        } catch (Exception e) {
            log.debug("LLM ticker parse failed: {}", e.getMessage());
            return null;
        }
    }

    private String parseTickerByRegex(String query) {
        Matcher m = TICKER_PATTERN.matcher(query.toUpperCase());
        while (m.find()) {
            if (m.group(1) != null) {
                return m.group(1); // 数字代码（港股/A股）
            }
            String letters = m.group(2);
            // 排除常见英文停用词
            if (letters != null && !isStopWord(letters)) {
                return letters;
            }
        }
        return null;
    }

    private boolean isStopWord(String word) {
        return switch (word) {
            case "Q1", "Q2", "Q3", "Q4", "FY", "H1", "H2", "YOY", "QOQ",
                 "THE", "AND", "FOR", "HOW", "WHAT", "WHY", "CEO", "CFO",
                 "GDP", "EPS", "ROE", "ROA", "PE", "PB", "MAU", "DAU", "ARPU", "GMV" -> true;
            default -> false;
        };
    }
}
