package io.invest.iagent.service.filing.downloader;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.invest.iagent.model.FinancialFilingDownloadResult;
import io.invest.iagent.service.filing.FinancialFilingDownloadService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 港股财报下载集成测试
 * 这些测试需要网络连接，默认禁用
 * 运行时请手动启用
 */
class HkexDownloaderIntegrationTest {

    private final Path workspace = Paths.get(System.getProperty("user.dir")).resolve("workspace");
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .build();

    @Test
    @Disabled("需要网络访问港交所API")
    public void testDownloadTencentAnnualReport2025() throws Exception {
        HkexDownloader downloader = new HkexDownloader(workspace, httpClient, objectMapper);

        // 测试下载腾讯控股(00700)2024年年报
        FinancialFilingDownloadResult result = downloader.downloadHkFiling(
                "00700", java.util.Set.of(2025), false,
                java.util.Set.of("FY"), false, false
        );

        Assertions.assertNotNull(result);
        System.out.println("Download result: " + result.getMessage());
        System.out.println("Downloaded count: " + result.getDownloadedCount());
        System.out.println("Skipped count: " + result.getSkippedCount());
        System.out.println("Error count: " + result.getErrorCount());

        // 打印所有错误
        if (!result.getErrors().isEmpty()) {
            System.out.println("\nErrors:");
            for (String error : result.getErrors()) {
                System.out.println("  - " + error);
            }
        }
    }

    @Test
    @Disabled("需要网络访问港交所API")
    public void testDownloadViaUnifiedService() {
        FinancialFilingDownloadService service = new FinancialFilingDownloadService(workspace);

        // 测试通过统一服务下载
        FinancialFilingDownloadResult result = service.downloadFiling("00700.HK", "2024", "FY");

        Assertions.assertNotNull(result);
        System.out.println("Unified service result: " + result.getMessage());
        System.out.println("Success: " + result.isSuccess());
    }

    @Test
    @Disabled("需要网络访问港交所API")
    public void testSearchAnnouncementsDirectly() throws Exception {
        HkexDownloader downloader = new HkexDownloader(workspace, httpClient, objectMapper);

        // 使用反射调用私有方法进行测试
        java.lang.reflect.Method method = HkexDownloader.class.getDeclaredMethod(
                "searchHkAnnouncements", String.class, String.class, String.class, String.class
        );
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        var results = (java.util.List<?>) method.invoke(downloader, "00700", "2024-01-01", "2024-12-31", "40100");

        System.out.println("Found " + results.size() + " announcements");
        for (Object ann : results) {
            System.out.println("  - " + ann);
        }
    }
}
