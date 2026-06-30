package io.invest.iagent.tools.kb;

import com.alibaba.fastjson2.JSON;
import io.invest.iagent.service.extraction.ExtractionVerificationAgent;
import io.invest.iagent.service.extraction.SegmentExtractionAgent;
import io.invest.iagent.service.kb.embedding.EmbeddingService;
import io.invest.iagent.service.kb.FilingKnowledgeBaseService;
import io.invest.iagent.service.kb.FilingPreprocessService;
import io.invest.iagent.service.kb.embedding.ModelEmbeddingService;
import io.invest.iagent.service.kb.vector.VectorStoreService;
import io.invest.iagent.service.kb.vector.VectorStoreServiceByMilvus;
import io.invest.iagent.service.kb.model.KnowledgeBaseOperationResult;
import io.invest.iagent.model.KnowledgeBaseRetrieveResult;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

class FilingKnowledgeBaseToolTest {

    private FilingKnowledgeBaseTool tool ;
    private SegmentExtractionAgent extractionAgent ;
    private ExtractionVerificationAgent verificationAgent ;

    @BeforeEach
    public void init(){
        // process
        Path workspace = Paths.get(System.getProperty("user.dir")).resolve("workspace");
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
        FilingKnowledgeBaseService service = new FilingKnowledgeBaseService(preprocessService, embeddingService, vectorStoreService);
        tool = new FilingKnowledgeBaseTool(service);

        String openAiBaseUrl = "http://localhost:11434/v1";
        String llmApiKey = "local";
        String llmModel = "qwen3.5:9b";

        extractionAgent = new SegmentExtractionAgent(service, openAiBaseUrl, llmApiKey, llmModel);
        verificationAgent = new ExtractionVerificationAgent(service, openAiBaseUrl, llmApiKey, llmModel);
    }


    @Test
    void preprocess() {
        KnowledgeBaseOperationResult result = tool.preprocessFilingKb("LI","fil_0001104659-25-023764",true) ;
        Assertions.assertNotNull(result);
        Assertions.assertTrue(result.isSuccess());
    }

    @Test
    void build() {
        KnowledgeBaseOperationResult result = tool.buildFilingKb("LI",null,false) ;
        Assertions.assertNotNull(result);
        Assertions.assertTrue(result.isSuccess());
    }

    @Test
    void build2() {
        KnowledgeBaseOperationResult result = tool.buildFilingKb("NVDA",null,false) ;
        Assertions.assertNotNull(result);
        Assertions.assertTrue(result.isSuccess());
    }

    @Test
    void build_google() {
        KnowledgeBaseOperationResult result = tool.buildFilingKb("GOOGL",null,false) ;
        Assertions.assertNotNull(result);
        Assertions.assertTrue(result.isSuccess());
    }

    @Test
    public void extract_google(){
        String ticker = "GOOGL" ;
        String fy = "2025" ;
        String formType = "10-K" ;
        SegmentExtractionAgent.ExtractionResult result = extractionAgent.extractSegments(
                ticker, fy, formType
        );
        Assertions.assertNotNull(result);
    }

    @Test
    void retrieve() {
        KnowledgeBaseRetrieveResult result = tool.retrieveFilingKb("Vehicle margin","LI",10,"2025",null,null,false) ;
        Assertions.assertNotNull(result);
        System.out.println(JSON.toJSONString(result));
        Assertions.assertNotNull(result.getResults());
    }
}
