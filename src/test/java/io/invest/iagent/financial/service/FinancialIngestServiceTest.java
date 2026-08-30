package io.invest.iagent.financial.service;

import io.invest.AgentConfig4Test;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(classes = AgentConfig4Test.class)
@TestPropertySource(locations = "classpath:test.properties")
class FinancialIngestServiceTest {

    @Autowired
    private FinancialIngestService financialIngestService ;

    @Test
    public void test_build_00700(){
        String ticker = "00700" ;
        int periods = 1 ;
        FinancialIngestService.BuildResult result = financialIngestService.build(ticker,periods) ;
        Assertions.assertTrue(result.success());
    }

}