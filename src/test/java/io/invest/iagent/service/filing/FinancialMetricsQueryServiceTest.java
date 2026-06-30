package io.invest.iagent.service.filing;

import com.alibaba.fastjson2.JSON;
import io.invest.iagent.model.FinancialIndexValueDTO;
import io.invest.iagent.service.filing.metrics.FinancialMetricsSourcePreference;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

class FinancialMetricsQueryServiceTest {
    private FinancialMetricsQueryService service ;

    @BeforeEach
    public void init(){
        Path workspace = Paths.get(System.getProperty("user.dir")).resolve("./test-filings");
        String userAgent = "io/yiying5@gmail.com";
        service = new FinancialMetricsQueryService(workspace, userAgent);
    }

    @Test
    public void query_li() throws Exception {
        String ticker = "LI";
        List<FinancialIndexValueDTO> result = service.queryFinancialMetrics(ticker,"Revenue,CostOfRevenue,OperatingExpenses,NetIncomeLoss"
                , "2024",null,"Q1,Q2,Q3,Q4,FY", FinancialMetricsSourcePreference.LOCAL.name());
        System.out.println(JSON.toJSONString(result));
        Assertions.assertNotNull(result);
    }

    @Test
    public void query_APPLE() throws Exception {
        String ticker = "AAPL";
        List<FinancialIndexValueDTO> result = service.queryFinancialMetrics(ticker,"Revenue,CostOfRevenue,OperatingExpenses,NetIncomeLoss"
                , "2024",null,"Q1,Q2,Q3,Q4,FY", FinancialMetricsSourcePreference.LOCAL.name());
        System.out.println(JSON.toJSONString(result));
        Assertions.assertNotNull(result);
    }
}