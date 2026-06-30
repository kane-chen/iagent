package io.invest.iagent.tools.filing;

import com.alibaba.fastjson2.JSON;
import io.invest.iagent.model.FinancialIndexValueDTO;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;

public class FinancialMetricsQueryToolIntegrationTest {

    private FinancialMetricsQueryTool tool;

    @BeforeEach
    public void before(){
        Path workspace = Paths.get(System.getProperty("user.dir")).resolve("./test-filings");
        String userAgent = "io/yiying5@gmail.com";
        tool =  new FinancialMetricsQueryTool(workspace, userAgent);
    }

    @Test
    void queryApple() {
        String ticker = "AAPL";
        String metrics = "Revenue,CostOfRevenue,OperatingExpenses,OperatingIncomeLoss";
        List<FinancialIndexValueDTO> items = tool.queryFinancialMetrics(ticker, metrics,"2025","2026","Q1,Q2", "auto");
        System.out.println(JSON.toJSONString(items));
        Assertions.assertThat(Objects.requireNonNull(items)).isNotNull();

    }

    @Test
    void queryPdd() {
        String ticker = "PDD";
        String metrics = "Revenue,CostOfRevenue,OperatingExpenses,OperatingIncomeLoss";
        List<FinancialIndexValueDTO> items = tool.queryFinancialMetrics(ticker, metrics,"2024","2026","Q1,Q2", "local");
        System.out.println(JSON.toJSONString(items));
        Assertions.assertThat(Objects.requireNonNull(items)).isNotNull();
    }

    @Test
    void queryLi2() {
        String ticker = "LI";
        String metrics = "Revenue,CostOfRevenue,OperatingIncomeLoss,NetIncomeLoss";
        List<FinancialIndexValueDTO> items = tool.queryFinancialMetrics(ticker, metrics,"2024","2025","Q1,Q2,Q3,Q4", "local");
        System.out.println(JSON.toJSONString(items));
        Assertions.assertThat(Objects.requireNonNull(items)).isNotNull();
    }


    @Test
    void queryLi() {
        String ticker = "LI";
        String metrics = "Revenue,CostOfRevenue,OperatingExpenses,OperatingIncomeLoss";
        List<FinancialIndexValueDTO> items = tool.queryFinancialMetrics(ticker, metrics,"2025","2026",null, "local");
        System.out.println(JSON.toJSONString(items));
        Assertions.assertThat(Objects.requireNonNull(items)).isNotNull();
    }

}
