package io.invest.iagent.financial.service;

import io.invest.AgentConfig4Test;
import io.invest.iagent.financial.ingest.RagMetricExtractor;
import io.invest.iagent.financial.model.MetricSource;
import io.invest.iagent.financial.model.MetricValueDO;
import io.invest.iagent.financial.model.PeriodType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@SpringBootTest(classes = AgentConfig4Test.class)
@TestPropertySource(locations = "classpath:test.properties")
class FinancialIngestServiceTest {

    @Autowired
    private FinancialIngestService financialIngestService ;

    @Autowired
    private RagMetricExtractor ragMetricExtractor ;

    @Test
    public void test_build_00700(){
        String ticker = "83690" ;
        int periods = 3 ;
        FinancialIngestService.BuildResult result = financialIngestService.build(ticker,periods) ;
        Assertions.assertTrue(result.success());
    }

    @Test
    public void test_build_baba_capex_manu(){
        String ticker = "BABA" ;
        String metricCode = "NON_GAAP_CAPEX" ;
        Map<String,Integer> values = new HashMap<>() ;
        values.put("2026Q2",67680);
        values.put("2026Q1",26890);
        values.put("2025Q4",29000);
        values.put("2025Q3",31500);
        values.put("2025Q2",38600);
        values.put("2025Q1",24610);
        values.put("2024Q4",31780);
        values.put("2024Q3",17490);
        values.put("2024Q2",12090);
        values.put("2024Q1",11150);
        values.put("2023Q4", 8860);
        values.put("2023Q3", 5150);
        values.put("2023Q2", 6930);
        values.put("2023Q1", 3480);
        values.put("2022Q4", 6900);
        values.put("2022Q3",12110);
        values.put("2022Q2",11840);
        values.put("2022Q1",11500);
        values.put("2021Q4",13350);
        values.put("2021Q3",15940);
        values.put("2021Q2",12520);
        values.put("2021Q1", 7690);
        values.put("2020Q4", 5840);
        values.put("2020Q3",14280);
        List<MetricValueDO> metricValues = this.buildMetricValue4CapEx(ticker,metricCode,values) ;
        financialIngestService.buildResult(metricValues);
    }

    @Test
    public void test_build_00700_buyback_manu(){
        String ticker = "00700" ;
        String metricCode = "SHARE_BUYBACK" ;
        Map<String,Integer> values = Map.of(
                "2026Q2",16900,
                "2026Q1",7600,
                "2025Q4",22400,
                "2025Q3",21200,
                "2025Q2",19400,
                "2025Q1",17100,
                "2024Q4",23700,
                "2024Q3",35900,
                "2024Q2",37500,
                "2024Q1",14800
        ) ;
        List<MetricValueDO> metricValues = this.buildMetricValue4CapEx(ticker,metricCode,values) ;
        financialIngestService.buildResult(metricValues);
    }

    @Test
    public void test_build_00700_paid_manu(){
        String ticker = "00700" ;
        String metricCode = "DIVIDENDS_PAID" ;
        Map<String,Integer> values = Map.of(
                "2026Q2",48600,
                "2025Q2",41000,
                "2024Q2",31700,
                "2023Q2",22900
        ) ;
        List<MetricValueDO> metricValues = this.buildMetricValue4CapEx(ticker,metricCode,values) ;
        financialIngestService.buildResult(metricValues);
    }

    private List<MetricValueDO> buildMetricValue4CapEx(String ticker,String metricCode ,Map<String,Integer> values){
        Integer confidence = 95 ;
        return values.entrySet().stream()
                .map(t-> this.buildMetricValue(ticker,t.getKey(),metricCode,BigDecimal.valueOf(t.getValue()),confidence))
                .toList() ;
    }

    private MetricValueDO buildMetricValue(String ticker, String period, String metricCode, BigDecimal value,Integer confidence){
        return MetricValueDO.builder()
                .ticker(ticker)
                .fiscalPeriod(period)
                .periodType(PeriodType.SINGLE_Q.name())
                .metricCode(metricCode)
                .value(value)
                .unit("million")
                .source(MetricSource.MANU.name())
                .confidence(confidence)
                .build();
    }

}