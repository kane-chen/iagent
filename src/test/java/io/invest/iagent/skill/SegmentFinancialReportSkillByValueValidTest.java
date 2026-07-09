package io.invest.iagent.skill;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.HarnessAgent;
import io.invest.AgentConfig4Test;
import io.invest.iagent.utils.ProcessRunner;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.Assert;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class SegmentFinancialReportSkillByValueValidTest {

    @Test
    public void test_skill_direct_00700() throws Exception {
        List<Map<String, Object>> segments = this.runSkill("00700", 360);
        Assert.isTrue(!segments.isEmpty(), "00700 segments should not be empty");
        Assertions.assertEquals("95860", getValue(segments,buildParams("VAS","REVENUE","2025Q3"),"value"));
        Assertions.assertEquals("58623", getValue(segments,buildParams("VAS","GROSS_PROFIT","2025Q3"),"value"));
        Assertions.assertEquals("82695", getValue(segments,buildParams("VAS","REVENUE","2024Q3"),"value"));
        Assertions.assertEquals("47513", getValue(segments,buildParams("VAS","GROSS_PROFIT","2024Q3"),"value"));
        Assertions.assertEquals("58174", getValue(segments,buildParams("FINTECH","REVENUE","2025Q3"),"value"));
        Assertions.assertEquals("29210", getValue(segments,buildParams("FINTECH","GROSS_PROFIT","2025Q3"),"value"));
        Assertions.assertEquals("25377", getValue(segments,buildParams("FINTECH","GROSS_PROFIT","2024Q3"),"value"));
    }

    @Test
    public void test_skill_direct_83690() throws Exception {
        List<Map<String, Object>> segments = this.runSkill("83690", 360);
        Assert.isTrue(!segments.isEmpty(), "83690 segments should not be empty");
        Assertions.assertEquals("23021", getValue(segments,buildParams("DELIVERY","REVENUE","2025Q3"),"value"));
        Assertions.assertEquals("26375", getValue(segments,buildParams("COMMISSION","REVENUE","2025Q3"),"value"));
        Assertions.assertEquals("81517", getValue(segments,buildParams("LOCAL_SERVICE","COST","2025Q3"),"value"));
        Assertions.assertEquals("14582", getValue(segments,buildParams("LOCAL_SERVICE","OPERATING_INCOME","2024Q3"),"value"));
        Assertions.assertEquals("25230", getValue(segments,buildParams("NEW_SERVICE","COST","2024Q3"),"value"));
    }

    @Test
    public void test_skill_direct_baba() throws Exception {
        List<Map<String, Object>> segments = this.runSkill("BABA", 360);
        Assert.isTrue(!segments.isEmpty(), "alibaba segments should not be empty");
        // BABA fiscal year ends March: Q1 = Jun-ending quarter, Q4 = Mar-ending quarter.
        Assertions.assertEquals("2337", getValue(segments,buildParams("CLOUD_INTELLIGENCE","ADJUSTED_EBITA","2025Q1"),"value"));
        Assertions.assertEquals("916", getValue(segments,buildParams("CLOUD_INTELLIGENCE","ADJUSTED_EBITA","2024Q1"),"value"));
        Assertions.assertEquals("107421", getValue(segments,buildParams("CHINA_COMMERCE_RETAIL","REVENUE","2025Q1"),"value"));
        Assertions.assertEquals("109828", getValue(segments,buildParams("CHINA_COMMERCE_RETAIL","REVENUE","2024Q1"),"value"));
        Assertions.assertEquals("6711", getValue(segments,buildParams("CHINA_COMMERCE_WHOLESALE","REVENUE","2026Q1"),"value"));
        Assertions.assertEquals("89252", getValue(segments,buildParams("CUSTOMER_MANAGEMENT","REVENUE","2026Q1"),"value"));
        Assertions.assertEquals("-59", getValue(segments,buildParams("INTERNATIONAL_DIGITAL_COMMERCE","ADJUSTED_EBITA","2026Q1"),"value"));
    }

    @Test
    public void test_skill_direct_amazon() throws Exception {
        List<Map<String, Object>> segments = this.runSkill("AMZN", 360);
        Assert.isTrue(!segments.isEmpty(), "amazon segments should not be empty");
        System.out.println(JSON.toJSONString(segments));
        Assertions.assertEquals("104143", getValue(segments,buildParams("North_America","REVENUE","2026Q1"),"value"));
        Assertions.assertEquals("92887", getValue(segments,buildParams("North_America","REVENUE","2025Q1"),"value"));
        Assertions.assertEquals("8267", getValue(segments,buildParams("North_America","OPERATING_INCOME","2026Q1"),"value"));
        Assertions.assertEquals("37587", getValue(segments,buildParams("AWS","REVENUE","2026Q1"),"value"));
        Assertions.assertEquals("29267", getValue(segments,buildParams("AWS","REVENUE","2025Q1"),"value"));
        Assertions.assertEquals("11547", getValue(segments,buildParams("AWS","OPERATING_INCOME","2025Q1"),"value"));
    }

    @Test
    public void test_skill_direct_google() throws Exception {
        List<Map<String, Object>> segments = this.runSkill("GOOG", 360);
        Assert.isTrue(!segments.isEmpty(), "google segments should not be empty");
        System.out.println(JSON.toJSONString(segments));
        Assertions.assertEquals("12260", getValue(segments, buildParams("GOOGLE_CLOUD", "REVENUE", "2025Q1"), "value"));
        Assertions.assertEquals("9574", getValue(segments, buildParams("GOOGLE_CLOUD", "REVENUE", "2024Q1"), "value"));
        Assertions.assertEquals("66885", getValue(segments, buildParams("GOOGLE_ADVERTISING", "REVENUE", "2025Q1"), "value"));
        Assertions.assertEquals("61659", getValue(segments, buildParams("GOOGLE_ADVERTISING", "REVENUE", "2024Q1"), "value"));
    }

    @Test
    public void test_skill_direct_beke() throws Exception {
        List<Map<String, Object>> segments = this.runSkill("BEKE", 360);
        Assert.isTrue(!segments.isEmpty(), "google segments should not be empty");
        System.out.println(JSON.toJSONString(segments));
        Assertions.assertNotNull(segments);
        Assertions.assertEquals("6870", getValue(segments, buildParams("Existing_Home", "REVENUE", "2025Q1"), "value"));
        Assertions.assertEquals("8074", getValue(segments, buildParams("New_Home", "REVENUE", "2025Q1"), "value"));
        Assertions.assertEquals("-4252", getValue(segments, buildParams("Existing_Home", "COST", "2025Q1"), "value"));
        Assertions.assertEquals("2618", getValue(segments, buildParams("Existing_Home", "OPERATING_INCOME", "2025Q1"), "value"));
        Assertions.assertEquals("6132", getValue(segments, buildParams("Existing_Home", "REVENUE", "2026Q1"), "value"));
        Assertions.assertEquals("5086", getValue(segments, buildParams("New_Home", "REVENUE", "2026Q1"), "value"));
    }

    @Test
    public void test_skill_direct_tcom() throws Exception {
        List<Map<String, Object>> segments = this.runSkill("TCOM", 120);
        System.out.println(JSON.toJSONString(segments));
        Assert.isTrue(!segments.isEmpty(), "tcom segments should not be empty");
        Assertions.assertNotNull(segments);
        Assertions.assertEquals("8047", getValue(segments, buildParams("Hotel", "REVENUE", "2025Q3"), "value"));
        Assertions.assertEquals("6225", getValue(segments, buildParams("Hotel", "REVENUE", "2025Q2"), "value"));
        Assertions.assertEquals("6802", getValue(segments, buildParams("Hotel", "REVENUE", "2024Q3"), "value"));
        Assertions.assertEquals("5541", getValue(segments, buildParams("Hotel", "REVENUE", "2025Q1"), "value"));
        Assertions.assertEquals("4496", getValue(segments, buildParams("Hotel", "REVENUE", "2024Q1"), "value"));
        Assertions.assertEquals("6306", getValue(segments, buildParams("Ticket", "REVENUE", "2025Q3"), "value"));
        Assertions.assertEquals("5650", getValue(segments, buildParams("Ticket", "REVENUE", "2024Q3"), "value"));
        Assertions.assertEquals("1606", getValue(segments, buildParams("Tour", "REVENUE", "2025Q3"), "value"));
        Assertions.assertEquals("1558", getValue(segments, buildParams("Tour", "REVENUE", "2024Q3"), "value"));
    }

    @Test
    public void test_skill_direct_microsoft() throws Exception {
        List<Map<String, Object>> segments = this.runSkill("MSFT", 360);
        Assert.isTrue(!segments.isEmpty(), "google segments should not be empty");
        Assertions.assertNotNull(segments);
        Assertions.assertEquals("29944", getValue(segments, buildParams("PRODUCTIVITY_BUSINESS", "REVENUE", "2025Q3"), "value"));
        Assertions.assertEquals("19570", getValue(segments, buildParams("PRODUCTIVITY_BUSINESS", "REVENUE", "2024Q3"), "value"));
        Assertions.assertEquals("17379", getValue(segments, buildParams("PRODUCTIVITY_BUSINESS", "OPERATING_INCOME", "2025Q3"), "value"));
        Assertions.assertEquals("10143", getValue(segments, buildParams("PRODUCTIVITY_BUSINESS", "OPERATING_INCOME", "2024Q3"), "value"));
        Assertions.assertEquals("26751", getValue(segments, buildParams("INTELLIGENT_CLOUD", "REVENUE", "2025Q3"), "value"));
        Assertions.assertEquals("11095", getValue(segments, buildParams("INTELLIGENT_CLOUD", "OPERATING_INCOME", "2025Q3"), "value"));
        Assertions.assertEquals("3526", getValue(segments, buildParams("PERSONAL_COMPUTING", "OPERATING_INCOME", "2025Q3"), "value"));
    }

    private List<Pair<String,String>> buildParams(String segmentCode,String metricCode,String period){
        return List.of(
                Pair.of("segmentCode", segmentCode),
                Pair.of("metricCode", metricCode),
                Pair.of("period", period)
        );
    }

    private String getValue(List<Map<String, Object>> records, List<Pair<String,String>> params,String key) {
        for (Map<String, Object> record : records) {
            boolean allMatch = params.stream().allMatch(param -> record.get(param.getLeft()).equals(param.getRight())) ;
            if(allMatch){
                Object value = record.get( key) ;
                if(Objects.isNull(value)){
                    return null ;
                }
                if(value instanceof BigDecimal){
                    return ((BigDecimal) value).setScale(0).toPlainString();
                }
                return value.toString();
            }
        }
        return null ;
    }

    private List<Map<String, Object>> runSkill(String ticker, int timeoutSeconds) throws Exception {
        Path projectRoot = Paths.get(System.getProperty("user.dir"));
        Path script = projectRoot.resolve("workspace/skills/segment-financial-report/scripts/extract_segments.py");
        Assertions.assertTrue(Files.exists(script), "extract script missing at " + script);

        // 1) 调用 extract_segments.py （不加 --excel，输出 JSON；脚本默认写入 workspace/temp/ 并将 JSON 路径打印到 stdout）
        List<String> cmd = List.of(
                "python", script.toString(),
                "--ticker", ticker
        );
        ProcessRunner.Result result = ProcessRunner.run(cmd, projectRoot, timeoutSeconds);
        Assertions.assertEquals(0, result.getExitCode(),
                "extract_segments.py failed, stderr: " + result.getStderr());

        // 2) 读取 stdout 拿到 JSON 文件路径（脚本最后一行 print(str(json_path))）
        String stdout = result.getStdout().trim();
        String jsonPathStr = stdout.lines()
                .filter(line -> line.endsWith(".json"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "extract_segments.py did not print json path. stdout=" + stdout));
        Path jsonPath = Paths.get(jsonPathStr.trim());
        Assertions.assertTrue(Files.exists(jsonPath), "segment json not found at " + jsonPath);

        // 3) 读取并解析 JSON 文件
        String jsonContent = Files.readString(jsonPath, StandardCharsets.UTF_8);
        List<Map<String, Object>> segments = JSON.parseObject(jsonContent,
                new TypeReference<>() {});
        Assertions.assertNotNull(segments, "parsed segments is null");
        return segments;
    }

}
