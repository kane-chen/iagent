package io.invest.iagent.filingkb;

import io.invest.AgentConfig4Test;
import io.invest.iagent.filingkb.model.FilingBuildReport;
import io.invest.iagent.filingkb.model.FilingChunk;
import io.invest.iagent.filingkb.retrieve.FilingTagKeys;
import io.invest.iagent.rag.model.RetrieveRequest;
import io.invest.iagent.rag.model.RetrieveResultItem;
import io.invest.iagent.rag.model.TagCondition;
import io.invest.iagent.rag.model.TagFilter;
import io.invest.iagent.rag.retrieve.enums.RetrieveMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * filingkb 端到端集成测试：真实 PostgreSQL(pgvector + ParadeDB) + Ollama(embedding + chat)。
 * <p>
 * 流程：在临时 workspace 下构造两份财报 HTML（不同财期）→ {@link FilingKbBuildService} 建库
 * → 校验 embeddings/chunk_tags 行 → {@link FilingKbQaService} 走完整 pipeline
 * （含 query 改写、标签解析、"最新一期"周期归一化、金融术语扩展、引用格式化）→ 校验带标签和引用的片段。
 * <p>
 * 运行前提：本地 Postgres（iagent 库，已执行 rag-schema.sql）与 Ollama（qwen3-embedding:4b、qwen3.5:4b）。
 * 默认随 {@code mvn test} 不会被外部服务依赖阻断时才通过；无外部服务时会失败（属预期，集成测试范畴）。
 */
@SpringBootTest(classes = AgentConfig4Test.class)
@TestPropertySource(locations = "classpath:test.properties")
class FilingKbEndToEndIntegrationTest {

    private static final String TICKER = "ITFILING";
    private static final String DOC_Q1 = "2025Q1_filing";
    private static final String DOC_Q2 = "2025Q2_filing";

    /** 测试专用知识库 id，避免污染开发库中的 "filing" 数据 */
    private static final String KB_ID = "filing_it";

    @TempDir
    static Path workspace;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws Exception {
        // AgentConfig 启动时要求 workspace 下存在 skills 目录（BossAgent 的 FileSystemSkillRepository）；
        // 此处在 context 加载前（workspace 已由 @TempDir 创建）准备好目录结构
        Files.createDirectories(workspace.resolve("skills"));
        Files.createDirectories(workspace.resolve("states"));
        Files.createDirectories(workspace.resolve("temp"));
        registry.add("app.workspace.base-dir", () -> workspace.toString());
        registry.add("app.filing-kb.knowledge-base-id", () -> KB_ID);
    }

    @Autowired
    private FilingKbBuildService buildService;
    @Autowired
    private FilingKbQaService qaService;
    @Autowired
    private io.invest.iagent.rag.KnowledgeService knowledgeService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        // 仅清理本测试的 knowledgeId（ticker_docId），标签随外键级联
        jdbcTemplate.update("DELETE FROM embeddings WHERE knowledge_base_id = ? AND knowledge_id LIKE ?",
                KB_ID, TICKER + "\\_%");
    }

    private Path docDir(String documentId) {
        return workspace.resolve("portfolio").resolve(TICKER).resolve("filings").resolve(documentId);
    }

    private void writeFiling(String documentId, String fiscalPeriod, int fiscalYear,
                             String formType, String cloudRevenue, String riskText) throws Exception {
        Path dir = docDir(documentId);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("meta.json"), """
                {
                  "formType": "%s",
                  "fiscalYear": %d,
                  "fiscalPeriod": "%s",
                  "filingDate": "%d-04-30"
                }
                """.formatted(formType, fiscalYear, fiscalPeriod, fiscalYear));
        // HtmlDocumentReader 会把 h1/h2 转成 #/##，供 HeadingAwareChunker 挂 heading 面包屑
        Files.writeString(dir.resolve("report.html"), """
                <html><body>
                <h1>%s %s 财报</h1>
                <h2>管理层讨论与分析</h2>
                <p>本季度公司云计算业务表现稳健。%s 该增长主要由企业数字化转型与算力需求驱动。
                人工智能业务继续放量，自研大模型通过API对外提供服务。</p>
                <h2>风险因素</h2>
                <p>%s 高端GPU供应存在不确定性，市场价格竞争可能压缩利润率。</p>
                </body></html>
                """.formatted(TICKER, fiscalPeriod, cloudRevenue, riskText));
    }

    @Test
    void build_then_retrieve_with_tags_and_citation() throws Exception {
        // ---- 1. 构造两份财报（2025Q1 与 2025Q2）----
        writeFiling(DOC_Q1, "Q1", 2025, "10-Q",
                "云计算业务本季度实现收入80亿元，同比增长18%。",
                "供应链方面，");
        writeFiling(DOC_Q2, "Q2", 2025, "10-Q",
                "云计算业务本季度实现收入100亿元，同比增长25%。",
                "供应链方面，");

        // ---- 2. 建库 ----
        FilingBuildReport report = buildService.buildTicker(TICKER, false);
        System.out.println("[build] docs=" + report.getDocuments()
                + " chunks=" + report.getChunks() + " errors=" + report.getErrors());
        assertThat(report.getErrors()).isEmpty();
        assertThat(report.getDocuments()).isEqualTo(2);
        assertThat(report.getChunks()).isGreaterThan(0);

        Long embCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM embeddings WHERE knowledge_base_id = ?", Long.class, KB_ID);
        Long tagCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chunk_tags WHERE knowledge_base_id = ?", Long.class, KB_ID);
        Long distinctPeriods = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT tag_value) FROM chunk_tags WHERE knowledge_base_id = ? AND tag_key = 'fiscal_period'",
                Long.class, KB_ID);
        System.out.println("[db] embeddings=" + embCount + " tags=" + tagCount + " periods=" + distinctPeriods);
        assertThat(embCount).isGreaterThan(0);
        assertThat(tagCount).isGreaterThan(0);
        assertThat(distinctPeriods).isEqualTo(2L);

        // 校验 heading 标签确实由 HeadingAwareChunker 挂接
        Integer headingRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM chunk_tags WHERE knowledge_base_id = ? AND tag_key = 'heading'",
                Integer.class, KB_ID);
        assertThat(headingRows).isGreaterThan(0);

        // 幂等重建：再次建库，行数不翻倍
        buildService.buildTicker(TICKER, false);
        Long embCountAfterRebuild = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM embeddings WHERE knowledge_base_id = ?", Long.class, KB_ID);
        assertThat(embCountAfterRebuild).isEqualTo(embCount);

        // ---- 3. 检索：显式 ticker + 相对时间"最新一期"，应归一化到 2025Q2 ----
        List<FilingChunk> latest = qaService.ask("最新一期云计算业务的收入是多少亿元", TICKER, null, 5);
        assertThat(latest).isNotEmpty();
        System.out.println("=== latest period results ===");
        latest.forEach(c -> System.out.println(c.getCitation() + " | " + c.getContent()));

        FilingChunk top = latest.get(0);
        assertThat(top.getTags()).containsEntry(FilingTagKeys.TICKER, TICKER);
        assertThat(top.getTags()).containsEntry(FilingTagKeys.FISCAL_PERIOD, "2025Q2");
        assertThat(top.getTags()).containsKey(FilingTagKeys.HEADING);
        assertThat(top.getCitation()).contains(TICKER).contains("2025Q2").contains("[C1]");
        // 命中文本应来自 Q2（100亿元 / 25%）
        assertThat(top.getContent()).contains("100");

        // ---- 4. 检索：显式指定 2025Q1，应命中 Q1（80亿元 / 18%）----
        List<FilingChunk> q1 = qaService.ask("云计算业务收入是多少", TICKER, "2025Q1", 5);
        assertThat(q1).isNotEmpty();
        assertThat(q1.get(0).getTags()).containsEntry(FilingTagKeys.FISCAL_PERIOD, "2025Q1");
        assertThat(q1.get(0).getContent()).contains("80");
        System.out.println("=== explicit Q1 results ===");
        q1.forEach(c -> System.out.println(c.getCitation()));

        // ---- 5. 跨域隔离：非 filing domain 的检索不产生 filing 引用 ----
        RetrieveRequest nonFiling = RetrieveRequest.builder()
                .query("云计算业务收入")
                .knowledgeBaseIds(List.of(KB_ID))
                .retrieveMode(RetrieveMode.HYBRID)
                .enableRewrite(false)
                .rerankTopK(3)
                .tagFilter(TagFilter.of(TagCondition.eq(FilingTagKeys.FISCAL_PERIOD, "2025Q2")))
                .build();
        // 通过 KnowledgeService 直接检索（domain=null），filingkb handler 不应执行
        List<RetrieveResultItem> generic = knowledgeService.retrieve(nonFiling);
        assertThat(generic).isNotEmpty();
        for (RetrieveResultItem item : generic) {
            Map<String, String> meta = item.getMetadata();
            assertThat(meta).doesNotContainKey("citation");
        }
        System.out.println("=== cross-domain isolation OK, returned " + generic.size() + " chunks without citation ===");
    }
}
