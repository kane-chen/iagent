package io.invest.iagent.financial.service;

import io.invest.AgentConfig4Test;
import io.invest.iagent.financial.ingest.RagMetricExtractor;
import org.apache.commons.compress.utils.Lists;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

@SpringBootTest(classes = AgentConfig4Test.class)
@TestPropertySource(locations = "classpath:test.properties")
class FinancialIngestServiceTest {

    @Autowired
    private FinancialIngestService financialIngestService ;

    @Autowired
    private RagMetricExtractor ragMetricExtractor ;

    @Test
    public void test_build_00700(){
        String ticker = "00700" ;
        int periods = 25 ;
        FinancialIngestService.BuildResult result = financialIngestService.build(ticker,periods) ;
        Assertions.assertTrue(result.success());
    }

    @Test
    public void test_build_00700_rag(){
        String ticker = "00700" ;
        List<String> periods = List.of("2026Q1","2026Q2") ;
        RagMetricExtractor.RagExtractResult result = ragMetricExtractor.extract(ticker,periods) ;
        Assertions.assertTrue(result.extracted()>0);
    }

}