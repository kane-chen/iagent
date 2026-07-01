package io.invest.iagent.service.extraction.service;

import com.alibaba.fastjson2.JSON;
import io.invest.iagent.service.extraction.model.Segment;
import io.invest.iagent.service.extraction.model.SegmentMetric;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.util.CollectionUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;


class FinancialExtractionServiceTest {

    private final Path workspace = Paths.get(System.getProperty("user.dir")).resolve("workspace");

    @Test
    public void extract_baba() throws IOException {
        File file = Paths.get(System.getProperty("user.dir")).resolve("workspace/portfolio/BABA/filings/fil_0001104659-25-049400/tm2515233d1_ex99-1.htm").toFile() ;
        FinancialExtractionService service = new FinancialExtractionService("BABA",workspace);
        List<Segment> segments = service.extractFromHtmlFile(file) ;
        Assertions.assertNotNull(segments);
        System.out.println(JSON.toJSONString(segments));
        Assertions.assertEquals(2420D,localValue(segments,"CLOUD_INTELLIGENCE","ADJUSTED_EBITA","2025Q1"));
        Assertions.assertEquals(1432D,localValue(segments,"CLOUD_INTELLIGENCE","ADJUSTED_EBITA","2024Q1"));
        Assertions.assertEquals(95581,localValue(getSubSegments(segments,"TAOBAO_TMALL"),"CHINA_COMMERCE_RETAIL","REVENUE","2025Q1"));
        Assertions.assertEquals(88264,localValue(getSubSegments(segments,"TAOBAO_TMALL"),"CHINA_COMMERCE_RETAIL","REVENUE","2024Q1"));
    }

    @Test
    public void extract_baba2() throws IOException {
        File file = Paths.get(System.getProperty("user.dir")).resolve("workspace/portfolio/BABA/filings/fil_0001104659-22-026569/tm227577d1_ex99-1.htm").toFile() ;
        FinancialExtractionService service = new FinancialExtractionService("BABA",workspace);
        List<Segment> segments = service.extractFromHtmlFile(file) ;
        Assertions.assertNotNull(segments);
        System.out.println(JSON.toJSONString(segments));
        Assertions.assertEquals(3831,locale(segments,"CHINA_COMMERCE_WHOLESALE").getMetric("REVENUE","2020Q4").getValue());
        Assertions.assertEquals(101449,locale(segments,"CUSTOMER_MANAGEMENT").getMetric("REVENUE","2020Q4").getValue());
    }

    @Test
    public void extract_baba3() throws IOException {
        File file = Paths.get(System.getProperty("user.dir")).resolve("workspace/portfolio/BABA/filings/fil_0001104659-26-060224/tm2614494d1_ex99-1.htm").toFile() ;
        FinancialExtractionService service = new FinancialExtractionService("BABA",workspace);
        List<Segment> segments = service.extractFromHtmlFile(file) ;
        Assertions.assertNotNull(segments);
        System.out.println(JSON.toJSONString(segments));
        Assertions.assertEquals(5940,locale(segments,"CHINA_COMMERCE_WHOLESALE").getMetric("REVENUE","2026Q1").getValue());
        Assertions.assertEquals(73024,locale(segments,"CUSTOMER_MANAGEMENT").getMetric("REVENUE","2026Q1").getValue());
    }

    public Segment locale(List<Segment> segments,String segmentCode){
        if(CollectionUtils.isEmpty(segments)){
            return null ;
        }
        Segment target = segments.stream()
                .filter(segment -> segment.getSegmentCode().equals(segmentCode))
                .findFirst().orElse(null);
        if(target != null){
            return target ;
        }
        target = segments.stream().map(Segment::getChildren)
                .map(t->locale(t,segmentCode))
                .filter(Objects::nonNull)
                .findFirst().orElse(null) ;
        return target ;
    }

    @Test
    public void extract_pdd() throws IOException {
        File file = Paths.get(System.getProperty("user.dir")).resolve("workspace/portfolio/PDD/filings/fil_0001104659-25-026115/tm259886d1_ex99-1.htm").toFile() ;
        FinancialExtractionService service = new FinancialExtractionService("PDD",workspace);
        List<Segment> segments = service.extractFromHtmlFile(file) ;
        Assertions.assertNotNull(segments);
        System.out.println(JSON.toJSONString(segments));
        Assertions.assertEquals(57011	,locale(segments,"Online_Marketing_Services").getMetric("REVENUE","2024Q4").getValue());
        Assertions.assertEquals(40205	,locale(segments,"Transaction_Services").getMetric("REVENUE","2023Q4").getValue());
    }

    @Test
    public void extract_pdd2() throws IOException {
        File file = Paths.get(System.getProperty("user.dir")).resolve("workspace/portfolio/PDD/filings/fil_0001104659-26-067186/tm2615739d1_ex99-1.htm").toFile() ;
        FinancialExtractionService service = new FinancialExtractionService("PDD",workspace);
        List<Segment> segments = service.extractFromHtmlFile(file) ;
        Assertions.assertNotNull(segments);
        System.out.println(JSON.toJSONString(segments));
        Assertions.assertEquals(48722	,locale(segments,"Online_Marketing_Services").getMetric("REVENUE","2025Q1").getValue());
        Assertions.assertEquals(46950	,locale(segments,"Transaction_Services").getMetric("REVENUE","2025Q1").getValue());
    }

    @Test
    public void extract_microsoft() throws IOException {
        File file = Paths.get(System.getProperty("user.dir")).resolve("workspace/portfolio/MSFT/filings/fil_0000950170-25-061046/msft-20250331.htm").toFile() ;
        FinancialExtractionService service = new FinancialExtractionService("MSFT",workspace);
        List<Segment> segments = service.extractFromHtmlFile(file) ;
        Assertions.assertNotNull(segments);
        System.out.println(JSON.toJSONString(segments));
        Assertions.assertEquals(29944	,locale(segments,"PRODUCTIVITY_BUSINESS").getMetric("REVENUE","2025Q1").getValue());
        Assertions.assertEquals(27113	,locale(segments,"PRODUCTIVITY_BUSINESS").getMetric("REVENUE","2024Q1").getValue());
        Assertions.assertEquals(17379	,locale(segments,"PRODUCTIVITY_BUSINESS").getMetric("OPERATING_INCOME","2025Q1").getValue());
        Assertions.assertEquals(15143	,locale(segments,"PRODUCTIVITY_BUSINESS").getMetric("OPERATING_INCOME","2024Q1").getValue());
        Assertions.assertEquals(26751	,locale(segments,"INTELLIGENT_CLOUD").getMetric("REVENUE","2025Q1").getValue());
        Assertions.assertEquals(11095	,locale(segments,"INTELLIGENT_CLOUD").getMetric("OPERATING_INCOME","2025Q1").getValue());
        Assertions.assertEquals(3526	,locale(segments,"PERSONAL_COMPUTING").getMetric("OPERATING_INCOME","2025Q1").getValue());
    }

    @Test
    public void extract_nvda() throws IOException {
        File file = Paths.get(System.getProperty("user.dir")).resolve("workspace/portfolio/NVDA/filings/fil_0001045810-26-000021/nvda-20260125.htm").toFile() ;
        FinancialExtractionService service = new FinancialExtractionService("NVDA",workspace);
        List<Segment> segments = service.extractFromHtmlFile(file) ;
        Assertions.assertNotNull(segments);
        System.out.println(JSON.toJSONString(segments));
        Assertions.assertEquals(29944	,locale(segments,"Compute_Networking").getMetric("REVENUE","2026Q1").getValue());
        Assertions.assertEquals(17379	,locale(segments,"Compute_Networking").getMetric("OPERATING_INCOME","2026Q1").getValue());
        Assertions.assertEquals(26751	,locale(segments,"Graphics").getMetric("REVENUE","2025Q1").getValue());
        Assertions.assertEquals(11095	,locale(segments,"Graphics").getMetric("OPERATING_INCOME","2025Q1").getValue());
    }



    @Test
    public void extract_baba_2025() throws IOException {
        FinancialExtractionService service = new FinancialExtractionService("BABA",workspace);
        List<Segment> segments = service.extractFromHtmlFile("BABA",null,null) ;
        Assertions.assertNotNull(segments);
        System.out.println(JSON.toJSONString(segments));
    }

    @Test
    public void extract_google() throws IOException {
        File file = Paths.get(System.getProperty("user.dir")).resolve("workspace/portfolio/GOOG/filings/fil_0001652044-25-000043/goog-20250331.htm").toFile() ;
        FinancialExtractionService service = new FinancialExtractionService("GOOG",workspace);
        List<Segment> segments = service.extractFromHtmlFile(file) ;
        Assertions.assertNotNull(segments);
        System.out.println(JSON.toJSONString(segments));
        Assertions.assertEquals(12260,localValue(segments,"GOOGLE_CLOUD","REVENUE","2025Q1"));
        Assertions.assertEquals(9574,localValue(segments,"GOOGLE_CLOUD","REVENUE","2024Q1"));
        Assertions.assertEquals(66885,localValue(getSubSegments(segments,"GOOGLE_SERVICES"),"GOOGLE_ADVERTISING","REVENUE","2025Q1"));
        Assertions.assertEquals(61659,localValue(getSubSegments(segments,"GOOGLE_SERVICES"),"GOOGLE_ADVERTISING","REVENUE","2024Q1"));
    }

    @Test
    public void extract_google_2025() throws IOException {
        FinancialExtractionService service = new FinancialExtractionService("GOOGL",workspace);
        List<Segment> segments = service.extractFromHtmlFile("GOOGL",null,null) ;
        Assertions.assertNotNull(segments);
        System.out.println(JSON.toJSONString(segments));
    }

    private List<Segment> getSubSegments(List<Segment> segments,String segmentCode){
        return segments.stream().filter(s -> s.getSegmentCode().equals(segmentCode)).map(Segment::getChildren).flatMap(Collection::stream).collect(Collectors.toList());
    }

    private Double localValue(List<Segment> segments,String segmentCode, String metricCode,String period){
        Segment segment = segments.stream().filter(s -> s.getSegmentCode().equals(segmentCode)).findFirst().orElse(null);
        return Optional.ofNullable(segment)
                .map(t->t.getMetric(metricCode,period))
                .map(SegmentMetric::getValue).orElse(null);
    }

    @Test
    public void extract_tencent() throws IOException {
        File file = Paths.get(System.getProperty("user.dir")).resolve("workspace/portfolio/00700/filings/fil_hk_00700_2025_Q1/01ce3b69d840860f44c0fc7ebca99ee3.pdf").toFile() ;
        FinancialExtractionService service = new FinancialExtractionService("00700",workspace);
        List<Segment> segments = service.extractFromHtmlFile(file) ;
        Assertions.assertNotNull(segments);
        System.out.println(JSON.toJSONString(segments));
        Assertions.assertEquals(92133	,locale(segments,"VAS").getMetric("REVENUE","2025Q1").getValue());
        Assertions.assertEquals(54911	,locale(segments,"VAS").getMetric("GROSS_PROFIT","2025Q1").getValue());
        Assertions.assertEquals(78629	,locale(segments,"VAS").getMetric("REVENUE","2024Q1").getValue());
        Assertions.assertEquals(45022	,locale(segments,"VAS").getMetric("GROSS_PROFIT","2024Q1").getValue());
        Assertions.assertEquals(54907	,locale(segments,"FINTECH").getMetric("REVENUE","2025Q1").getValue());
        Assertions.assertEquals(27597	,locale(segments,"FINTECH").getMetric("GROSS_PROFIT","2025Q1").getValue());
        Assertions.assertEquals(23851	,locale(segments,"FINTECH").getMetric("GROSS_PROFIT","2024Q1").getValue());
    }

    @Test
    public void extract_tencent2() throws IOException {
        File file = Paths.get(System.getProperty("user.dir")).resolve("workspace/portfolio/00700/filings/fil_hk_00700_2025_Q3/7f2ce4fec910f600e27fc70b47b253e7.pdf").toFile() ;
        FinancialExtractionService service = new FinancialExtractionService("00700",workspace);
        List<Segment> segments = service.extractFromHtmlFile(file) ;
        Assertions.assertNotNull(segments);
        System.out.println(JSON.toJSONString(segments));
        Assertions.assertEquals(95860	,locale(segments,"VAS").getMetric("REVENUE","2025Q3").getValue());
        Assertions.assertEquals(58623	,locale(segments,"VAS").getMetric("GROSS_PROFIT","2025Q3").getValue());
        Assertions.assertEquals(82695	,locale(segments,"VAS").getMetric("REVENUE","2024Q3").getValue());
        Assertions.assertEquals(47513	,locale(segments,"VAS").getMetric("GROSS_PROFIT","2024Q3").getValue());
        Assertions.assertEquals(58174	,locale(segments,"FINTECH").getMetric("REVENUE","2025Q3").getValue());
        Assertions.assertEquals(29210	,locale(segments,"FINTECH").getMetric("GROSS_PROFIT","2025Q3").getValue());
        Assertions.assertEquals(25377	,locale(segments,"FINTECH").getMetric("GROSS_PROFIT","2024Q3").getValue());
    }

}