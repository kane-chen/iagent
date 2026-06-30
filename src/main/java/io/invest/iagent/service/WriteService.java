package io.invest.iagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class WriteService {

    private static final String WORKSPACE_DIR = "workspace";
    private static final String REPORTS_DIR = "reports";
    private static final String CHAPTERS_DIR = "chapters";
    private static final String EVIDENCE_DIR = "evidence";
    private static final String SESSIONS_DIR = "sessions";
    private static final int WRITE_CANCELLED_EXIT_CODE = 130;
    
    private final ObjectMapper objectMapper;
    private final Map<String, ChapterMetadata> chapterMetadataCache = new ConcurrentHashMap<>();
    private final AtomicInteger runIdCounter = new AtomicInteger(0);
    private final Set<Integer> activeRuns = ConcurrentHashMap.newKeySet();
    
    public WriteService() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
    
    @Data
    public static class WriteRequest {
        private String ticker;
        private Integer fiscalYear;
        private WriteConfig writeConfig;
        private ExecutionOptions executionOptions;
        private List<String> chapters;
        private Map<String, Object> context;
        private String companyName;
        private Map<String, String> companyMetaSummary;
    }
    
    @Data
    public static class WriteConfig {
        private String writeModelOverrideName;
        private String auditModelOverrideName;
        private Map<String, SceneModelConfig> sceneModels;
        private boolean enableAudit;
        private boolean enableEvidenceAnchoring;
        private String outputFormat;
        private Boolean includeExecutiveSummary;
        private Boolean includeRiskFactors;
        
        public WriteConfig() {
            this.enableAudit = true;
            this.enableEvidenceAnchoring = true;
            this.outputFormat = "``";
            this.includeExecutiveSummary = true;
            this.includeRiskFactors = true;
        }
    }
    
    @Data
    public static class SceneModelConfig {
        private String modelName;
        private Double temperature;
        private Integer maxTokens;
        private String sceneName;
        
        public SceneModelConfig() {
            this.temperature = 0.7;
            this.maxTokens = 4096;
        }
    }
    
    @Data
    public static class ExecutionOptions {
        private String sessionId;
        private Boolean async;
        private Integer timeout;
        private Map<String, Object> extraParams;
        private String concurrencyPolicy;
        
        public ExecutionOptions() {
            this.async = false;
            this.timeout = 300;
            this.concurrencyPolicy = "unbounded";
        }
    }
    
    @Data
    public static class ChapterMetadata {
        private String chapterId;
        private String ticker;
        private Integer fiscalYear;
        private LocalDateTime generatedAt;
        private String status;
        private List<String> evidenceSources;
        private Map<String, Object> metadata;
        private Integer version;
        private String feedback;
        
        public ChapterMetadata() {
            this.version = 1;
            this.evidenceSources = new ArrayList<>();
            this.metadata = new HashMap<>();
        }
    }
    
    @Data
    public static class WriteResult {
        private int exitCode;
        private String reportPath;
        private List<ChapterResult> chapters;
        private Map<String, Object> metadata;
        private LocalDateTime completedAt;
        private String errorMessage;
        
        public WriteResult() {
            this.chapters = new ArrayList<>();
            this.metadata = new HashMap<>();
        }
    }
    
    @Data
    public static class ChapterResult {
        private String chapterId;
        private String content;
        private String status;
        private List<String> evidenceAnchors;
        private LocalDateTime generatedAt;
        private Integer version;
        private List<String> auditNotes;
        private Map<String, Object> metrics;
        
        public ChapterResult() {
            this.evidenceAnchors = new ArrayList<>();
            this.auditNotes = new ArrayList<>();
            this.metrics = new HashMap<>();
            this.version = 1;
        }
    }

    /**
     * 运行报告写作 pipeline（主入口）
     * 
     * @param request 写作请求
     * @return 退出码（0=成功，130=取消，1=错误）
     */
    public int run(WriteRequest request) {

        if (request == null || request.getTicker() == null) {
            log.error("Invalid write request");
            return 1;
        }

        log.info("Starting write pipeline for ticker: {}, fiscal year: {}",
                request.getTicker(), request.getFiscalYear());


        String sessionId = createSession();
        int runId = runIdCounter.incrementAndGet();
        
        try {
            return runPipelineWithObserver(request, sessionId, runId);
        } catch (Exception e) {
            log.error("Pipeline execution failed", e);
            return 1;
        } finally {
            cleanupSession(sessionId);
        }
    }
    
    /**
     * 带观察器的 pipeline 执行
     */
    private int runPipelineWithObserver(WriteRequest request, String sessionId, int runId) {
        log.info("Running pipeline with session: {}, runId: {}", sessionId, runId);
        
        registerActiveRun(runId);
        try {
            HostedRunContext context = new HostedRunContext();
            context.setRunId(String.valueOf(runId));
            context.setSessionId(sessionId);
            context.setCancellationToken(new CancellationToken());
            
            return runPipelineWithContext(request, sessionId, context);
        } finally {
            clearActiveRun(runId);
        }
    }
    
    /**
     * 带上下文的 pipeline 执行
     */
    private int runPipelineWithContext(WriteRequest request, String sessionId, HostedRunContext context) {
        if (context.getCancellationToken().isCancelled()) {
            log.warn("Pipeline cancelled before start");
            return WRITE_CANCELLED_EXIT_CODE;
        }
        
        try {
            return runPipeline(request, sessionId);
        } catch (Exception e) {
            if (context.getCancellationToken().isCancelled()) {
                log.info("Pipeline was cancelled during execution");
                return WRITE_CANCELLED_EXIT_CODE;
            }
            throw e;
        }
    }
    
    /**
     * 核心 pipeline 执行逻辑
     */
    private int runPipeline(WriteRequest request, String sessionId) {
        try {
            ExecutionOptions mainOptions = buildExecutionOptionsWithModelOverride(
                    request.getExecutionOptions(),
                    request.getWriteConfig() != null ? request.getWriteConfig().getWriteModelOverrideName() : null
            );
            
            ExecutionOptions auditOptions = buildExecutionOptionsWithModelOverride(
                    request.getExecutionOptions(),
                    request.getWriteConfig() != null ? request.getWriteConfig().getAuditModelOverrideName() : null
            );
            
            Map<String, SceneModelConfig> sceneModels = resolveWriteSceneModels(
                    mainOptions,
                    auditOptions
            );
            
            WriteConfig resolvedConfig = request.getWriteConfig() != null ? 
                    request.getWriteConfig() : new WriteConfig();
            resolvedConfig.setSceneModels(sceneModels);
            
            return executeWritePipeline(
                    request,
                    resolvedConfig,
                    mainOptions,
                    sessionId
            );
            
        } catch (Exception e) {
            log.error("Error running write pipeline", e);
            return 1;
        }
    }
    
    /**
     * 构建带模型覆盖的执行选项
     */
    private ExecutionOptions buildExecutionOptionsWithModelOverride(
            ExecutionOptions baseOptions, 
            String modelName) {
        
        ExecutionOptions options = baseOptions != null ? baseOptions : new ExecutionOptions();
        
        if (modelName != null && !modelName.isEmpty()) {
            if (options.getExtraParams() == null) {
                options.setExtraParams(new HashMap<>());
            }
            options.getExtraParams().put("model_override", modelName);
            log.debug("Applied model override: {}", modelName);
        }
        
        return options;
    }
    
    /**
     * 解析场景模型配置
     */
    private Map<String, SceneModelConfig> resolveWriteSceneModels(
            ExecutionOptions mainOptions,
            ExecutionOptions auditOptions) {
        
        Map<String, SceneModelConfig> sceneModels = new HashMap<>();
        
        SceneModelConfig writeModel = new SceneModelConfig();
        writeModel.setSceneName("write");
        writeModel.setModelName(mainOptions.getExtraParams() != null ? 
                (String) mainOptions.getExtraParams().get("model_override") : "default");
        sceneModels.put("write", writeModel);
        
        SceneModelConfig auditModel = new SceneModelConfig();
        auditModel.setSceneName("audit");
        auditModel.setModelName(auditOptions.getExtraParams() != null ? 
                (String) auditOptions.getExtraParams().get("model_override") : "default");
        sceneModels.put("audit", auditModel);
        
        log.debug("Resolved scene models: write={}, audit={}", 
                writeModel.getModelName(), auditModel.getModelName());
        
        return sceneModels;
    }
    
    /**
     * 执行写作 pipeline
     */
    private int executeWritePipeline(
            WriteRequest request,
            WriteConfig config,
            ExecutionOptions options,
            String sessionId) {
        
        List<String> chapters = request.getChapters() != null ? 
                request.getChapters() : getDefaultChapterOrder();
        
        log.info("Executing pipeline with {} chapters: {}", chapters.size(), chapters);
        
        List<ChapterResult> chapterResults = new ArrayList<>();
        
        for (String chapterId : chapters) {
            try {
                ChapterResult result = generateChapter(
                        request.getTicker(),
                        chapterId,
                        request.getFiscalYear(),
                        config,
                        options,
                        request.getContext()
                );
                
                chapterResults.add(result);
                log.info("Chapter {} completed with status: {}", chapterId, result.getStatus());
                
                if (config.isEnableAudit() && "completed".equals(result.getStatus())) {
                    auditChapter(request.getTicker(), result, config);
                    log.info("Chapter {} audited", chapterId);
                }
                
            } catch (Exception e) {
                log.error("Failed to generate chapter: {}", chapterId, e);
                chapterResults.add(createFailedChapter(chapterId, e.getMessage()));
            }
        }
        
        WriteResult writeResult = assembleWriteResult(request, chapterResults);
        
        try {
            String reportPath = saveReport(request.getTicker(), chapterResults, config);
            writeResult.setReportPath(reportPath);
            saveWriteResult(writeResult, sessionId);
            log.info("Pipeline completed successfully. Report saved to: {}", reportPath);
        } catch (IOException e) {
            log.error("Failed to save write result", e);
            return 1;
        }
        
        return writeResult.getExitCode();
    }
    
    /**
     * 组装写作结果
     */
    private WriteResult assembleWriteResult(WriteRequest request, List<ChapterResult> chapters) {
        WriteResult result = new WriteResult();
        result.setExitCode(0);
        result.setChapters(chapters);
        result.setCompletedAt(LocalDateTime.now());
        result.setMetadata(buildMetadata(request, chapters));
        
        long failedCount = chapters.stream()
                .filter(c -> "failed".equals(c.getStatus()))
                .count();
        
        if (failedCount > 0) {
            log.warn("{} chapters failed to generate", failedCount);
        }
        
        return result;
    }
    
    /**
     * 生成单个章节
     */
    private ChapterResult generateChapter(
            String ticker,
            String chapterId,
            Integer fiscalYear,
            WriteConfig config,
            ExecutionOptions options,
            Map<String, Object> context) throws Exception {
        
        log.info("Generating chapter: {} for ticker: {}", chapterId, ticker);
        
        ChapterResult result = new ChapterResult();
        result.setChapterId(chapterId);
        result.setGeneratedAt(LocalDateTime.now());
        
        String template = loadChapterTemplate(chapterId);
        String content = fillChapterContent(ticker, chapterId, fiscalYear, template, context);
        
        if (config.isEnableEvidenceAnchoring()) {
            List<String> evidenceAnchors = extractEvidenceAnchors(content);
            result.setEvidenceAnchors(evidenceAnchors);
            log.debug("Extracted {} evidence anchors for chapter {}", evidenceAnchors.size(), chapterId);
        }
        
        result.setContent(content);
        result.setStatus("completed");
        
        saveChapterMetadata(ticker, chapterId, fiscalYear, result);
        
        return result;
    }
    
    /**
     * 审计章节
     */
    private void auditChapter(String ticker, ChapterResult chapter, WriteConfig config) {
        log.info("Auditing chapter: {} for ticker: {}", chapter.getChapterId(), ticker);
        
        List<String> auditNotes = performInternalFactCheck(ticker, chapter.getContent());
        chapter.setAuditNotes(auditNotes);
        
        if (!auditNotes.isEmpty()) {
            log.warn("Chapter {} has {} audit notes", chapter.getChapterId(), auditNotes.size());
        }
    }
    
    /**
     * 加载章节模板
     */
    private String loadChapterTemplate(String chapterId) {
        Path templatePath = Paths.get(WORKSPACE_DIR, "recycle", "report_writing", 
                "references", "chapter_templates.md");
        
        try {
            if (Files.exists(templatePath)) {
                String templates = Files.readString(templatePath);
                String extracted = extractTemplateForChapter(templates, chapterId);
                if (extracted != null && !extracted.isEmpty()) {
                    return extracted;
                }
            }
        } catch (IOException e) {
            log.warn("Failed to load chapter template: {}", chapterId, e);
        }
        
        return getDefaultTemplate(chapterId);
    }
    
    /**
     * 填充章节内容
     */
    private String fillChapterContent(String ticker, String chapterId, 
                                     Integer fiscalYear, String template,
                                     Map<String, Object> context) {
        log.debug("Filling chapter content for: {} - {}", ticker, chapterId);
        
        switch (chapterId) {
            case "company_overview":
                return fillCompanyOverview(ticker, fiscalYear, template, context);
            case "financial_analysis":
                return fillFinancialAnalysis(ticker, fiscalYear, template, context);
            case "risk_assessment":
                return fillRiskAssessment(ticker, fiscalYear, template, context);
            default:
                return fillGenericChapter(ticker, chapterId, fiscalYear, template, context);
        }
    }
    
    /**
     * 填充公司概况
     */
    private String fillCompanyOverview(String ticker, Integer fiscalYear, String template, Map<String, Object> context) {
        StringBuilder content = new StringBuilder();
        content.append("# Company Overview\n\n");
        content.append("**Ticker**: ").append(ticker).append("\n\n");
        content.append("**Fiscal Year**: ").append(fiscalYear).append("\n\n");
        
        if (context != null && context.containsKey("companyName")) {
            content.append("**Company Name**: ").append(context.get("companyName")).append("\n\n");
        }
        
        content.append("## Business Description\n");
        content.append("[Company business description to be filled based on latest filings]\n\n");
        content.append("## Market Position\n");
        content.append("[Market position and competitive landscape analysis]\n\n");
        content.append("## Key Products/Services\n");
        content.append("[Main revenue sources and product lines]\n\n");
        
        return content.toString();
    }
    
    /**
     * 填充财务分析
     */
    private String fillFinancialAnalysis(String ticker, Integer fiscalYear, String template, Map<String, Object> context) {
        StringBuilder content = new StringBuilder();
        content.append("# Financial Analysis\n\n");
        content.append("**Ticker**: ").append(ticker).append("\n\n");
        content.append("**Fiscal Year**: ").append(fiscalYear).append("\n\n");
        
        content.append("## Revenue Trend\n");
        content.append("[Revenue analysis with YoY growth rates]\n\n");
        content.append("## Profitability Metrics\n");
        content.append("- Gross Margin: [To be filled]\n");
        content.append("- Operating Margin: [To be filled]\n");
        content.append("- Net Margin: [To be filled]\n\n");
        content.append("## Cash Flow Analysis\n");
        content.append("[Operating, investing, and financing cash flows]\n\n");
        content.append("## Balance Sheet Highlights\n");
        content.append("[Key balance sheet items and ratios]\n\n");
        
        return content.toString();
    }
    
    /**
     * 填充风险评估
     */
    private String fillRiskAssessment(String ticker, Integer fiscalYear, String template, Map<String, Object> context) {
        StringBuilder content = new StringBuilder();
        content.append("# Risk Assessment\n\n");
        content.append("**Ticker**: ").append(ticker).append("\n\n");
        
        content.append("## Risk Factors\n");
        content.append("### Market Risks\n");
        content.append("[Industry and market-related risks]\n\n");
        content.append("### Operational Risks\n");
        content.append("[Company-specific operational risks]\n\n");
        content.append("### Financial Risks\n");
        content.append("[Liquidity, credit, and financial risks]\n\n");
        
        content.append("## Mitigation Strategies\n");
        content.append("[Management's approach to risk mitigation]\n\n");
        
        return content.toString();
    }
    
    /**
     * 填充通用章节
     */
    private String fillGenericChapter(String ticker, String chapterId, Integer fiscalYear, 
                                      String template, Map<String, Object> context) {
        StringBuilder content = new StringBuilder();
        content.append("# ").append(formatChapterTitle(chapterId)).append("\n\n");
        content.append("**Ticker**: ").append(ticker).append("\n\n");
        content.append("**Fiscal Year**: ").append(fiscalYear).append("\n\n");
        content.append("[Content to be generated based on specific requirements]\n\n");
        
        return content.toString();
    }
    
    /**
     * 格式化章节标题
     */
    private String formatChapterTitle(String chapterId) {
        return Arrays.stream(chapterId.split("_"))
                .map(word -> word.substring(0, 1).toUpperCase() + word.substring(1))
                .reduce((a, b) -> a + " " + b)
                .orElse(chapterId);
    }
    
    /**
     * 提取证据锚点
     */
    private List<String> extractEvidenceAnchors(String content) {
        List<String> anchors = new ArrayList<>();
        
        String[] lines = content.split("\n");
        for (String line : lines) {
            if (line.contains("[") && line.contains("]") && !line.trim().startsWith("#")) {
                String anchor = line.trim();
                if (anchor.contains("To be filled") || anchor.contains("based on")) {
                    anchors.add(anchor);
                }
            }
        }
        
        return anchors;
    }
    
    /**
     * 执行事实检查（公开方法供 Tool 调用）
     */
    public List<String> performFactCheck(String ticker, String content) {
        List<String> auditNotes = new ArrayList<>();
        
        if (content.contains("[") && content.contains("]")) {
            auditNotes.add("Contains placeholder content that needs verification");
        }
        
        if (content.toLowerCase().contains("to be filled")) {
            auditNotes.add("Has incomplete sections requiring data input");
        }
        
        if (content.toLowerCase().contains("based on latest")) {
            auditNotes.add("References data that should be verified against source documents");
        }
        
        log.debug("Fact check completed for ticker: {}, found {} notes", ticker, auditNotes.size());
        
        return auditNotes;
    }
    
    /**
     * 执行事实检查（内部使用）
     */
    private List<String> performInternalFactCheck(String ticker, String content) {
        return performFactCheck(ticker, content);
    }
    
    /**
     * 保存报告
     */
    private String saveReport(String ticker, List<ChapterResult> chapters, WriteConfig config) throws IOException {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String reportDir = String.format("%s/%s/%s_%s", 
                WORKSPACE_DIR, REPORTS_DIR, ticker, timestamp);
        
        Path dirPath = Paths.get(reportDir);
        Files.createDirectories(dirPath);
        
        StringBuilder fullReport = new StringBuilder();
        fullReport.append("# Investment Analysis Report\n\n");
        fullReport.append("**Ticker**: ").append(ticker).append("\n");
        fullReport.append("**Generated At**: ").append(timestamp).append("\n");
        
        if (config.getIncludeExecutiveSummary()) {
            fullReport.append("**Report Type**: Full Analysis with Executive Summary\n");
        }
        
        fullReport.append("\n---\n\n");
        
        if (config.getIncludeExecutiveSummary() && !chapters.isEmpty()) {
            fullReport.append(generateExecutiveSummary(chapters));
            fullReport.append("\n---\n\n");
        }
        
        for (ChapterResult chapter : chapters) {
            fullReport.append(chapter.getContent());
            
            if (config.isEnableEvidenceAnchoring() && 
                chapter.getEvidenceAnchors() != null && 
                !chapter.getEvidenceAnchors().isEmpty()) {
                fullReport.append("\n### Evidence & Sources\n");
                for (String anchor : chapter.getEvidenceAnchors()) {
                    fullReport.append("- ").append(anchor).append("\n");
                }
                fullReport.append("\n");
            }
            
            if (chapter.getAuditNotes() != null && !chapter.getAuditNotes().isEmpty()) {
                fullReport.append("### Audit Notes\n");
                for (String note : chapter.getAuditNotes()) {
                    fullReport.append("- ⚠️ ").append(note).append("\n");
                }
                fullReport.append("\n");
            }
            
            fullReport.append("---\n\n");
        }
        
        Path reportFile = dirPath.resolve("report.md");
        Files.writeString(reportFile, fullReport.toString());
        
        log.info("Report saved to: {}", reportFile.toAbsolutePath());
        return reportFile.toAbsolutePath().toString();
    }
    
    /**
     * 生成执行摘要
     */
    private String generateExecutiveSummary(List<ChapterResult> chapters) {
        StringBuilder summary = new StringBuilder();
        summary.append("## Executive Summary\n\n");
        summary.append("This report provides a comprehensive analysis based on the following chapters:\n\n");
        
        for (ChapterResult chapter : chapters) {
            if ("completed".equals(chapter.getStatus())) {
                summary.append("- **").append(formatChapterTitle(chapter.getChapterId()))
                       .append("**: Generated successfully\n");
            }
        }
        
        summary.append("\n*Note: This is an automated analysis. All findings should be verified.*\n\n");
        
        return summary.toString();
    }
    
    /**
     * 保存章节元数据
     */
    private void saveChapterMetadata(String ticker, String chapterId, 
                                    Integer fiscalYear, ChapterResult result) {
        ChapterMetadata metadata = new ChapterMetadata();
        metadata.setChapterId(chapterId);
        metadata.setTicker(ticker);
        metadata.setFiscalYear(fiscalYear);
        metadata.setGeneratedAt(result.getGeneratedAt());
        metadata.setStatus(result.getStatus());
        metadata.setEvidenceSources(result.getEvidenceAnchors());
        metadata.setVersion(result.getVersion());
        
        String key = buildMetadataKey(ticker, chapterId, fiscalYear);
        chapterMetadataCache.put(key, metadata);
        
        log.debug("Saved metadata for chapter: {}", key);
    }
    
    /**
     * 保存写作结果
     */
    private void saveWriteResult(WriteResult result, String sessionId) throws IOException {
        Path sessionDir = Paths.get(WORKSPACE_DIR, SESSIONS_DIR, sessionId);
        Files.createDirectories(sessionDir);
        
        Path resultFile = sessionDir.resolve("write_result.json");
        Files.writeString(resultFile, objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(result));
        
        log.debug("Write result saved to session: {}", sessionId);
    }
    
    /**
     * 创建会话
     */
    private String createSession() {
        String sessionId = UUID.randomUUID().toString();
        try {
            Path sessionDir = Paths.get(WORKSPACE_DIR, SESSIONS_DIR, sessionId);
            Files.createDirectories(sessionDir);
            
            Path sessionInfo = sessionDir.resolve("session_info.json");
            Map<String, Object> info = new HashMap<>();
            info.put("sessionId", sessionId);
            info.put("createdAt", LocalDateTime.now().toString());
            info.put("status", "active");
            
            Files.writeString(sessionInfo, objectMapper.writeValueAsString(info));
        } catch (IOException e) {
            log.error("Failed to create session directory", e);
        }
        return sessionId;
    }
    
    /**
     * 清理会话
     */
    private void cleanupSession(String sessionId) {
        log.debug("Cleaning up session: {}", sessionId);
        try {
            Path sessionDir = Paths.get(WORKSPACE_DIR, SESSIONS_DIR, sessionId);
            Path sessionInfo = sessionDir.resolve("session_info.json");
            
            if (Files.exists(sessionInfo)) {
                Map<String, Object> info = objectMapper.readValue(
                        Files.readString(sessionInfo), Map.class);
                info.put("status", "completed");
                info.put("completedAt", LocalDateTime.now().toString());
                Files.writeString(sessionInfo, objectMapper.writeValueAsString(info));
            }
        } catch (IOException e) {
            log.warn("Failed to update session status", e);
        }
    }
    
    /**
     * 注册活跃运行
     */
    private void registerActiveRun(int runId) {
        activeRuns.add(runId);
        log.debug("Registered active run: {}, total active: {}", runId, activeRuns.size());
    }
    
    /**
     * 清除活跃运行
     */
    private void clearActiveRun(int runId) {
        activeRuns.remove(runId);
        log.debug("Cleared active run: {}, remaining active: {}", runId, activeRuns.size());
    }
    
    /**
     * 检查是否有活跃的取消请求
     */
    public boolean hasActiveCancellation(int runId) {
        return !activeRuns.contains(runId);
    }
    
    /**
     * 构建元数据
     */
    private Map<String, Object> buildMetadata(WriteRequest request, 
                                              List<ChapterResult> chapters) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("ticker", request.getTicker());
        metadata.put("fiscalYear", request.getFiscalYear());
        metadata.put("generatedAt", LocalDateTime.now().toString());
        metadata.put("chapterCount", chapters.size());
        metadata.put("successfulChapters", chapters.stream()
                .filter(c -> "completed".equals(c.getStatus()))
                .count());
        metadata.put("failedChapters", chapters.stream()
                .filter(c -> "failed".equals(c.getStatus()))
                .count());
        
        if (request.getCompanyName() != null) {
            metadata.put("companyName", request.getCompanyName());
        }
        
        return metadata;
    }
    
    /**
     * 构建元数据键
     */
    private String buildMetadataKey(String ticker, String chapterId, Integer fiscalYear) {
        return String.format("%s_%s_%d", ticker, chapterId, fiscalYear);
    }
    
    /**
     * 获取默认章节顺序
     */
    private List<String> getDefaultChapterOrder() {
        return Arrays.asList(
                "company_overview",
                "financial_analysis",
                "risk_assessment"
        );
    }
    
    /**
     * 提取特定章节的模板
     */
    private String extractTemplateForChapter(String templates, String chapterId) {
        String[] lines = templates.split("\n");
        StringBuilder result = new StringBuilder();
        boolean inSection = false;
        
        for (String line : lines) {
            if (line.startsWith("## " + chapterId)) {
                inSection = true;
                continue;
            }
            if (inSection) {
                if (line.startsWith("## ")) {
                    break;
                }
                result.append(line).append("\n");
            }
        }
        
        return result.length() > 0 ? result.toString() : null;
    }
    
    /**
     * 获取默认模板
     */
    private String getDefaultTemplate(String chapterId) {
        return String.format("# %s\n\n[Content to be generated based on latest available data]\n", 
                formatChapterTitle(chapterId));
    }
    
    /**
     * 创建失败的章节结果
     */
    private ChapterResult createFailedChapter(String chapterId, String error) {
        ChapterResult result = new ChapterResult();
        result.setChapterId(chapterId);
        result.setStatus("failed");
        result.setContent(String.format("# %s\n\n**Error**: %s\n\n*This chapter failed to generate. Please review and retry.*\n", 
                formatChapterTitle(chapterId), error));
        result.setGeneratedAt(LocalDateTime.now());
        result.getMetrics().put("error", error);
        return result;
    }
    
    /**
     * 重新生成章节（支持反馈）
     */
    public ChapterResult regenerateChapter(String ticker, String chapterId, 
                                          Integer fiscalYear, String feedback,
                                          WriteConfig config) {
        log.info("Regenerating chapter: {} for ticker: {} with feedback", chapterId, ticker);
        
        try {
            String key = buildMetadataKey(ticker, chapterId, fiscalYear);
            ChapterMetadata existingMetadata = chapterMetadataCache.get(key);
            
            if (existingMetadata != null) {
                existingMetadata.setVersion(existingMetadata.getVersion() + 1);
                existingMetadata.setFeedback(feedback);
            }
            
            Map<String, Object> context = new HashMap<>();
            context.put("feedback", feedback);
            context.put("regeneration", true);
            
            return generateChapter(ticker, chapterId, fiscalYear, config, 
                    new ExecutionOptions(), context);
                    
        } catch (Exception e) {
            log.error("Failed to regenerate chapter: {}", chapterId, e);
            return createFailedChapter(chapterId, "Regeneration failed: " + e.getMessage());
        }
    }
    
    /**
     * 推断公司多维度特征
     */
    public Map<String, Object> inferCompanyFacets(String ticker, List<String> facets) {
        log.info("Inferring company facets for ticker: {}, facets: {}", ticker, facets);
        
        Map<String, Object> inferredFacets = new HashMap<>();
        
        for (String facet : facets) {
            switch (facet.toLowerCase()) {
                case "financial_health":
                    inferredFacets.put(facet, analyzeFinancialHealth(ticker));
                    break;
                case "growth_quality":
                    inferredFacets.put(facet, analyzeGrowthQuality(ticker));
                    break;
                case "competitive_position":
                    inferredFacets.put(facet, analyzeCompetitivePosition(ticker));
                    break;
                case "management_quality":
                    inferredFacets.put(facet, analyzeManagementQuality(ticker));
                    break;
                default:
                    inferredFacets.put(facet, "Analysis pending for facet: " + facet);
            }
        }
        
        return inferredFacets;
    }
    
    private String analyzeFinancialHealth(String ticker) {
        return String.format("Financial health analysis for %s: [Requires detailed financial data]", ticker);
    }
    
    private String analyzeGrowthQuality(String ticker) {
        return String.format("Growth quality analysis for %s: [Requires historical growth metrics]", ticker);
    }
    
    private String analyzeCompetitivePosition(String ticker) {
        return String.format("Competitive position analysis for %s: [Requires industry comparison]", ticker);
    }
    
    private String analyzeManagementQuality(String ticker) {
        return String.format("Management quality analysis for %s: [Requires governance assessment]", ticker);
    }
    
    /**
     * 打印报告
     */
    public int printReport(String outputDir) {
        try {
            Path dirPath = Paths.get(outputDir);
            if (!Files.exists(dirPath)) {
                log.error("Output directory does not exist: {}", outputDir);
                return 1;
            }
            
            Path reportFile = dirPath.resolve("report.md");
            if (!Files.exists(reportFile)) {
                log.error("Report file does not exist: {}", reportFile);
                return 1;
            }
            
            String content = Files.readString(reportFile);
            System.out.println(content);
            
            return 0;
            
        } catch (IOException e) {
            log.error("Failed to print report", e);
            return 1;
        }
    }
    
    /**
     * 获取章节元数据
     */
    public ChapterMetadata getChapterMetadata(String ticker, String chapterId, Integer fiscalYear) {
        String key = buildMetadataKey(ticker, chapterId, fiscalYear);
        return chapterMetadataCache.get(key);
    }
    
    /**
     * 获取所有活跃运行
     */
    public Set<Integer> getActiveRuns() {
        return Collections.unmodifiableSet(activeRuns);
    }
}

/**
 * 托管运行上下文
 */
@Data
class HostedRunContext {
    private String runId;
    private String sessionId;
    private CancellationToken cancellationToken;
    
    public HostedRunContext() {
        this.cancellationToken = new CancellationToken();
    }
}

/**
 * 取消令牌
 */
@Data
class CancellationToken {
    private volatile boolean cancelled;
    
    public void cancel() {
        this.cancelled = true;
    }
    
    public boolean isCancelled() {
        return this.cancelled;
    }
}
