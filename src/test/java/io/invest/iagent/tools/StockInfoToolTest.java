package io.invest.iagent.tools;

import com.alibaba.fastjson2.JSON;
import com.google.common.collect.Lists;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;


class StockInfoToolTest {

    private StockInfoTool tool;

    @BeforeEach
    public void init() {
        tool = new StockInfoTool();
    }

    @Test
    public void test_process_ticker_apple() throws IOException, InterruptedException {
        List<StockInfoTool.StockInfo> stocks = tool.searchTicker("苹果", null,1);
        Assertions.assertNotNull(stocks.get(0));
        Assertions.assertEquals("AAPL", stocks.get(0).getSymbol());
        Assertions.assertEquals("US_DOMESTIC", stocks.get(0).getCompanyType());
        Assertions.assertEquals("10-K", stocks.get(0).getAnnualReportType());
        Assertions.assertEquals("10-Q", stocks.get(0).getQuarterlyReportType());
        Assertions.assertEquals("美国SEC", stocks.get(0).getFilingAuthority());
    }

    @Test
    public void test_process_ticker_nvd() throws IOException, InterruptedException {
        List<StockInfoTool.StockInfo> stocks = tool.searchTicker("英伟达", null,1);
        System.out.println(JSON.toJSONString(stocks));
        Assertions.assertNotNull(stocks.get(0));
        Assertions.assertEquals("NVDA", stocks.get(0).getSymbol());
        Assertions.assertEquals("US_DOMESTIC", stocks.get(0).getCompanyType());
        Assertions.assertEquals("10-K", stocks.get(0).getAnnualReportType());
        Assertions.assertEquals("10-Q", stocks.get(0).getQuarterlyReportType());
        Assertions.assertEquals("美国SEC", stocks.get(0).getFilingAuthority());
    }

    @Test
    public void test_process_ticker_pdd() throws IOException, InterruptedException {
        List<StockInfoTool.StockInfo> stocks = tool.searchTicker("拼多多", null,1);
        Assertions.assertNotNull(stocks.get(0));
        Assertions.assertEquals("PDD", stocks.get(0).getSymbol());
        Assertions.assertEquals("FOREIGN_PRIVATE_ISSUER", stocks.get(0).getCompanyType());
        Assertions.assertEquals("20-F", stocks.get(0).getAnnualReportType());
        Assertions.assertEquals("6-K", stocks.get(0).getQuarterlyReportType());
        Assertions.assertEquals("美国SEC", stocks.get(0).getFilingAuthority());
    }

    @Test
    public void test_process_ticker_baba() throws IOException, InterruptedException {
        List<StockInfoTool.StockInfo> stocks = tool.searchTicker("阿里巴巴", Lists.newArrayList("NASDAQ"),1);
        Assertions.assertNotNull(stocks.get(0));
        Assertions.assertEquals("BABA", stocks.get(0).getSymbol());
        Assertions.assertEquals("FOREIGN_PRIVATE_ISSUER", stocks.get(0).getCompanyType());
        Assertions.assertEquals("20-F", stocks.get(0).getAnnualReportType());
        Assertions.assertEquals("6-K", stocks.get(0).getQuarterlyReportType());
        Assertions.assertEquals("美国SEC", stocks.get(0).getFilingAuthority());
    }

    @Test
    public void test_process_ticker_tencent() throws IOException, InterruptedException {
        List<StockInfoTool.StockInfo> stocks = tool.searchTicker("腾讯", null,1);
        Assertions.assertNotNull(stocks.get(0));
        Assertions.assertEquals("00700", stocks.get(0).getSymbol());
        Assertions.assertEquals("HK_LISTED", stocks.get(0).getCompanyType());
        Assertions.assertEquals("年报", stocks.get(0).getAnnualReportType());
        Assertions.assertEquals("季度业绩公告（自愿）", stocks.get(0).getQuarterlyReportType());
        Assertions.assertEquals("香港联交所", stocks.get(0).getFilingAuthority());
    }

    @Test
    public void test_process_ticker_meituan() throws IOException, InterruptedException {
        List<StockInfoTool.StockInfo> stocks = tool.searchTicker("美团", Lists.newArrayList("HKG"),1);
        Assertions.assertNotNull(stocks.get(0));
        Assertions.assertEquals("83690", stocks.get(0).getSymbol());
        Assertions.assertEquals("HK_LISTED", stocks.get(0).getCompanyType());
        Assertions.assertEquals("年报", stocks.get(0).getAnnualReportType());
        Assertions.assertEquals("季度业绩公告（自愿）", stocks.get(0).getQuarterlyReportType());
        Assertions.assertEquals("香港联交所", stocks.get(0).getFilingAuthority());
    }

    @Test
    public void test_process_ticker_ppmt() throws IOException, InterruptedException {
        List<StockInfoTool.StockInfo> stocks = tool.searchTicker("泡泡玛特", Lists.newArrayList("HKG"),1);
        Assertions.assertNotNull(stocks.get(0));
        Assertions.assertEquals("09992", stocks.get(0).getSymbol());
        Assertions.assertEquals("HK_LISTED", stocks.get(0).getCompanyType());
        Assertions.assertEquals("年报", stocks.get(0).getAnnualReportType());
        Assertions.assertEquals("季度业绩公告（自愿）", stocks.get(0).getQuarterlyReportType());
        Assertions.assertEquals("中期报告", stocks.get(0).getSemiAnnualReportType());
        Assertions.assertEquals("香港联交所", stocks.get(0).getFilingAuthority());
    }

    @Test
    public void test_process_ticker_vanke() throws IOException, InterruptedException {
        List<StockInfoTool.StockInfo> stocks = tool.searchTicker("万科", Lists.newArrayList("SZSE"),1);
        Assertions.assertNotNull(stocks.get(0));
        System.out.println(stocks.get(0));
        Assertions.assertEquals("000002", stocks.get(0).getSymbol());
        Assertions.assertEquals("CN_LISTED", stocks.get(0).getCompanyType());
        Assertions.assertEquals("年度报告", stocks.get(0).getAnnualReportType());
        Assertions.assertEquals("季度报告", stocks.get(0).getQuarterlyReportType());
        Assertions.assertEquals("中国证监会", stocks.get(0).getFilingAuthority());
    }

    @Test
    public void test_process_ticker_maotai() throws IOException, InterruptedException {
        List<StockInfoTool.StockInfo> stocks = tool.searchTicker("茅台", Lists.newArrayList("SZSE","SSE"),1);
        Assertions.assertNotNull(stocks.get(0));
        System.out.println(stocks.get(0));
        Assertions.assertEquals("600519", stocks.get(0).getSymbol());
        Assertions.assertEquals("CN_LISTED", stocks.get(0).getCompanyType());
        Assertions.assertEquals("年度报告", stocks.get(0).getAnnualReportType());
        Assertions.assertEquals("季度报告", stocks.get(0).getQuarterlyReportType());
        Assertions.assertEquals("中国证监会", stocks.get(0).getFilingAuthority());
    }

}