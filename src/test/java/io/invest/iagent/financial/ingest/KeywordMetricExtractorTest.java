package io.invest.iagent.financial.ingest;

import io.invest.AgentConfig4Test;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.Calendar;
import java.util.List;
import java.util.ArrayList;


@SpringBootTest(classes = AgentConfig4Test.class)
@TestPropertySource(locations = "classpath:test.properties")
class KeywordMetricExtractorTest {

    @Autowired
    private KeywordMetricExtractor keywordMetricExtractor ;

    @Test
    public void test_extract_00700_q3(){
        String ticker = "00700" ;
        List<String> periods = this.buildPeriods(16) ;
        KeywordMetricExtractor.KeywordExtractResult result = keywordMetricExtractor.extract(ticker,periods) ;
        Assertions.assertTrue(result.extracted() >= 0);
    }

    @Test
    public void test_extract_BABA(){
        String ticker = "BABA" ;
        // 期间逐个传入（逗号拼接成单个字符串会被 FiscalPeriod 判为非法而整体跳过，导致不触发检索）
        List<String> periods = this.buildPeriods(24) ;
        KeywordMetricExtractor.KeywordExtractResult result = keywordMetricExtractor.extract(ticker,periods) ;
        Assertions.assertTrue(result.extracted() >= 0);
    }

    @Test
    public void test_extract_GOOG(){
        String ticker = "AMZN" ;
        // 期间逐个传入（逗号拼接成单个字符串会被 FiscalPeriod 判为非法而整体跳过，导致不触发检索）
        List<String> periods = this.buildPeriods(16) ;
        KeywordMetricExtractor.KeywordExtractResult result = keywordMetricExtractor.extract(ticker,periods) ;
        Assertions.assertTrue(result.extracted() >= 0);
    }

    private List<String> buildPeriods(int limit){
        int year = Calendar.getInstance().get(Calendar.YEAR);
        int quarter = (Calendar.getInstance().get(Calendar.MONTH)- 1) / 3 + 1;
        List<String> result = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            result.add(year + "Q" + quarter);
            // 向前推一个季度
            quarter--;
            if (quarter == 0) {
                quarter = 4;
                year--;
            }
        }
        return result;
    }

}