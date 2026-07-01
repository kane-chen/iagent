package io.invest.iagent.service.extraction.service;

import com.google.common.collect.Lists;
import lombok.Data;
import io.invest.iagent.service.extraction.config.CompanyConfigLoader;
import io.invest.iagent.service.extraction.extractor.DataExtractor;
import io.invest.iagent.service.extraction.mapper.MetricMapper;
import io.invest.iagent.service.extraction.model.*;
import io.invest.iagent.service.extraction.parser.HtmlReportParser;
import io.invest.iagent.service.extraction.parser.PdfReportParser;
import io.invest.iagent.service.extraction.parser.ReportParser;
import io.invest.iagent.service.extraction.recognizer.SegmentRecognizer;
import io.invest.iagent.service.extraction.validator.QualityValidator;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.CollectionUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 财务数据提取主服务
 * 整合所有模块，提供统一的提取接口
 */
@Slf4j
public class FinancialExtractionService {

    private final ReportParser htmlReportParser;
    private final PdfReportParser pdfReportParser;
    private final MetricMapper metricMapper;
    private final QualityValidator qualityValidator;
    private final CompanyConfigLoader configLoader;
    private final FinancialFileFilter fileFilter;
    private SegmentRecognizer segmentRecognizer;
    private DataExtractor dataExtractor;
    @Getter
    private CompanyConfig companyConfig;

    public FinancialExtractionService(Path workspace) {
        this.metricMapper = new MetricMapper();
        this.htmlReportParser = new HtmlReportParser();
        this.pdfReportParser = new PdfReportParser();
        this.pdfReportParser.setWorkspace(workspace);
        this.qualityValidator = new QualityValidator();
        this.fileFilter = new FinancialFileFilter(workspace) ;
        this.configLoader = new CompanyConfigLoader();
    }

    /**
     * 使用指定公司代码初始化
     */
    public FinancialExtractionService(String companyCode,Path workspace) {
        this(workspace);
        this.companyConfig = configLoader.loadConfig(companyCode);
        this.segmentRecognizer = new SegmentRecognizer(companyConfig);
        this.dataExtractor = new DataExtractor(segmentRecognizer, metricMapper);
        this.pdfReportParser.setCompanyConfig(companyConfig);
    }

    /**
     * 使用公司配置初始化
     */
    public FinancialExtractionService(CompanyConfig companyConfig,Path workspace) {
        this(workspace);
        this.companyConfig = companyConfig;
        this.segmentRecognizer = new SegmentRecognizer(companyConfig);
        this.dataExtractor = new DataExtractor(segmentRecognizer, metricMapper);
        this.pdfReportParser.setCompanyConfig(companyConfig);
    }

    public List<Segment> extractFromHtmlFile(String tickerCode,String fiscalYearStart,String fiscalYearEnd) throws IOException {
        List<Path> files = fileFilter.filter(tickerCode, fiscalYearStart, fiscalYearEnd);
        if(CollectionUtils.isEmpty(files)){
            return List.of() ;
        }
        List<Segment> segments = Lists.newArrayList() ;
        for(Path file : files){
            try {
                List<Segment> subs = extractFromHtmlFile(file.toFile());
                if(Objects.nonNull(subs)){
                    segments.addAll(subs) ;
                }
            } catch (IOException e) {
                log.error("extract failed:{}",file.toAbsolutePath(),e);
                return null;
            }
        }

        return SegmentMetricUtil.merge(segments);
    }



    /**
     * 从文件中提取财务数据（支持HTML和PDF）
     */
    public List<Segment> extractFromHtmlFile(File file) throws IOException {
        String fileName = file.getName().toLowerCase();
        log.info("Extracting financial data from file: {}", fileName);

        if (fileName.endsWith(".pdf")) {
            // PDF文件：直接由 PdfReportParser 通过 mapping 输出 Segment，
            // 不经过 HTML 那套基于标签匹配的 DataExtractor —— 因为港股 PDF 字体乱码后
            // 没有可识别的中文标签，只能靠"位置映射"。
            List<Segment> segments = pdfReportParser.parseSegments(file);
            log.info("PDF parser produced {} segments for file {}", segments.size(), fileName);
            return segments;
        }

        // HTML文件使用HTML解析器
        List<FinancialTable> tables = htmlReportParser.parse(file);
        log.info("Parsed file {} financial tables", tables.size());

        // 2. 从表格中提取分部数据
        List<Segment> segments = dataExtractor.extractFromMultipleTables(tables);
        log.info("Extracted file {} segments with financial data", segments.size());

        return segments;
    }

    /**
     * 从HTML内容字符串中提取财务数据
     */
    public List<Segment> extractFromHtmlContent(String htmlContent) {
        log.info("Extracting financial data from HTML content, length: {}", htmlContent.length());

        // 1. 解析HTML，提取表格
        List<FinancialTable> tables = htmlReportParser.parseHtml(htmlContent);
        log.info("Parsed {} financial tables", tables.size());

        // 2. 从表格中提取分部数据
        List<Segment> segments = dataExtractor.extractFromMultipleTables(tables);
        log.info("Extracted {} segments with financial data", segments.size());

        return segments;
    }

    /**
     * 提取并进行质量校验
     */
    public ExtractionResult extractAndValidate(File htmlFile) throws IOException {
        List<Segment> segments = extractFromHtmlFile(htmlFile);
        ValidationResult validationResult = qualityValidator.validate(segments);
        
        ExtractionResult result = new ExtractionResult();
        result.setSegments(segments);
        result.setValidationResult(validationResult);
        result.setCompanyConfig(companyConfig);
        
        return result;
    }

    public void setCompanyConfig(CompanyConfig companyConfig) {
        this.companyConfig = companyConfig;
        this.segmentRecognizer = new SegmentRecognizer(companyConfig);
        this.dataExtractor = new DataExtractor(segmentRecognizer, metricMapper);
        this.pdfReportParser.setCompanyConfig(companyConfig);
    }

    /**
     * 提取结果封装
     */
    @Data
    public static class ExtractionResult {
        private List<Segment> segments;
        private ValidationResult validationResult;
        private CompanyConfig companyConfig;
    }
}
