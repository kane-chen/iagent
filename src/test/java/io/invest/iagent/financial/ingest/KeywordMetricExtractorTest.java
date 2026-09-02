package io.invest.iagent.financial.ingest;

import io.invest.AgentConfig4Test;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

@SpringBootTest(classes = AgentConfig4Test.class)
@TestPropertySource(locations = "classpath:test.properties")
class KeywordMetricExtractorTest {

    @Autowired
    private KeywordMetricExtractor keywordMetricExtractor ;

    @Test
    public void test_extract_00700_q3(){
        String ticker = "00700" ;
        List<String> periods = List.of("2026Q1","2026Q2");
        KeywordMetricExtractor.KeywordExtractResult result = keywordMetricExtractor.extract(ticker,periods) ;
        Assertions.assertTrue(result.extracted() >= 0);
    }

}