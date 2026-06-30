package io.invest.iagent.service.kb;

import com.alibaba.fastjson2.JSON;
import io.invest.iagent.service.kb.embedding.EmbeddingService;
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

class FilingKnowledgeBaseServiceTest {

    private FilingKnowledgeBaseService service ;

    @BeforeEach
    public void init(){
        // process
        Path workspace = Paths.get(System.getProperty("user.dir")).resolve("./test-filings");
        FilingPreprocessService preprocessService =  new FilingPreprocessService(workspace);
        // embedding
        String baseUri = "http://localhost:11434/api/embed";
        String apiKey = "local" ;
        String model = "qwen3-embedding:4b" ;
        EmbeddingService embeddingService = new ModelEmbeddingService(baseUri,apiKey, model,1024);
        // vector-store
        String endpoint = "http://192.168.1.6:19530" ;
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

    @Test
    void retrieve() {
        KnowledgeBaseRetrieveResult result = service.retrieve("Operating margin","LI",10,"2025",null) ;
        Assertions.assertNotNull(result);
        System.out.println(JSON.toJSONString(result));
        Assertions.assertNotNull(result.getResults());
    }
}