package io.invest.iagent.service.kb.backend;

import io.invest.AgentConfig4Test;
import io.invest.iagent.service.kb.model.KnowledgeBaseDocumentDTO;
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
 * RAGFlow 后端端到端冒烟测试。
 * <p>
 * 本 Java 端后端只承担 preprocess / build / list / delete；检索（retrieve）已迁移到
 * {@code workspace/skills/financial-filing-retrieve} Python skill，因此 retrieve 相关的
 * 冒烟用例（原 Step 3）也一并迁移到 skill 的 CLI（参见 SKILL.md 用法示例）。
 * <p>
 * 该测试要求：
 * <ul>
 *   <li>本机（或指定 base-url）已按 deploy/ragflow/README-ragflow.md 起了 RAGFlow 服务</li>
 *   <li>已在 RAGFlow Web UI 生成 API Key，并通过环境变量 {@code RAGFLOW_API_KEY} 传入</li>
 *   <li>iagent workspace 下已下载过至少 1 份指定 ticker 的财报</li>
 * </ul>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(classes = AgentConfig4Test.class)
@TestPropertySource(locations = "classpath:test.properties")
class RagflowBackendSmokeTest {


    @Resource(name="ragflowKnowledgeBaseBackend")
    private KnowledgeBaseBackend backend;

    /**
     * Step 1：调用 build 触发上传 + meta 写入。
     * <p>
     * 注意：当前 backend 不再自动触发 parse，需要用户去 RAGFlow Web UI 手动点击"解析"完成
     * chunk+embedding，完成后再单独跑 {@link #list()} 验证；检索结果的验证请走 python skill。
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
        Assertions.assertTrue(StringUtils.startsWith(result.getKnowledgeBaseId(), "filing_kb_"),
                "dataset 名应以 filing_kb_ 前缀开头，实际=" + result.getKnowledgeBaseId());
    }

    /**
     * Step 2：list 应能列出已构建的文档，且 meta 字段可读。
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

}
