package io.invest.iagent.service.kb;

import io.invest.iagent.service.kb.embedding.EmbeddingService;
import io.invest.iagent.service.kb.embedding.ModelEmbeddingService;
import io.invest.iagent.service.kb.vector.VectorStoreService;
import io.invest.iagent.service.kb.vector.VectorStoreServiceByMilvus;
import io.invest.iagent.service.kb.model.KnowledgeBaseOperationResult;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

class FilingKnowledgeBaseServiceTest {

    private FilingKnowledgeBaseService service ;

    @BeforeEach
    public void init(){
        // process
        Path workspace = Paths.get(System.getProperty("user.dir")).resolve("./test-filings");
        // 创建带有财务报表提取功能的预处理服务
        FilingPreprocessService preprocessService = new FilingPreprocessService(workspace);
        // embedding
        String baseUri = "http://localhost:11434/api/embed";
        String apiKey = "local" ;
        String model = "qwen3-embedding:4b" ;
        EmbeddingService embeddingService = new ModelEmbeddingService(baseUri,apiKey, model,1024);
        // vector-store
        String endpoint = "http://127.0.0.1:19530" ;
        String token = null; // "root:Milvus" ;
        String collectionName = "invest_filing_test" ;
        VectorStoreService vectorStoreService = new VectorStoreServiceByMilvus(endpoint,token,collectionName) ;
        service = new FilingKnowledgeBaseService(preprocessService, embeddingService, vectorStoreService);
    }


    @Test
    void preprocess() {
        KnowledgeBaseOperationResult result = service.preprocess("LI","fil_0001104659-25-023764",true) ;
        Assertions.assertNotNull(result);
        Assertions.assertTrue(result.isSuccess());
    }

    @Test
    void build() {
        KnowledgeBaseOperationResult result = service.build("LI","fil_0001104659-25-023764",true) ;
        Assertions.assertNotNull(result);
        Assertions.assertTrue(result.isSuccess());
    }

    // retrieve 已迁移到 workspace/skills/financial-filing-retrieve；对应的 Java 单测已下线。
}
