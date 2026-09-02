package io.invest.iagent.financial.report.downloader;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.invest.iagent.financial.report.enums.Market;
import io.invest.iagent.financial.report.enums.ReportType;
import io.invest.iagent.financial.report.model.ReportMeta;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 美国 SEC EDGAR 下载器，支持两类发行人：
 * <ul>
 *   <li>本土上市公司：年报 10-K、季报 10-Q，取 data.sec.gov submissions 接口；</li>
 *   <li>中概股等外国私募发行人(FPI)：年报 20-F、季报以 6-K 形式临时申报。
 *       6-K 混杂大量非财报公告（董事会日期预告、股东大会、月报等），季报正文通常是
 *       Exhibit 99.1 附件（文件名形如 xxx_ex99-1.htm）。这里通过 EDGAR 全文检索(EFTS)
 *       限定 forms=6-K + 短语 "financial results" 召回候选，再抓取附件开头文本，
 *       用标题特征（announces/reports ... quarter/full year ... results）二次确认。</li>
 * </ul>
 * 注意：SEC 强制要求 User-Agent 带联系邮箱；主文档为 htm 而非 PDF。
 */
@Service
public class EdgarDownloader extends ReportDownloader {

    private static final String TICKERS_API = "https://www.sec.gov/files/company_tickers.json";
    private static final String SUBMISSIONS_API = "https://data.sec.gov/submissions/CIK%010d.json";
    private static final String EFTS_API = "https://efts.sec.gov/LATEST/search-index";
    private static final String DOC_URL = "https://www.sec.gov/Archives/edgar/data/%d/%s/%s";

    /** 6-K 季报附件文件名特征：ex99-1 / ex99_1 / ex991（Exhibit 99.1），仅认 htm/html */
    private static final Pattern EX99_1 = Pattern.compile("ex99[-_.]?1(?![0-9])[^/]*\\.html?$", Pattern.CASE_INSENSITIVE);
    /**
     * 财报标题特征，用于把 6-K 中的季报从其他公告里区分出来。两类写法：
     * 英文新闻稿 "PDD Holdings Announces Second Quarter 2026 ... Financial Results"；
     * 港交所格式公告 "ANNOUNCEMENT OF THE MARCH QUARTER 2025 RESULTS AND FISCAL YEAR 2025 ANNUAL RESULTS"。
     */
    private static final Pattern EARNINGS_TITLE = Pattern.compile(
            "(announces?|reports?|announcement\\s+of)\\b.{0,150}?\\b(quarter|full year|fiscal year)\\b.{0,80}?\\bresults\\b",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    /** 只在附件开头一段文本里判断标题（港交所格式免责声明较长，标题约在 1000 字符处），避免正文偶发措辞误判 */
    private static final int TITLE_SCAN_CHARS = 3000;

    // ticker -> CIK / CIK -> 公司名，全量映射只加载一次
    private static volatile Map<String, Long> cikByTicker;
    private static volatile Map<Long, String> companyByCik;

    @Override
    public Market supportMarket() {
        return Market.US ;
    }

    /** SEC 强制要求 UA 带联系邮箱 */
    protected String downloadUserAgent() { return financialProperties.getReportSecUserAgent(); }

    @Override
    public List<ReportMeta> fetchMetaList(String ticker, ReportType type,int startYear,int endYear) {
        List<ReportMeta> list = new ArrayList<>();
        String t = ticker.toUpperCase();
        try {
            ensureTickerMap();
            Long cik = cikByTicker.get(t);
            if (cik == null) {
                throw new IllegalArgumentException("EDGAR 未找到 ticker: " + ticker);
            }
            String company = companyByCik.get(cik);

            JSONObject recent = JSON.parseObject(
                            getText(String.format(SUBMISSIONS_API, cik), this.downloadUserAgent()))
                    .getJSONObject("filings").getJSONObject("recent");

            // 按申报历史判断发行人类型：申报 20-F/6-K 的是外国私募发行人（中概股）
            boolean foreign = false;
            JSONArray forms = recent.getJSONArray("form");
            for (int i = 0; i < forms.size(); i++) {
                String f = forms.getString(i);
                if ("20-F".equals(f) || "6-K".equals(f)) { foreign = true; break; }
            }

            if (foreign) {
                // 中概股：年报 20-F；季报/中报都是 6-K 附件
                if (type == ReportType.ANNUAL) {
                    list.addAll(fromSubmissions(recent, "20-F", cik, t, company,startYear,endYear));
                } else {
                    list.addAll(fetch6kEarnings(cik, t, company,startYear,endYear));
                }
            } else {
                // 本土公司：年报 10-K，季报/中报 10-Q（精确匹配，排除 10-K/A 等修订）
                list.addAll(fromSubmissions(recent, type == ReportType.ANNUAL ? "10-K" : "10-Q",
                        cik, t, company,startYear,endYear));
            }
        } catch (Exception e) {
            list.clear();
            ReportMeta m = new ReportMeta();
            m.errorMsg = "EDGAR 查询失败: " + e;
            list.add(m);
        }
        return list;
    }

    /** 从 submissions 的 filings.recent 中按表单类型抽取文档 */
    private List<ReportMeta> fromSubmissions(JSONObject recent, String formType,
                                             long cik, String ticker, String company,int startYear,int endYear) {
        List<ReportMeta> list = new ArrayList<>();
        JSONArray accessions = recent.getJSONArray("accessionNumber");
        JSONArray forms = recent.getJSONArray("form");
        JSONArray filingDates = recent.getJSONArray("filingDate");
        JSONArray primaryDocs = recent.getJSONArray("primaryDocument");

        for (int i = 0; i < accessions.size(); i++) {
            if (!formType.equals(forms.getString(i))) continue;
            String filingDate = filingDates.getString(i);   // yyyy-MM-dd
            int year = Integer.parseInt(filingDate.substring(0, 4));
            if (year < startYear || year > endYear) continue;

            ReportMeta m = new ReportMeta();
            m.title = company + " " + formType + " (filed " + filingDate + ")";
            m.publishDate = filingDate;
            m.pdfUrl = String.format(DOC_URL, cik,
                    accessions.getString(i).replace("-", ""), primaryDocs.getString(i));
            m.localPath = String.format("US/%s/%s_%s_%s.htm",
                    ticker, ticker, filingDate.replace("-", ""), formType);
            list.add(m);
            throttle();
        }
        return list;
    }

    /**
     * 中概股季报：EFTS 全文检索 6-K 候选，逐份校验 Exhibit 99.1 标题，
     * 保留真正的季度/年度业绩公告（同一 accession 只取一份附件）。
     */
    private List<ReportMeta> fetch6kEarnings(long cik, String ticker, String company,int startYear,int endYear) throws Exception {
        // accession -> [文件名, filingDate]，同一申报只保留一个附件
        Map<String, String[]> candidates = new LinkedHashMap<>();

        String url = EFTS_API + "?q=" + URLEncoder.encode("\"financial results\"", StandardCharsets.UTF_8)
                + "&forms=6-K&ciks=" + String.format("%010d", cik)
                + "&dateRange=custom&startdt=" + startYear + "-01-01&enddt=" + endYear + "-12-31";
        JSONObject resp = JSON.parseObject(getText(url, this.downloadUserAgent()));
        JSONObject hits = resp.getJSONObject("hits");
        if (hits == null) return new ArrayList<>();
        JSONArray hitArr = hits.getJSONArray("hits");
        if (hitArr == null) return new ArrayList<>();

        for (int i = 0; i < hitArr.size(); i++) {
            JSONObject hit = hitArr.getJSONObject(i);
            int colon = hit.getString("_id").indexOf(':');
            if (colon < 0) continue;
            String accession = hit.getString("_id").substring(0, colon);
            String fileName = hit.getString("_id").substring(colon + 1);
            String fileDate = hit.getJSONObject("_source").getString("file_date");
            int year = Integer.parseInt(fileDate.substring(0, 4));
            if (year < startYear || year > endYear) continue;
            // 只认 Exhibit 99.1 的 htm/html 附件（PDF 多为月报等其他材料）
            if (!EX99_1.matcher(fileName).find()) continue;
            candidates.putIfAbsent(accession, new String[]{fileName, fileDate});
        }

        List<ReportMeta> list = new ArrayList<>();
        java.util.Set<String> seenDates = new java.util.HashSet<>();
        for (Map.Entry<String, String[]> e : candidates.entrySet()) {
            String accession = e.getKey();
            String fileName = e.getValue()[0];
            String fileDate = e.getValue()[1];
            throttle();
            try {
                String docUrl = String.format(DOC_URL, cik, accession.replace("-", ""), fileName);
                String text = fetchHeadText(docUrl);
                if (!EARNINGS_TITLE.matcher(text.substring(0, Math.min(TITLE_SCAN_CHARS, text.length()))).find()) {
                    continue;   // 董事会日期预告、港交所其他公告等噪音
                }
                // 同一天可能有英文版/港交所正式公告等多份附件，同季度只保留一份
                if (!seenDates.add(fileDate)) continue;
                ReportMeta m = new ReportMeta();
                m.title = company + " 6-K 业绩公告 (filed " + fileDate + ")";
                m.publishDate = fileDate;
                m.pdfUrl = docUrl;
                m.localPath = String.format("US/%s/%s_%s_6-K.htm",
                        ticker, ticker, fileDate.replace("-", ""));
                list.add(m);
            } catch (Exception ex) {
                // 单份附件校验失败不影响整体
            }
        }
        list.sort(Comparator.comparing(m -> m.publishDate));
        return list;
    }

    /** 抓取文档开头内容（Range 只取前 16KB）并转成纯文本，用于标题判断 */
    private String fetchHeadText(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(java.net.URI.create(url))
                .header("User-Agent", this.downloadUserAgent())
                .header("Range", "bytes=0-32767")
                .GET().build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200 && resp.statusCode() != 206) {
            throw new java.io.IOException("HTTP " + resp.statusCode());
        }
        return stripHtml(resp.body());
    }

    /** 粗粒度 HTML 转文本：去标签、反转义常见实体、压缩空白 */
    private static String stripHtml(String html) {
        String t = html.replaceAll("(?is)<script.*?</script>|<style.*?</style>", " ");
        t = t.replaceAll("(?s)<[^>]+>", " ");
        t = t.replace("&nbsp;", " ").replace("&amp;", "&").replace("&#39;", "'")
             .replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">");
        return t.replaceAll("\\s+", " ").trim();
    }

    /** 加载全量 ticker -> CIK 映射（约 1 万条，进程内缓存） */
    private void ensureTickerMap() throws Exception {
        if (cikByTicker != null) return;
        synchronized (EdgarDownloader.class) {
            if (cikByTicker != null) return;
            JSONObject obj = JSON.parseObject(getText(TICKERS_API, this.downloadUserAgent()));
            Map<String, Long> ciks = new HashMap<>();
            Map<Long, String> names = new HashMap<>();
            for (String key : obj.keySet()) {
                JSONObject o = obj.getJSONObject(key);
                long cik = o.getLongValue("cik_str");
                ciks.put(o.getString("ticker").toUpperCase(), cik);
                names.put(cik, o.getString("title"));
            }
            companyByCik = names;
            cikByTicker = ciks;
        }
    }
}
