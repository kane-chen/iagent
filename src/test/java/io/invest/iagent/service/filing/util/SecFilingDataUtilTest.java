package io.invest.iagent.service.filing.util;

import com.alibaba.fastjson2.JSON;
import io.invest.iagent.service.filing.util.SecFilingDataUtil;
import io.invest.iagent.service.filing.util.SecFilingFilterUtil;
import io.invest.iagent.model.FinanceQueryParam;
import io.invest.iagent.model.FinancialIndexValueDTO;
import io.invest.iagent.service.filing.model.SecFilingDataDTO;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.apache.commons.lang3.tuple.Pair ;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Objects;


class SecFilingDataUtilTest {

    @Test
    void test_apple() throws IOException, InterruptedException {
        List<FinancialIndexValueDTO> values = queryXbrlValues("AAPL","USD",null) ;
        Assertions.assertNotNull(values);
    }

    @Test
    void test_app() throws IOException, InterruptedException {
        List<FinancialIndexValueDTO> values = queryXbrlValues("APP","USD",null) ;
        Assertions.assertNotNull(values);
    }

    @Test
    void test_pdd() throws IOException, InterruptedException {
        List<FinancialIndexValueDTO> values = queryXbrlValues("PDD","CNY",null) ;
        Assertions.assertNotNull(values);
    }

    @Test
    void test_li() throws IOException, InterruptedException {
        List<FinancialIndexValueDTO> values = queryXbrlValues("LI","CNY",null) ;
        Assertions.assertNotNull(values);
    }

    @Test
    void test_baba() throws IOException, InterruptedException {
        List<FinancialIndexValueDTO> values = queryXbrlValues("BABA","CNY",null) ;
        Assertions.assertNotNull(values);
    }

    private List<FinancialIndexValueDTO> queryXbrlValues(String ticker,String unit,FinanceQueryParam query) throws IOException, InterruptedException {
        // parse
        Path workspace = Paths.get(System.getProperty("user.dir")).resolve("./test-filings");
        String userAgent = "io/yiying5@gmail.com" ;
        SecFilingDataUtil tool = new SecFilingDataUtil(workspace,userAgent) ;
        SecFilingDataDTO content = tool.fetchFinancialIndexValue(ticker) ;
        // query
        if(Objects.isNull(query)){
            query = FinanceQueryParam.builder()
                    .ticker(ticker)
                    .reportTypes("Income")
                    .indexCodes("NetIncomeLoss")
                    .fiscalYears("2024,2025")
                    .fiscalPeriods("FY,Q1,Q2,Q3,Q4")
                    .build() ;
        }
        // mapping
        Map<String, Pair<String, String>> dict = Map.of(
//                "Revenue", Pair.of("",""),
                "CostOfRevenue",Pair.of("CostOfRevenue","income")
                ,"GrossProfit",Pair.of("GrossProfit","income")
                ,"AdvertisingExpense",Pair.of("AdvertisingExpense","income")
                ,"GeneralAndAdministrativeExpense",Pair.of("G&M Expense","income")
                ,"ResearchAndDevelopmentExpense",Pair.of("R&D Expense","income")
                ,"OperatingExpenses",Pair.of("OperatingExpenses","income")
                ,"OperatingIncomeLoss",Pair.of("OperatingIncomeLoss","income")
                ,"NetIncomeLoss",Pair.of("NetIncomeLoss","income")
        ) ;
        List<FinancialIndexValueDTO> indexes = SecFilingFilterUtil.filter(content, query, unit, dict) ;
        System.out.println(JSON.toJSONString(indexes));
        return indexes ;
    }

    @Test
    void test_view() throws IOException, InterruptedException {
        // parse
        String ticker = "LI" ;
        Path workspace = Paths.get(System.getProperty("user.dir")).resolve("./test-filings");
        String userAgent = "io/yiying5@gmail.com" ;
        SecFilingDataUtil tool = new SecFilingDataUtil(workspace,userAgent) ;
        SecFilingDataDTO content = tool.fetchFinancialIndexValue(ticker) ;
        List<FinancialIndexValueDTO> indexes = SecFilingFilterUtil.filter(content, FinanceQueryParam.builder().ticker(ticker).build(), "CNY", null) ;
        System.out.println("-------------revenues--------------");
        Assertions.assertNotNull(indexes);
        this.printLabel(indexes,"27009779");
        System.out.println("-------------cost--------------");
        this.printLabel(indexes,"21248325");
        System.out.println("-------------margin--------------");
        this.printLabel(indexes,"5761454");
        System.out.println("-------------r&d--------------");
        this.printLabel(indexes,"3286389");
        System.out.println("-------------s&m--------------");
        this.printLabel(indexes,"3492385");
//        System.out.println("-------------g&A--------------");
//        this.printLabel(indexes,"7552967");
        System.out.println("-------------expense--------------");
        this.printLabel(indexes,"6778774");
        System.out.println("-------------operating income--------------");
        this.printLabel(indexes,"-1017320");
        System.out.println("-------------net income--------------");
        this.printLabel(indexes,"-321455");
    }

    private void printLabel(List<FinancialIndexValueDTO> indexes,String prefix){
        List<FinancialIndexValueDTO> items = indexes.stream().filter(t->t.getValue().startsWith(prefix)).toList() ;
        System.out.println(JSON.toJSONString(items));
    }

    @Test
    void buildTickerDict_returnsProvidedDict() {
        Path workspace = Paths.get(System.getProperty("user.dir")).resolve("./test-filings");
        SecFilingDataUtil tool = new SecFilingDataUtil(workspace,"io/yiying5@gmail.com") ;
        Map<String, Pair<String, String>> dict = Map.of("CustomMetric", Pair.of("CustomMetric", "income"));
        Assertions.assertSame(dict, tool.buildTickerDict("AAPL", dict));
    }

    @Test
    void fetchFinancialIndexValue_cacheOnlyUsesExistingCache() throws IOException, InterruptedException {
        Path workspace = Paths.get(System.getProperty("user.dir")).resolve("./test-filings");
        SecFilingDataUtil tool = new SecFilingDataUtil(workspace,"io/yiying5@gmail.com") ;
        SecFilingDataDTO content = tool.fetchFinancialIndexValue("AAPL", false);
        Assertions.assertNotNull(content);
        Assertions.assertTrue(tool.hasCompanyFactsCache("AAPL"));
    }

}