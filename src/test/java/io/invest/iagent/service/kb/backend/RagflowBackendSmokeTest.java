package io.invest.iagent.service.kb.backend;

import com.alibaba.fastjson2.JSON;
import io.invest.AgentConfig4Test;
import io.invest.iagent.model.KnowledgeBaseDocumentDTO;
import io.invest.iagent.model.KnowledgeBaseRetrieveResult;
import io.invest.iagent.service.kb.model.KnowledgeBaseChunkDTO;
import io.invest.iagent.service.kb.model.KnowledgeBaseOperationResult;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import javax.annotation.Resource;
import java.util.List;

/**
 * RAGFlow 后端端到端冒烟测试，对应 deploy/ragflow/README-ragflow.md §4 的三步冒烟。
 * <p>
 * 该测试要求：
 * <ul>
 *   <li>本机（或指定 base-url）已按 README 起了 RAGFlow 服务</li>
 *   <li>已在 RAGFlow Web UI 生成 API Key，并通过环境变量 {@code RAGFLOW_API_KEY} 传入</li>
 *   <li>iagent workspace 下已下载过至少 1 份指定 ticker 的财报（默认 {@code workspace/portfolio/BABA/filings/}）</li>
 * </ul>
 * <p>
 * 缺少 {@code RAGFLOW_API_KEY} 时全部用例自动跳过，不影响日常构建。
 * <p>
 * 支持的环境变量（全部可选，除 API Key 外都有默认值）：
 * <table>
 *   <tr><th>变量</th><th>默认值</th><th>用途</th></tr>
 *   <tr><td>{@code RAGFLOW_BASE_URL}</td><td>{@code http://localhost:9380}</td><td>RAGFlow HTTP 入口</td></tr>
 *   <tr><td>{@code RAGFLOW_API_KEY}</td><td>-</td><td>API Key（必填才会启用测试）</td></tr>
 *   <tr><td>{@code RAGFLOW_SMOKE_TICKER}</td><td>{@code BABA}</td><td>被测 ticker，workspace 下须有该公司财报</td></tr>
 *   <tr><td>{@code RAGFLOW_SMOKE_WORKSPACE}</td><td>{@code $user.dir/workspace}</td><td>workspace 根目录</td></tr>
 *   <tr><td>{@code RAGFLOW_SMOKE_QUERY}</td><td>{@code revenue growth reasons}</td><td>检索 query</td></tr>
 *   <tr><td>{@code RAGFLOW_SMOKE_FORCE}</td><td>{@code false}</td><td>构建时是否 force=true（true 会重跑 parse，耗时增加）</td></tr>
 * </table>
 * <p>
 * 运行示例（PowerShell）：
 * <pre>
 * $env:RAGFLOW_API_KEY = "ragflow-xxxxxx"
 * mvn -Dtest=RagflowBackendSmokeTest test
 * </pre>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(classes = AgentConfig4Test.class)
@TestPropertySource(locations = "classpath:test.properties")
class RagflowBackendSmokeTest {


    @Resource(name="ragflowKnowledgeBaseBackend")
    private KnowledgeBaseBackend backend;
    /**
     * Step 1（对应 README §4 Step 1）：调用 build_filing_kb 触发上传 + meta 写入。
     * <p>
     * 注意：当前 backend 不再自动触发 parse，需要用户去 RAGFlow Web UI 手动点击"解析"完成 chunk+embedding，
     * 完成后再单独跑 {@link #list()} / {@link #retrieve()} 验证。
     */
    @Test
    @Order(1)
    void build() {
        String docId = "";
        String ticker = "BABA";
        KnowledgeBaseOperationResult result = backend.build(ticker, docId, true);
        Assertions.assertNotNull(result);
        Assertions.assertEquals("build", result.getOperation());
        Assertions.assertEquals(StringUtils.upperCase(ticker), result.getTicker());
        Assertions.assertTrue(result.isSuccess(), "build 应返回 success=true，errors=" + result.getErrors());
        Assertions.assertNotNull(result.getKnowledgeBaseId());
        Assertions.assertTrue(StringUtils.startsWith(result.getKnowledgeBaseId(), "filing_kb_"), "dataset 名应以 filing_kb_ 前缀开头，实际=" + result.getKnowledgeBaseId());
    }

    /**
     * Step 2（README §4 补充）：list 应能列出已构建的文档，且 meta 字段可读。
     */
    @Test
    @Order(2)
    void list() {
        String ticker = "BABA";
        List<KnowledgeBaseDocumentDTO> documents = backend.list(ticker);
        Assertions.assertNotNull(documents);
        Assertions.assertFalse(documents.isEmpty(),
                "list 应返回至少 1 个文档；若为空说明 build 未成功或 meta_fields 未写入");
        KnowledgeBaseDocumentDTO first = documents.get(0);
        Assertions.assertEquals(StringUtils.upperCase(ticker), first.getTicker());
        Assertions.assertNotNull(first.getDocumentId());
        // status 由 mapDocStatus 转出，正常应是 indexed
        Assertions.assertNotNull(first.getStatus());
    }

    /**
     * Step 3（对应 README §4 Step 2 的 retrieve_filing_kb 调用）：检索应返回至少一条命中，
     * 并且元数据里能看到 ragflow backend 与 dataset id。
     */
    @Test
    @Order(3)
    void retrieve() {
        String ticker = "BABA";
        String query = "revenue growth reasons" ;
        KnowledgeBaseRetrieveResult result = backend.retrieve(query, ticker, 5, null, null, null, false);
        System.out.println("retrieve => " + JSON.toJSONString(result));

        Assertions.assertNotNull(result);
        Assertions.assertEquals(StringUtils.upperCase(ticker), result.getTicker());
        Assertions.assertEquals(query, result.getQuery());
        Assertions.assertNotNull(result.getResults(),
                "retrieve.results 不应为 null，即使命中 0 条也应返回空列表");
        Assertions.assertFalse(result.getResults().isEmpty(),
                "retrieve 应命中至少 1 条 chunk；若为空请检查 parse 是否完成或 similarity-threshold 是否过高");

        KnowledgeBaseChunkDTO top = result.getResults().get(0);
        Assertions.assertNotNull(top.getChunkId(), "top chunk 应有 id");
        Assertions.assertTrue(StringUtils.isNotBlank(top.getText()), "top chunk 应有 text");
        Assertions.assertNotNull(top.getScore(), "top chunk 应有相似度分数");

        // metadata 应带 backend 与 dataset_id，便于日志追溯
        Assertions.assertNotNull(result.getMetadata());
        Assertions.assertEquals("ragflow", result.getMetadata().get("backend"));
        Assertions.assertNotNull(result.getMetadata().get("dataset_id"));
    }

}
