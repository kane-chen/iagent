package io.invest.iagent.service.filing.downloader;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.invest.iagent.model.FinancialFilingDownloadResult;
import io.invest.iagent.service.filing.model.DownloadedFile;
import io.invest.iagent.service.filing.model.DownloadedFiling;
import io.invest.iagent.service.filing.util.FilingDownloadSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 港股港交所披露易下载器
 */
public class HkexDownloader extends FilingDownloadSupport {

    private static final Logger logger = LoggerFactory.getLogger(HkexDownloader.class);

    public HkexDownloader(Path workspaceDir, ObjectMapper objectMapper) {
        super(workspaceDir, objectMapper);
    }

    public FinancialFilingDownloadResult downloadHkFiling(String stockCode, Set<Integer> fiscalYears, boolean allYears,
                                   Set<String> formTypes, boolean allTypes, boolean overwrite) {
        logger.info("HK filing download for stock: {}, years: {}, types: {}", stockCode, fiscalYears, formTypes);

        Set<Integer> years = fiscalYears;
        if (allYears) {
            int currentYear = java.time.Year.now().getValue();
            years = new LinkedHashSet<>();
            for (int y = currentYear; y >= currentYear - 10; y--) {
                years.add(y);
            }
        }

        StringBuilder result = new StringBuilder();
        result.append("HK filing download requires manual operation\n");
        result.append("Stock Code: ").append(stockCode).append("\n");
        result.append("Form Types: ").append(String.join(", ", formTypes)).append("\n");
        result.append("Years: ").append(allYears ? "last 10 years" : years.toString()).append("\n\n");
        result.append("Please download manually from the following HKEX search links:\n");

        // 去重年份后生成有限数量的链接
        List<Integer> sortedYears = new ArrayList<>(years);
        Collections.sort(sortedYears);
        // 只展示前 3 年以减少链接数量
        int linkCount = 0;
        for (int year : sortedYears) {
            if (linkCount >= 3) {
                result.append("... and ").append(sortedYears.size() - 3).append(" more years\n");
                break;
            }
            String url = String.format(
                    "https://www1.hkexnews.hk/listedco/listconews/advancedsearch/search_active_main.aspx?stockcode=%s&yearfrom=%d&yearto=%d",
                    stockCode, year, year
            );
            result.append("  ").append(year).append(": ").append(url).append("\n");
            linkCount++;
        }

        result.append("\nAfter downloading, use upload_filing to process the file.");
        String message = result.toString();

        return FinancialFilingDownloadResult.builder()
                .success(true)
                .ticker(stockCode)
                .formTypes(List.copyOf(formTypes))
                .fiscalYears(List.copyOf(years))
                .allYears(allYears)
                .totalCount(0)
                .downloadedCount(0)
                .skippedCount(0)
                .errorCount(0)
                .downloadedFilings(List.of())
                .skipped(List.of())
                .errors(List.of())
                .message(message)
                .error(null)
                .build();
    }
}
