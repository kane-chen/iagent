package io.invest.iagent.financial.report.downloader;

import io.invest.iagent.financial.config.FinancialProperties;
import io.invest.iagent.financial.report.enums.Market;
import io.invest.iagent.financial.report.enums.ReportType;
import io.invest.iagent.financial.report.model.ReportMeta;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 财报下载基类：负责 HTTP 通信、PDF/HTM 文件落盘（带重试 + 断点跳过）。
 * 子类只需实现 {@link #fetchMetaList} 对接各市场的公告查询接口。
 */
public abstract class ReportDownloader {

    @Autowired
    protected FinancialProperties financialProperties ;

    protected static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();

    // UA 轮换池，降低被识别为爬虫的概率
    private static final String[] UA_POOL = {
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 Safari/605.1.15",
        "Mozilla/5.0 (X11; Linux x86_64; rv:121.0) Gecko/20100101 Firefox/121.0"
    };
    protected String randomUA() {
        return UA_POOL[ThreadLocalRandom.current().nextInt(UA_POOL.length)];
    }

    public abstract Market supportMarket() ;

    /** 对外主入口 */
    public List<ReportMeta> download(String stockCode, ReportType type,int startYear,int endYear) {
        List<ReportMeta> metas = fetchMetaList(stockCode, type,startYear,endYear);
        // 无 pdfUrl 的是查询阶段产生的错误占位元数据，跳过下载
        metas.stream().filter(m -> m.getPdfUrl() != null).forEach(this::downloadOne);
        return metas;
    }

    /** 子类实现：调 API 取公告列表 */
    public abstract List<ReportMeta> fetchMetaList(String stockCode, ReportType type,int startYear,int endYear);

    /** GET 取文本（JSON/HTML），非 200 抛异常 */
    protected String getText(String url, String userAgent) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", userAgent)
                .GET()
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IOException("HTTP " + resp.statusCode() + " 请求失败: " + url);
        }
        return resp.body();
    }

    /** POST 表单（application/x-www-form-urlencoded），非 200 抛异常 */
    protected String postForm(String url, String form, String referer) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", randomUA())
                .header("Content-Type", "application/x-www-form-urlencoded");
        if (referer != null && !referer.isEmpty()) {
            builder.header("Referer", referer);
        }
        HttpRequest req = builder.POST(HttpRequest.BodyPublishers.ofString(form)).build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IOException("HTTP " + resp.statusCode() + " 请求失败: " + url);
        }
        return resp.body();
    }

    /** 下载文件时使用的 UA，默认浏览器 UA；SEC 要求带联系邮箱，由子类覆盖 */
    protected String downloadUserAgent() { return randomUA(); }

    /** 下载单个文件，带重试 + 指数退避；已存在且非空则跳过 */
    protected void downloadOne(ReportMeta meta) {
        Path out = Path.of(financialProperties.getReportBaseDir(), meta.localPath);
        try {
            Files.createDirectories(out.getParent());
            if (Files.exists(out) && Files.size(out) > 0) {   // 断点续传：已存在且非空则跳过
                meta.success = true;
                return;
            }
            int maxRetry = financialProperties.getReportMaxRetry() ;
            for (int attempt = 1; attempt <= maxRetry; attempt++) {
                try {
                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create(meta.pdfUrl))
                            .header("User-Agent", downloadUserAgent())
                            .GET()
                            .build();
                    HttpResponse<byte[]> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
                    if (resp.statusCode() == 200 && resp.body().length > 1024) {
                        Files.write(out, resp.body());
                        meta.success = true;
                        return;
                    }
                    meta.errorMsg = "HTTP " + resp.statusCode() + "，响应体 " + resp.body().length + " 字节";
                } catch (Exception e) {
                    meta.errorMsg = e.getMessage();
                }
                // 指数退避，最后一次失败后不再等待
                if (attempt < maxRetry) {
                    Thread.sleep(financialProperties.getReportSleepMs() * attempt);
                }
            }
        } catch (Exception e) {
            meta.errorMsg = e.getMessage();
        }
    }

    /** 请求间限流 */
    protected void throttle() {
        try { Thread.sleep(financialProperties.getReportSleepMs() + ThreadLocalRandom.current().nextLong(500)); }
        catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }
}
