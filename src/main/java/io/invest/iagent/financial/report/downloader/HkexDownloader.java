package io.invest.iagent.financial.report.downloader;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.invest.iagent.financial.report.enums.Market;
import io.invest.iagent.financial.report.enums.ReportType;
import io.invest.iagent.financial.report.model.ReportMeta;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 港交所披露易（HKEX）下载器。
 * 注意：
 * 1. titleSearchServlet 只支持 GET；
 * 2. stockId 参数不是股票代码，而是港交所内部数字 ID（如 00700 腾讯 -> 7609），
 *    需先通过 prefix.do（JSONP 接口）按代码查询；
 * 3. 返回的 result 字段是二次编码的 JSON 字符串，需再解析一次；
 * 4. 港股主板不强制披露正式季报：腾讯等公司的 Q1/Q3 财报以「季度業績」公告形式发布
 *    （公告及通告类 t1code=10000，业绩分组 t2Gcode=3，t2code=13600）；
 *    GEM 等公司的正式季报才在「財務報表」类 t1code=40000 下（40300），季报需两类都查。
 */
@Service
public class HkexDownloader extends ReportDownloader {

    private static final String PREFIX_API = "https://www1.hkexnews.hk/search/prefix.do";
    private static final String SEARCH_API = "https://www1.hkexnews.hk/search/titleSearchServlet.do";
    private static final String SITE = "https://www1.hkexnews.hk";

    // 财务报表类公告(t1code=40000)下的子类：40100 年报 / 40200 中期报告 / 40300 季报
    private static final String T1_FINANCIAL = "40000";
    private static final String T2_ANNUAL_REPORT = "40100";    // 年报（完整报告，约 4 月）
    private static final String T2_INTERIM_REPORT = "40200";   // 中期/半年度报告（完整报告，约 9 月）
    private static final String T2_QUARTERLY_REPORT = "40300"; // 正式季报（GEM 等）
    // 公告及通告类(t1code=10000)下「业绩」分组(t2Gcode=3)：业绩公告早于完整报告 1~2 个月发布，
    // 已含分部数据——13300 年度业绩(约 3 月) / 13400 中期业绩(约 8 月) / 13600 季度业绩(Q1/Q3)
    private static final String T1_ANNOUNCE = "10000";
    private static final String T2G_RESULTS = "3";
    private static final String T2_ANNUAL_RESULTS = "13300";     // 年度业绩公告
    private static final String T2_INTERIM_RESULTS = "13400";    // 中期(半年度)业绩公告
    private static final String T2_QUARTERLY_RESULTS = "13600";  // 季度业绩公告（主板 Q1/Q3）

    /** 披露易公告分类参数：t1code / t2Gcode / t2code */
    private record Category(String t1, String t2G, String t2) {}

    /**
     * 各报告类型对应的披露易分类。年报/中报同时查「业绩公告」（早 1~2 个月，含分部数据）
     * 与「完整报告」两类；季报同时查季度业绩公告和正式季报两个分类。
     * 同一公告可能被多个分类命中，由 {@link #fetchMetaList} 按 FILE_LINK 去重。
     */
    private static List<Category> categoriesOf(ReportType type) {
        return switch (type) {
            case ANNUAL   -> List.of(
                    new Category(T1_ANNOUNCE, T2G_RESULTS, T2_ANNUAL_RESULTS),     // 年度业绩公告（3 月）
                    new Category(T1_FINANCIAL, T1_FINANCIAL, T2_ANNUAL_REPORT));   // 年报（4 月）
            case INTERIM  -> List.of(
                    new Category(T1_ANNOUNCE, T2G_RESULTS, T2_INTERIM_RESULTS),    // 中期业绩公告（8 月，Q2 数据最早来源）
                    new Category(T1_FINANCIAL, T1_FINANCIAL, T2_INTERIM_REPORT));  // 中期报告（9 月）
            case QUARTERLY -> List.of(
                    new Category(T1_ANNOUNCE, T2G_RESULTS, T2_QUARTERLY_RESULTS),  // 季度业绩公告（主板）
                    new Category(T1_FINANCIAL, T1_FINANCIAL, T2_QUARTERLY_REPORT));// 正式季报（GEM 等）
        };
    }

    private static final DateTimeFormatter DATE_TIME_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Override
    public Market supportMarket() {
        return Market.HK ;
    }

    @Override
    public List<ReportMeta> fetchMetaList(String stockCode, ReportType type,int startYear,int endYear) {
        List<ReportMeta> list = new ArrayList<>();

        try {
            String stockId = resolveStockId(stockCode);
            Set<String> seenLinks = new HashSet<>();   // 按 FILE_LINK 去重（多个分类可能命中同一公告）
            for (Category cat : categoriesOf(type)) {
                String url = SEARCH_API + "?" +
                        "sortDir=0&sortByOptions=DateTime&category=0&market=SEHK" +
                        "&stockId=" + stockId + "&documentType=-1" +
                        "&t1code=" + cat.t1() + "&t2Gcode=" + cat.t2G() + "&t2code=" + cat.t2() +
                        "&rowRange=1-30&lang=ZH" +
                        "&fromDate=" + startYear + "0101&toDate=" + endYear + "1231&title=";

                JSONObject json = JSON.parseObject(getText(url, randomUA()));
                String result = json.getString("result");   // result 是二次编码的 JSON 字符串
                if (result == null || result.isBlank()) continue;
                JSONArray arr = JSON.parseArray(result);

                for (int i = 0; i < arr.size(); i++) {
                    JSONObject doc = arr.getJSONObject(i);
                    String link = doc.getString("FILE_LINK");
                    if (!seenLinks.add(link)) continue;   // 跳过重复公告
                    ReportMeta m = new ReportMeta();
                    m.title = stripHtml(doc.getString("TITLE"));
                    // DATE_TIME 格式为 dd/MM/yyyy HH:mm（港交所时间）
                    LocalDate date = LocalDateTime.parse(doc.getString("DATE_TIME"), DATE_TIME_FMT).toLocalDate();
                    m.publishDate = date.format(DATE_FMT);
                    m.pdfUrl = SITE + link;
                    m.localPath = String.format("HK/%s/%s_%s_%s.pdf",
                            stockCode, stockCode, m.publishDate, type.name());
                    list.add(m);
                    throttle();
                }
            }
        } catch (Exception e) {
            list.clear();
            ReportMeta m = new ReportMeta();
            m.errorMsg = "HKEX 查询失败: " + e;
            list.add(m);
        }
        return list;
    }

    /**
     * 股票代码 -> 港交所内部 stockId（如 00700 -> 7609）。
     * prefix.do 是 JSONP 接口，响应形如 callback({...}); 需剥掉函数包裹。
     */
    private String resolveStockId(String stockCode) throws Exception {
        String code = normalizeCode(stockCode);
        String url = PREFIX_API + "?lang=ZH&type=A&market=SEHK&callback=cb&name="
                + URLEncoder.encode(code, StandardCharsets.UTF_8);
        String body = getText(url, randomUA());
        int start = body.indexOf('(');
        int end = body.lastIndexOf(')');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("prefix.do 响应格式异常: " + body);
        }
        JSONArray stockInfo = JSON.parseObject(body.substring(start + 1, end)).getJSONArray("stockInfo");
        if (stockInfo == null || stockInfo.isEmpty()) {
            throw new IllegalArgumentException("披露易未找到股票代码: " + stockCode);
        }
        return stockInfo.getJSONObject(0).getString("stockId");
    }

    /** 港股代码统一补零为 5 位（700 -> 00700） */
    private static String normalizeCode(String stockCode) {
        try {
            return String.format("%05d", Integer.parseInt(stockCode.trim()));
        } catch (NumberFormatException e) {
            return stockCode;
        }
    }

    /** 去掉字段中的 HTML（如 STOCK_NAME 里的 <br/>） */
    private static String stripHtml(String s) {
        return s == null ? "" : s.replaceAll("(?i)<br\\s*/?>", " ").replaceAll("<[^>]+>", "").trim();
    }
}
