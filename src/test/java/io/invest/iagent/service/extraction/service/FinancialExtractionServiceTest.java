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
        Assertions.assertEquals(425526D,localValue(getSubSegments(segments,"TAOBAO_TMALL"),"CHINA_COMMERCE_RETAIL","REVENUE","2025FY"));
        Assertions.assertEquals(414414D,localValue(getSubSegments(segments,"TAOBAO_TMALL"),"CHINA_COMMERCE_RETAIL","REVENUE","2024FY"));
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
    public void extract_baba_2025() throws IOException {
        FinancialExtractionService service = new FinancialExtractionService("BABA",workspace);
        List<Segment> segments = service.extractFromHtmlFile("BABA",null,null) ;
        Assertions.assertNotNull(segments);
        System.out.println(JSON.toJSONString(segments));
    }

    @Test
    public void extract_google() throws IOException {
        File file = Paths.get(System.getProperty("user.dir")).resolve("workspace/portfolio/GOOGL/filings/fil_0001652044-25-000043/goog-20250331.htm").toFile() ;
        FinancialExtractionService service = new FinancialExtractionService("GOOGL",workspace);
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

}