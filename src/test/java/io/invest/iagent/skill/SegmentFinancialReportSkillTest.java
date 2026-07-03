package io.invest.iagent.skill;

import com.alibaba.fastjson2.JSON;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.HarnessAgent;
import io.invest.AgentConfig4Test;
import io.invest.iagent.service.extraction.model.Segment;
import io.invest.iagent.service.extraction.model.SegmentMetricDTO;
import io.invest.iagent.service.extraction.service.FinancialExtractionService;
import io.invest.iagent.service.extraction.service.SegmentMetricUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.Assert;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;

@SpringBootTest(classes = AgentConfig4Test.class)
@TestPropertySource(locations = "classpath:test.properties")
public class SegmentFinancialReportSkillTest {

    @Autowired
    private HarnessAgent baseAgent ;

    private RuntimeContext context;

    @BeforeEach
    public void init() {
    }

    @Test
    public void test_excel_baba() {
        String companyName = "阿里巴巴";
        Msg response = this.doExecute(companyName);
        String responseText = Objects.requireNonNull(response).getTextContent();
        Assert.notNull(responseText, "question response");
    }

    @Test
    public void test_excel_pdd() {
        String companyName = "拼多多";
        Msg response = this.doExecute(companyName);
        String responseText = Objects.requireNonNull(response).getTextContent();
        Assert.notNull(responseText, "question response");
    }

    @Test
    public void test_excel_google() {
        String companyName = "谷歌";
        Msg response = this.doExecute(companyName);
        String responseText = Objects.requireNonNull(response).getTextContent();
        Assert.notNull(responseText, "question response");
    }

    @Test
    public void test_excel_microsoft() {
        String companyName = "微软";
        Msg response = this.doExecute(companyName);
        String responseText = Objects.requireNonNull(response).getTextContent();
        Assert.notNull(responseText, "question response");
    }

    @Test
    public void test_excel_tencent() {
        String companyName = "腾讯控股";
        Msg response = this.doExecute(companyName);
        String responseText = Objects.requireNonNull(response).getTextContent();
        Assert.notNull(responseText, "question response");
    }

    @Test
    public void test_excel_tcom() {
        String companyName = "携程";
        Msg response = this.doExecute(companyName);
        String responseText = Objects.requireNonNull(response).getTextContent();
        Assert.notNull(responseText, "question response");
    }

    private Msg doExecute(String ticker) {
        String template = """
                生成公司[%s]所有分部的财务报表，
                执行流程如下：
                1、调用技能stock-ticker获取公司的股票代码。
                2、调用技能segment-financial-report生成财务报表。
                3、检查财务报表文件是否创建成功。
                特别注意：
                1、严格禁止只输出执行方式，但不去真正执行。
                2、严格禁止不通过stock-ticker技能获取股票代码。
                """;

        Msg qaMsg = this.buildUserMsg(String.format(template, ticker));
        return baseAgent.call(qaMsg).block();
    }

    private Msg buildUserMsg(String content){
        return Msg.builder()
                .role(MsgRole.USER)
                .textContent(content)
                .build();
    }

    /**
     * 直接调用 Java 引擎跑 flatten 后的 segments，验证提取逻辑；
     * 老版本的 {@code FinancialSegmentMetricsTool.queryFinancialMetricsFlatter} 的等价路径。
     * Excel 渲染现在完全交给 segment-financial-report skill 的 Python 脚本。
     */
    private static List<SegmentMetricDTO> extractSegments(String ticker) throws Exception {
        Path workspace = Paths.get(System.getProperty("user.dir")).resolve("workspace");
        FinancialExtractionService service = new FinancialExtractionService(ticker, workspace);
        List<Segment> segments = service.extractFromHtmlFile(ticker, null, null);
        return SegmentMetricUtil.flattenAndSort(segments);
    }

    @Test
    public void test_tool_baba() throws Exception {
        List<SegmentMetricDTO> segments = extractSegments("BABA");
        Assertions.assertNotNull(segments);
        System.out.println(JSON.toJSONString(segments));
    }

    @Test
    public void test_tool_baba_extract() throws Exception {
        List<SegmentMetricDTO> segments = extractSegments("BABA");
        Assertions.assertNotNull(segments);
    }

    @Test
    public void test_tool_00700_build() throws Exception {
        List<SegmentMetricDTO> segments = extractSegments("00700");
        Assertions.assertNotNull(segments);
    }

    @Test
    public void test_tool_83690_build() throws Exception {
        List<SegmentMetricDTO> segments = extractSegments("83690");
        Assertions.assertNotNull(segments);
    }

    @Test
    public void test_tool_baba_build() throws Exception {
        List<SegmentMetricDTO> segments = extractSegments("BABA");
        Assertions.assertNotNull(segments);
    }

    @Test
    public void test_tool_pdd_build() throws Exception {
        List<SegmentMetricDTO> segments = extractSegments("PDD");
        Assertions.assertNotNull(segments);
    }

    @Test
    public void test_tool_beke_build() throws Exception {
        List<SegmentMetricDTO> segments = extractSegments("BEKE");
        Assertions.assertNotNull(segments);
    }

}
