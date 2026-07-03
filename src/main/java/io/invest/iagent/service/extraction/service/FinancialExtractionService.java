package io.invest.iagent.service.extraction.service;

import com.google.common.collect.Lists;
import lombok.Data;
import io.invest.iagent.service.extraction.config.CompanyConfigLoader;
import io.invest.iagent.service.extraction.extractor.DataExtractor;
import io.invest.iagent.service.extraction.extractor.HtmlExtractionSupport;
import io.invest.iagent.service.extraction.extractor.HtmlFileSegmentParser;
import io.invest.iagent.service.extraction.extractor.HtmlReportOrchestrator;
import io.invest.iagent.service.extraction.mapper.MetricMapper;
import io.invest.iagent.service.extraction.model.*;
import io.invest.iagent.service.extraction.parser.FileSegmentParser;
import io.invest.iagent.service.extraction.parser.PdfFileSegmentParser;
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

/**
 * 财务数据提取主服务
 * 整合所有模块，提供统一的提取接口
 */
@Slf4j
public class FinancialExtractionService {

    private final MetricMapper metricMapper;
    private final QualityValidator qualityValidator;
    private final CompanyConfigLoader configLoader;
    private final FinancialFileFilter fileFilter;

    /** 格式无关的 parser 列表：每个 parser 自己决定 supports(File)；按注册顺序首个命中者处理。 */
    private final List<FileSegmentParser> parsers;
    private final HtmlFileSegmentParser htmlParser;
    private final PdfFileSegmentParser pdfParser;

    @Getter
    private CompanyConfig companyConfig;
    private SegmentRecognizer segmentRecognizer;
    private DataExtractor dataExtractor;
    private HtmlReportOrchestrator htmlOrchestrator;

    public FinancialExtractionService(Path workspace) {
        this.metricMapper = new MetricMapper();
        this.qualityValidator = new QualityValidator();
        this.fileFilter = new FinancialFileFilter(workspace);
        this.configLoader = new CompanyConfigLoader();

        this.htmlParser = new HtmlFileSegmentParser();
        this.pdfParser = new PdfFileSegmentParser(workspace);
        this.parsers = List.of(htmlParser, pdfParser);
    }

    /**
     * 使用指定公司代码初始化
     */
    public FinancialExtractionService(String companyCode, Path workspace) {
        this(workspace);
        CompanyConfig cfg = configLoader.loadConfig(companyCode);
        if (cfg != null) {
            configure(cfg);
        }
    }

    /**
     * 使用公司配置初始化
     */
    public FinancialExtractionService(CompanyConfig companyConfig, Path workspace) {
        this(workspace);
        configure(companyConfig);
    }

    /**
     * 批量提取：按 ticker+财年范围过滤出所有候选文件，逐个解析后合并。
     */
    public List<Segment> extractSegments(String tickerCode, String fiscalYearStart, String fiscalYearEnd) throws IOException {
        List<Path> files = fileFilter.filter(tickerCode, fiscalYearStart, fiscalYearEnd);
        if (CollectionUtils.isEmpty(files)) {
            return List.of();
        }
        List<Segment> segments = Lists.newArrayList();
        for (Path file : files) {
            try {
                List<Segment> subs = extractFromFile(file.toFile());
                if (Objects.nonNull(subs)) {
                    segments.addAll(subs);
                }
            } catch (IOException e) {
                log.error("extract failed:{}", file.toAbsolutePath(), e);
            }
        }
        return SegmentMetricUtil.merge(segments);
    }

    /**
     * 从单个文件提取分部数据（自动按扩展名分发给 HTML/PDF parser）。
     */
    public List<Segment> extractFromFile(File file) throws IOException {
        String fileName = file.getName().toLowerCase();
        log.info("Extracting financial data from file: {}", fileName);

        for (FileSegmentParser parser : parsers) {
            if (parser.supports(file)) {
                return parser.parse(file, companyConfig);
            }
        }
        log.warn("No parser supports file: {}", fileName);
        return List.of();
    }

    /**
     * 从HTML内容字符串中提取财务数据
     */
    public List<Segment> extractFromHtmlContent(String htmlContent) {
        log.info("Extracting financial data from HTML content, length: {}", htmlContent.length());
        return htmlParser.parseHtml(htmlContent, companyConfig);
    }

    /**
     * 提取并进行质量校验
     */
    public ExtractionResult extractAndValidate(File file) throws IOException {
        List<Segment> segments = extractFromFile(file);
        ValidationResult validationResult = qualityValidator.validate(segments);

        ExtractionResult result = new ExtractionResult();
        result.setSegments(segments);
        result.setValidationResult(validationResult);
        result.setCompanyConfig(companyConfig);

        return result;
    }

    public void setCompanyConfig(CompanyConfig companyConfig) {
        configure(companyConfig);
    }

    /**
     * 统一的配置入口：构造器和 setCompanyConfig 都走这里，避免重复。
     */
    private void configure(CompanyConfig cfg) {
        this.companyConfig = cfg;
        this.segmentRecognizer = new SegmentRecognizer(cfg);
        this.dataExtractor = new DataExtractor(segmentRecognizer, metricMapper);
        this.htmlOrchestrator = HtmlReportOrchestrator.standard(
                new HtmlExtractionSupport(metricMapper), segmentRecognizer, dataExtractor);
        this.htmlParser.setOrchestrator(htmlOrchestrator);
        this.pdfParser.setCompanyConfig(cfg);
    }

    // --------- 向后兼容方法（旧名保留，委托到新方法） ---------

    /** @deprecated Use {@link #extractSegments(String, String, String)} instead. */
    @Deprecated
    public List<Segment> extractFromHtmlFile(String tickerCode, String fiscalYearStart, String fiscalYearEnd) throws IOException {
        return extractSegments(tickerCode, fiscalYearStart, fiscalYearEnd);
    }

    /** @deprecated Use {@link #extractFromFile(File)} instead. */
    @Deprecated
    public List<Segment> extractFromHtmlFile(File file) throws IOException {
        return extractFromFile(file);
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
