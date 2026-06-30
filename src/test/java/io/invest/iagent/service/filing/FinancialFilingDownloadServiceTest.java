package io.invest.iagent.service.filing;

import io.invest.iagent.model.FinancialFilingDownloadResult;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

class FinancialFilingDownloadServiceTest {

    private FinancialFilingDownloadService service ;

    @BeforeEach
    public void init(){
        Path workspace = Paths.get(System.getProperty("user.dir")).resolve("./test-filings");
        String userAgent = "io/yiying5@gmail.com";
        service = new FinancialFilingDownloadService(workspace, userAgent);
    }

    @Test
    public void download_li(){
        String ticker = "LI";
        FinancialFilingDownloadResult result = service.downloadFiling(ticker, "2024",null);
        Assertions.assertNotNull(result);
        Assertions.assertNotNull(result.getMessage());
    }

    @Test
    public void download_APPLE(){
        String ticker = "AAPL";
        FinancialFilingDownloadResult result = service.downloadFiling(ticker, "2024",null);
        Assertions.assertNotNull(result);
        Assertions.assertNotNull(result.getMessage());
    }

}