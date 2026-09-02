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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 巨潮资讯网（A股）下载器。
 * 公告查询：POST /new/hisAnnouncement/query，stock 参数必须带 orgId（如 600519,gssh0600519），
 * orgId 通过 /new/information/topSearch/query 按代码查询。
 */
@Service
public class CninfoDownloader extends ReportDownloader {

    private static final String SEARCH_API = "http://www.cninfo.com.cn/new/information/topSearch/query";
    private static final String QUERY_API = "http://www.cninfo.com.cn/new/hisAnnouncement/query";
    private static final String PDF_BASE = "https://static.cninfo.com.cn/";
    private static final String REFERER = "http://www.cninfo.com.cn/new/commonUrl?url=disclosure/list/notice";

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Override
    public Market supportMarket() {
        return Market.CN;
    }

    @Override
    public List<ReportMeta> fetchMetaList(String stockCode, ReportType type,int startYear,int endYear) {
        List<ReportMeta> list = new ArrayList<>();
        try {
            // 1. 查 orgId，stock 参数格式为「代码,orgId」
            String orgId = resolveOrgId(stockCode);

            // 2. 市场栏目：6/9 开头为上交所(sse)，其余为深交所(szse)
            String column = stockCode.startsWith("6") || stockCode.startsWith("9") ? "sse" : "szse";

            // 3. 公告类别：年报 ndbg / 半年报 bndbg / 季报 yjdbg(一季报)+sjdbg(三季报)
            String category = switch (type) {
                case ANNUAL -> "category_ndbg_szsh";
                case INTERIM -> "category_bndbg_szsh";
                default -> "category_yjdbg_szsh;category_sjdbg_szsh";
            };

            String form = String.format(
                    "pageNum=1&pageSize=30&column=%s&tabName=fulltext&plate=&stock=%s,%s&" +
                    "searchkey=&secid=&category=%s&trade=&seDate=%d-01-01~%d-12-31&" +
                    "sortName=&sortType=&isHLtitle=false",
                    column, stockCode, orgId, category, startYear, endYear);

            JSONObject json = JSON.parseObject(postForm(QUERY_API, form, REFERER));
            JSONArray arr = json.getJSONArray("announcements");
            if (arr == null) return list;

            for (int i = 0; i < arr.size(); i++) {
                JSONObject ann = arr.getJSONObject(i);
                String title = ann.getString("announcementTitle");
                // 标题需含报告类型关键词，排除摘要、英文版、取消等
                if (title == null || !title.contains(type.cnKey())
                        || title.contains("摘要") || title.contains("英文") || title.contains("取消")) {
                    continue;
                }

                ReportMeta meta = new ReportMeta();
                meta.title = title;
                // announcementTime 是毫秒时间戳（北京时间），转 yyyy-MM-dd
                LocalDate date = Instant.ofEpochMilli(ann.getLongValue("announcementTime"))
                        .atOffset(ZoneOffset.ofHours(8)).toLocalDate();
                meta.publishDate = date.format(DATE_FMT);
                meta.pdfUrl = PDF_BASE + ann.getString("adjunctUrl");
                meta.localPath = String.format("CN/%s/%s_%s_%s.pdf",
                        stockCode, stockCode, meta.publishDate, type.name());
                list.add(meta);
                throttle();
            }
        } catch (Exception e) {
            list.clear();
            ReportMeta m = new ReportMeta();
            m.errorMsg = "CNINFO 查询失败: " + e;
            list.add(m);
        }
        return list;
    }

    /** 股票代码 -> 巨潮 orgId（如 600519 -> gssh0600519） */
    private String resolveOrgId(String stockCode) throws Exception {
        String form = "keyWord=" + URLEncoder.encode(stockCode, StandardCharsets.UTF_8) + "&maxNum=10";
        JSONArray arr = JSON.parseArray(postForm(SEARCH_API, form, REFERER));
        for (int i = 0; i < arr.size(); i++) {
            JSONObject o = arr.getJSONObject(i);
            if (stockCode.equals(o.getString("code"))) {
                return o.getString("orgId");
            }
        }
        if (!arr.isEmpty()) {
            return arr.getJSONObject(0).getString("orgId");
        }
        throw new IllegalArgumentException("巨潮未找到股票代码: " + stockCode);
    }
}
