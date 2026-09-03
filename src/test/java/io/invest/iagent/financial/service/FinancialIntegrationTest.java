package io.invest.iagent.financial.service;

import io.invest.AgentConfig4Test;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(classes = AgentConfig4Test.class)
@TestPropertySource(locations = "classpath:test.properties")
public class FinancialIntegrationTest {

    @Autowired
    private FinancialReportService reportService ;

    @Autowired
    private FinancialIngestService ingestService ;

    @Autowired
    private FinancialQueryService queryService ;

    @Test
    public void test_AAPL_init() {
        String ticker = "AAPL";
        int startYear = 2020;
        int endYear = 2026;
        this.filling(ticker, startYear, endYear);
    }

    @Test
    public void test_AMZN_init() {
        String ticker = "AMZN";
        int startYear = 2021;
        int endYear = 2026;
        this.filling(ticker, startYear, endYear);
    }

    @Test
    public void test_MSFT_init() {
        String ticker = "MSFT";
        int startYear = 2020;
        int endYear = 2026;
        this.filling(ticker, startYear, endYear);
    }

    @Test
    public void test_NVDA_init() {
        String ticker = "NVDA";
        int startYear = 2020;
        int endYear = 2026;
        this.filling(ticker, startYear, endYear);
    }

    @Test
    public void test_600900_init() {
        String ticker = "600900";
        int startYear = 2020;
        int endYear = 2026;
        this.filling(ticker, startYear, endYear);
    }

    @Test
    public void test_600188_init() {
        String ticker = "600188";
        int startYear = 2020;
        int endYear = 2026;
        this.filling(ticker, startYear, endYear);
    }

    private void filling(String ticker,int startYear,int endYear){
        // download
        FinancialReportService.DownloadResult downloadResult = reportService.downloadBatch(ticker, null,startYear,endYear) ;
        Assertions.assertTrue(downloadResult.success());
        // ingest
        FinancialIngestService.BuildResult buildResult = ingestService.build(ticker,(endYear-startYear+1)*4) ;
        Assertions.assertTrue(buildResult.success());
    }


}
