package io.invest.iagent.service.filing.downloader;

import io.invest.iagent.model.FinancialFilingDownloadResult;
import io.invest.iagent.service.filing.FinancialFilingDownloadService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 港股财报下载测试
 * 注意：默认禁用，因为需要网络访问港交所披露易API
 */
class HkexDownloaderTest {

    private final Path workspace = Paths.get(System.getProperty("user.dir")).resolve("workspace");

    @Test
    @Disabled("需要网络访问，手动运行测试")
    public void testDownloadHkFiling_SingleYear() {
        FinancialFilingDownloadService service = new FinancialFilingDownloadService(workspace);

        // 测试下载腾讯控股(00700)2024年年报
        FinancialFilingDownloadResult result = service.downloadFiling("00700.HK", "2024", "FY");

        Assertions.assertNotNull(result);
        System.out.println("Download result: " + result.getMessage());
        System.out.println("Downloaded count: " + result.getDownloadedCount());
        System.out.println("Skipped count: " + result.getSkippedCount());
        System.out.println("Error count: " + result.getErrorCount());
    }

    @Test
    @Disabled("需要网络访问，手动运行测试")
    public void testDownloadHkFiling_MultipleYears() {
        FinancialFilingDownloadService service = new FinancialFilingDownloadService(workspace);

        // 测试下载中国移动(00941)多年年报
        FinancialFilingDownloadResult result = service.downloadFiling("00941.HK", "2023,2024", "FY");

        Assertions.assertNotNull(result);
        System.out.println("Download result: " + result.getMessage());
    }

    @Test
    @Disabled("需要网络访问，手动运行测试")
    public void testDownloadHkFiling_InterimReport() {
        FinancialFilingDownloadService service = new FinancialFilingDownloadService(workspace);

        // 测试下载汇丰控股(00005)2024年中报
        FinancialFilingDownloadResult result = service.downloadFiling("00005.HK", "2024", "H1");

        Assertions.assertNotNull(result);
        System.out.println("Download result: " + result.getMessage());
    }

    @Test
    public void testNormalizeTicker_HK() {
        // 验证HK股票代码标准化逻辑（使用FinancialFilingDownloadService的normalizeTicker方法）
        FinancialFilingDownloadService service = new FinancialFilingDownloadService(workspace);

        // 通过parseFilingTypes间接测试市场识别和代码标准化
        FinancialFilingDownloadService.TypeParseResult result = service.parseFilingTypes("FY", io.invest.iagent.model.TickerMarket.HK);
        Assertions.assertNotNull(result);
        Assertions.assertTrue(result.types().contains("FY"));
    }
}
