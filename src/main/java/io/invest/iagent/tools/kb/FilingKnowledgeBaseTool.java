package io.invest.iagent.tools.kb;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.invest.iagent.service.kb.embedding.MathEmbeddingService;
import io.invest.iagent.service.kb.embedding.EmbeddingService;
import io.invest.iagent.service.kb.FilingKnowledgeBaseService;
import io.invest.iagent.service.kb.vector.VectorStoreServiceInMemory;
import io.invest.iagent.service.kb.vector.VectorStoreServiceByMilvus;
import io.invest.iagent.service.kb.embedding.ModelEmbeddingService;
import io.invest.iagent.service.kb.vector.VectorStoreService;
import io.invest.iagent.service.kb.summary.FilingChunkSummarizationService;
import io.invest.iagent.service.kb.summary.NoopFilingChunkSummarizationService;
import io.invest.iagent.service.kb.summary.OpenAiCompatibleFilingChunkSummarizationService;
import io.invest.iagent.model.KnowledgeBaseDocumentDTO;
import io.invest.iagent.service.kb.model.KnowledgeBaseOperationResult;
import io.invest.iagent.model.KnowledgeBaseRetrieveResult;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

public class FilingKnowledgeBaseTool {

    private final FilingKnowledgeBaseService service;

    public FilingKnowledgeBaseTool(FilingKnowledgeBaseService service) {
        this.service = service;
    }

    @Tool(name = "preprocess_filing_kb", description = "预处理已下载财报文件，生成知识库chunks，但不写入Milvus。适用于构建知识库前准备文本、表格和引用信息。")
    public KnowledgeBaseOperationResult preprocessFilingKb(
            @ToolParam(name = "ticker", description = "股票代码，例如 AAPL、PDD、LI") String ticker,
            @ToolParam(name = "document_id", required = false, description = "可选，指定财报document_id；不传则处理该公司的全部已下载财报") String documentId,
            @ToolParam(name = "force", required = false, description = "是否强制重新预处理，默认不重新预处理") Boolean force
    ) {
        return service.preprocess(ticker, documentId, BooleanUtils.isTrue(force));
    }

    @Tool(name = "build_filing_kb", description = "构建财报知识库：预处理已下载财报、生成向量并写入Milvus。用于让后续问题可从财报知识库检索。")
    public KnowledgeBaseOperationResult buildFilingKb(
            @ToolParam(name = "ticker", description = "股票代码，例如 AAPL、PDD、LI") String ticker,
            @ToolParam(name = "document_id", required = false, description = "可选，指定财报document_id；不传则构建该公司的全部已下载财报") String documentId,
            @ToolParam(name = "force", required = false, description = "是否强制重新构建，默认不重新构建") Boolean force
    ) {
        return service.build(ticker, documentId, BooleanUtils.isTrue(force));
    }

    @Tool(name = "retrieve_filing_kb", description = "从财报知识库检索与问题相关的内容片段，支持按内容类别过滤，也可先检索分片摘要候选再检索详细内容；不传类别时会根据问题自动推断，返回带引用的chunks。")
    public KnowledgeBaseRetrieveResult retrieveFilingKb(
            @ToolParam(name = "query", description = "检索问题，例如：经营利润下降的原因 成本 费用 管理层讨论") String query,
            @ToolParam(name = "ticker", required = false, description = "可选股票代码过滤，例如 LI") String ticker,
            @ToolParam(name = "top_k", required = false, description = "返回条数，默认5") Integer topK,
            @ToolParam(name = "fiscal_year", required = false, description = "可选财年过滤，例如 2025") String fiscalYear,
            @ToolParam(name = "form_type", required = false, description = "可选表单类型过滤，例如 10-K、20-F、6-K") String formType,
            @ToolParam(name = "category", required = false, description = "可选内容类别过滤，例如 financial_statements、business_operations、financial_operations、operating_risks、governance_legal、market_strategy、esg_human_capital、other；不传则根据问题自动推断") String category,
            @ToolParam(name = "use_summary_candidates", required = false, description = "是否先检索chunk摘要候选，再在候选详细内容中检索；默认false或由KB_RETRIEVAL_USE_SUMMARY_CANDIDATES控制") Boolean useSummaryCandidates
    ) {
        return service.retrieve(query, ticker, topK, fiscalYear, formType, category, BooleanUtils.toBooleanDefaultIfNull(useSummaryCandidates, defaultUseSummaryCandidates()));
    }

    @Tool(name = "list_filing_kb", description = "列出已构建的财报知识库文档和索引状态。")
    public List<KnowledgeBaseDocumentDTO> listFilingKb(
            @ToolParam(name = "ticker", required = false, description = "可选股票代码过滤，例如 AAPL") String ticker
    ) {
        return service.list(ticker);
    }

    @Tool(name = "delete_filing_kb", description = "删除指定公司或指定财报文档的知识库向量。仅在用户明确要求删除知识库内容时调用。")
    public KnowledgeBaseOperationResult deleteFilingKb(
            @ToolParam(name = "ticker", description = "股票代码，例如 AAPL") String ticker,
            @ToolParam(name = "document_id", required = false, description = "可选document_id；不传则删除该公司的全部知识库chunks") String documentId
    ) {
        return service.delete(ticker, documentId);
    }

    @Tool(name = "sync_filing_kb", description = "同步指定公司的财报知识库，根据已下载财报重新预处理并构建索引。")
    public KnowledgeBaseOperationResult syncFilingKb(
            @ToolParam(name = "ticker", description = "股票代码，例如 AAPL") String ticker,
            @ToolParam(name = "force", required = false, description = "是否强制重建，默认false") Boolean force
    ) {
        return service.sync(ticker, BooleanUtils.isTrue(force));
    }

    private static EmbeddingService defaultEmbeddingService(){
        String mode = StringUtils.defaultIfBlank(System.getenv("KB_EMBEDDING_MODE"), "openai");
        if(StringUtils.equalsIgnoreCase(mode, "deterministic")){
            return new MathEmbeddingService(Integer.parseInt(StringUtils.defaultIfBlank(System.getenv("KB_EMBEDDING_DIMENSION"), "128")));
        }
        return new ModelEmbeddingService(
                StringUtils.firstNonBlank(System.getenv("KB_EMBEDDING_BASE_URL"), "http://localhost:11434/v1"),
                StringUtils.firstNonBlank(System.getenv("KB_EMBEDDING_API_KEY"), "ollama-local"),
                StringUtils.firstNonBlank(System.getenv("KB_EMBEDDING_MODEL"), "bge-m3"),
                Integer.parseInt(StringUtils.firstNonBlank(System.getenv("KB_EMBEDDING_DIMENSION"), "1024"))
        );
    }

    private static VectorStoreService defaultVectorStoreService(){
        String mode = StringUtils.defaultIfBlank(System.getenv("KB_VECTOR_STORE"), "milvus");
        if(StringUtils.equalsIgnoreCase(mode, "memory")){
            return new VectorStoreServiceInMemory();
        }
        return new VectorStoreServiceByMilvus(
                StringUtils.defaultIfBlank(System.getenv("KB_MILVUS_ENDPOINT"), "http://localhost:19530"),
                StringUtils.defaultString(System.getenv("KB_MILVUS_TOKEN")),
                StringUtils.defaultIfBlank(System.getenv("KB_MILVUS_COLLECTION"), "filing_chunks"),
                StringUtils.defaultIfBlank(System.getenv("KB_MILVUS_SUMMARY_COLLECTION"), "filing_chunk_summaries")
        );
    }

    private static FilingChunkSummarizationService defaultSummarizationService(){
        if(!BooleanUtils.toBoolean(System.getenv("KB_CHUNK_SUMMARY_ENABLED"))){
            return new NoopFilingChunkSummarizationService();
        }
        return new OpenAiCompatibleFilingChunkSummarizationService(
                StringUtils.firstNonBlank(System.getenv("KB_CHUNK_SUMMARY_BASE_URL"), System.getenv("KB_EMBEDDING_BASE_URL"), "http://localhost:11434/v1"),
                StringUtils.firstNonBlank(System.getenv("KB_CHUNK_SUMMARY_API_KEY"), System.getenv("KB_EMBEDDING_API_KEY"), "ollama-local"),
                StringUtils.firstNonBlank(System.getenv("KB_CHUNK_SUMMARY_MODEL"), System.getenv("KB_EMBEDDING_MODEL"), "qwen2.5"),
                Integer.parseInt(StringUtils.defaultIfBlank(System.getenv("KB_CHUNK_SUMMARY_MAX_INPUT_CHARS"), "6000")),
                Integer.parseInt(StringUtils.defaultIfBlank(System.getenv("KB_CHUNK_SUMMARY_TIMEOUT_SECONDS"), "60"))
        );
    }

    private static boolean defaultUseSummaryCandidates(){
        return BooleanUtils.toBoolean(System.getenv("KB_RETRIEVAL_USE_SUMMARY_CANDIDATES"));
    }
}
