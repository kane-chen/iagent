package io.invest.iagent.service.filingrag;

import com.alibaba.fastjson2.JSON;
import io.invest.AgentConfig4Test;
import io.invest.iagent.service.filingrag.model.FilingAnswer;
import io.invest.iagent.service.filingrag.model.FilingBuildReport;
import io.invest.iagent.service.filingrag.model.FilingQuery;
import io.invest.iagent.service.filingrag.model.FilingQueryResult;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.assertj.core.api.Assertions ;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = AgentConfig4Test.class)
@TestPropertySource(locations = "classpath:test.properties")
class FilingRagServiceTest {

    @Autowired
    private FilingRagService filingRagService ;

    @Test
    void test_buildDocument() {
        FilingBuildReport report = filingRagService.buildDocument("AAPL","fil_0000320193-26-000006", true);
        assertNotNull(report);
    }

    @Test
    void test_search() {
        FilingQuery query = FilingQuery.builder()
                .ticker("AAPL")
                .fromFiscalYear(2026)
                .question("What is the revenue of AAPL in 2026?")
                .build();
        FilingQueryResult report = filingRagService.search(query);
        assertNotNull(report);
    }

    @Test
    void test_buildDocument_83690() {
        FilingBuildReport report = filingRagService.buildDocument("83690","fil_hk_83690_2026_Q1", true);
        assertNotNull(report);
    }

    @Test
    void test_search_83690() {
        FilingQuery query = FilingQuery.builder()
                .ticker("83690")
                .fromFiscalYear(2026)
                .question("核心本地商业分部经营亏损减少的原因")
                .build();
        FilingQueryResult report = filingRagService.search(query);
        assertNotNull(report);
        System.out.println(JSON.toJSONString(report));
    }

    @Test
    void test_answer_83690() {
        FilingQuery query = FilingQuery.builder()
                .ticker("83690")
                .fromFiscalYear(2026)
                .question("核心本地商业分部经营利润亏损减少的原因")
                .build();
        FilingAnswer report = filingRagService.answer(query);
        assertNotNull(report);
        assertNotNull(report.getAnswer());
        Assertions.assertThat(report.getAnswer()).containsAnyOf("即时配送业务亏损大幅减少");
    }

}