package io.invest.iagent.financial.service;

import io.invest.AgentConfig4Test;
import io.invest.iagent.financial.report.enums.ReportType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;


@SpringBootTest(classes = AgentConfig4Test.class)
@TestPropertySource(locations = "classpath:test.properties")
class FinancialReportServiceTest {

    @Autowired
    private FinancialReportService reportService ;

    @Test
    public void test_down_83690(){
        String ticker = "83690" ;
        FinancialReportService.DownloadResult result = reportService.downloadBatch(ticker, null,2021,2026) ;
        Assertions.assertTrue(result.success());
        Assertions.assertEquals(ticker,result.ticker());
    }

    @Test
    public void test_down_00700_q(){
        String ticker = "83690" ;
        FinancialReportService.DownloadResult result = reportService.download(ticker, ReportType.QUARTERLY,2021,2026) ;
        Assertions.assertTrue(result.success());
        Assertions.assertEquals(ticker,result.ticker());
    }

    @Test
    public void test_down_00700_h(){
        String ticker = "00700" ;
        FinancialReportService.DownloadResult result = reportService.download(ticker, ReportType.INTERIM,2024,2026) ;
        Assertions.assertTrue(result.success());
        Assertions.assertEquals(ticker,result.ticker());
    }

    @Test
    public void test_down_baba_q(){
        String ticker = "BABA" ;
        FinancialReportService.DownloadResult result = reportService.download(ticker, ReportType.QUARTERLY,2020,2026) ;
        Assertions.assertTrue(result.success());
        Assertions.assertEquals(ticker,result.ticker());
    }

    @Test
    public void test_down_li_q(){
        String ticker = "PDD" ;
        FinancialReportService.DownloadResult result = reportService.download(ticker, ReportType.QUARTERLY,2020,2026) ;
        Assertions.assertTrue(result.success());
        Assertions.assertEquals(ticker,result.ticker());
    }

    @Test
    public void test_down_maotai_q(){
        String ticker = "600519" ;
        FinancialReportService.DownloadResult result = reportService.download(ticker, ReportType.QUARTERLY,2025,2026) ;
        Assertions.assertTrue(result.success());
        Assertions.assertEquals(ticker,result.ticker());
    }

}