package io.invest.iagent.service.kb.backend;

import io.invest.iagent.service.kb.model.KnowledgeBaseDocumentDTO;
import io.invest.iagent.service.kb.FilingPreprocessService;
import io.invest.iagent.service.kb.embedding.EmbeddingService;
import io.invest.iagent.service.kb.model.KnowledgeBaseChunkDTO;
import io.invest.iagent.service.kb.model.KnowledgeBaseOperationResult;
import io.invest.iagent.service.kb.vector.VectorStoreService;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 基于本地 Milvus 的财报知识库后端实现。
 * <p>
 * 该实现负责：
 * 1) 复用 {@link FilingPreprocessService} 进行财报解析与切分；
 * 2) 通过 {@link EmbeddingService} 生成向量；
 * 3) 写入 {@link VectorStoreService}（Milvus 或内存实现）；
 * 4) 提供 preprocess / build / list / delete 语义。
 * <p>
 * <b>注意</b>：检索（retrieve）能力已迁移到 {@code workspace/skills/financial-filing-retrieve}
 * Python skill，本类不再实现 retrieve；上层若要拉取原文，请走该 skill。
 */
public class MilvusKnowledgeBaseBackend implements KnowledgeBaseBackend {

    private final FilingPreprocessService preprocessService;
    private final EmbeddingService embeddingService;
    private final VectorStoreService vectorStoreService;

    public MilvusKnowledgeBaseBackend(FilingPreprocessService preprocessService,
                                      EmbeddingService embeddingService,
                                      VectorStoreService vectorStoreService) {
        this.preprocessService = preprocessService;
        this.embeddingService = embeddingService;
        this.vectorStoreService = vectorStoreService;
    }

    @Override
    public String name() {
        return "milvus";
    }

    @Override
    public KnowledgeBaseOperationResult preprocess(String ticker, String documentId, boolean force) {
        try {
            List<KnowledgeBaseChunkDTO> chunks = preprocessService.preprocess(ticker, documentId, force);
            return KnowledgeBaseOperationResult.builder()
                    .success(true)
                    .operation("preprocess")
                    .ticker(StringUtils.upperCase(ticker))
                    .documentId(documentId)
                    .knowledgeBaseId(knowledgeBaseId(ticker))
                    .chunkCount(chunks.size())
                    .documentIds(documentIds(chunks))
                    .message("预处理完成，生成 " + chunks.size() + " 个chunk")
                    .metadata(Map.of("embedding_model", embeddingService.model()))
                    .build();
        } catch (Exception e) {
            return error("preprocess", ticker, documentId, e);
        }
    }

    @Override
    public KnowledgeBaseOperationResult build(String ticker, String documentId, boolean force) {
        try {
            String normalizedTicker = StringUtils.upperCase(ticker);

            // 如果不是强制构建，先检查是否已有索引
            if (!force) {
                List<KnowledgeBaseDocumentDTO> existing = list(normalizedTicker);
                if (StringUtils.isBlank(documentId) && !existing.isEmpty()) {
                    return KnowledgeBaseOperationResult.builder()
                            .success(true)
                            .operation("build")
                            .ticker(normalizedTicker)
                            .knowledgeBaseId(knowledgeBaseId(ticker))
                            .chunkCount(0)
                            .documentIds(existing.stream().map(KnowledgeBaseDocumentDTO::getDocumentId).toList())
                            .message("知识库已存在，跳过构建。如需重新构建，请使用 force=true")
                            .metadata(Map.of("existing_documents", existing.size()))
                            .build();
                } else if (StringUtils.isNotBlank(documentId)) {
                    boolean exists = existing.stream()
                            .anyMatch(d -> StringUtils.equals(d.getDocumentId(), documentId));
                    if (exists) {
                        return KnowledgeBaseOperationResult.builder()
                                .success(true)
                                .operation("build")
                                .ticker(normalizedTicker)
                                .documentId(documentId)
                                .knowledgeBaseId(knowledgeBaseId(ticker))
                                .chunkCount(0)
                                .documentIds(List.of(documentId))
                                .message("文档 " + documentId + " 已在知识库中，跳过构建。如需重新构建，请使用 force=true")
                                .build();
                    }
                }
            }

            List<String> targetDocumentIds;
            if (StringUtils.isNotBlank(documentId)) {
                targetDocumentIds = List.of(documentId);
            } else {
                targetDocumentIds = preprocessService.listDocumentIds(ticker);
            }

            if (targetDocumentIds.isEmpty()) {
                return KnowledgeBaseOperationResult.builder()
                        .success(true)
                        .operation("build")
                        .ticker(normalizedTicker)
                        .documentId(documentId)
                        .knowledgeBaseId(knowledgeBaseId(ticker))
                        .chunkCount(0)
                        .message("没有找到可处理的财报文件，请先下载财报")
                        .build();
            }

            int totalChunkCount = 0;
            int totalSummaryCandidateCount = 0;
            List<String> processedDocumentIds = new ArrayList<>();
            for (String currentDocumentId : targetDocumentIds) {
                List<KnowledgeBaseChunkDTO> chunks = preprocessService.preprocess(ticker, currentDocumentId, force);
                if (chunks.isEmpty()) {
                    continue;
                }
                List<List<Float>> embeddings = new ArrayList<>();
                for (KnowledgeBaseChunkDTO chunk : chunks) {
                    embeddings.add(embeddingService.embed(chunk.getText()));
                }
                upsertInBatches(chunks, embeddings, 512);
                totalSummaryCandidateCount += upsertSummaryCandidates(chunks);
                totalChunkCount += chunks.size();
                processedDocumentIds.addAll(documentIds(chunks));
            }

            if (totalChunkCount == 0) {
                return KnowledgeBaseOperationResult.builder()
                        .success(true)
                        .operation("build")
                        .ticker(normalizedTicker)
                        .documentId(documentId)
                        .knowledgeBaseId(knowledgeBaseId(ticker))
                        .chunkCount(0)
                        .message("没有找到可处理的财报文件，请先下载财报")
                        .build();
            }
            return KnowledgeBaseOperationResult.builder()
                    .success(true)
                    .operation("build")
                    .ticker(normalizedTicker)
                    .documentId(documentId)
                    .knowledgeBaseId(knowledgeBaseId(ticker))
                    .chunkCount(totalChunkCount)
                    .documentIds(processedDocumentIds.stream().distinct().toList())
                    .message("知识库构建完成，写入 " + totalChunkCount + " 个chunk")
                    .metadata(Map.of(
                            "embedding_model", embeddingService.model(),
                            "embedding_dimension", embeddingService.dimension(),
                            "summary_candidate_count", totalSummaryCandidateCount,
                            "summary_enabled", totalSummaryCandidateCount > 0))
                    .build();
        } catch (Exception e) {
            return error("build", ticker, documentId, e);
        }
    }

    @Override
    public KnowledgeBaseOperationResult delete(String ticker, String documentId) {
        int deleted = vectorStoreService.delete(StringUtils.upperCase(ticker), documentId);
        return KnowledgeBaseOperationResult.builder()
                .success(true)
                .operation("delete")
                .ticker(StringUtils.upperCase(ticker))
                .documentId(documentId)
                .knowledgeBaseId(knowledgeBaseId(ticker))
                .chunkCount(deleted)
                .message("已删除 " + deleted + " 个chunk")
                .build();
    }

    @Override
    public List<KnowledgeBaseDocumentDTO> list(String ticker) {
        Map<String, List<KnowledgeBaseChunkDTO>> byDocument = vectorStoreService.list(StringUtils.upperCase(ticker)).stream()
                .collect(Collectors.groupingBy(KnowledgeBaseChunkDTO::getDocumentId, LinkedHashMap::new, Collectors.toList()));
        List<KnowledgeBaseDocumentDTO> documents = new ArrayList<>();
        byDocument.forEach((documentId, chunks) -> {
            KnowledgeBaseChunkDTO first = chunks.get(0);
            documents.add(KnowledgeBaseDocumentDTO.builder()
                    .ticker(first.getTicker())
                    .documentId(documentId)
                    .formType(first.getFormType())
                    .fiscalYear(first.getFiscalYear())
                    .fiscalPeriod(first.getFiscalPeriod())
                    .filingDate(first.getFilingDate())
                    .sourceFingerprint(String.valueOf(first.getMetadata().get("source_fingerprint")))
                    .chunkCount(chunks.size())
                    .status("indexed")
                    .build());
        });
        return documents;
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private int upsertSummaryCandidates(List<KnowledgeBaseChunkDTO> chunks) {
        List<KnowledgeBaseChunkDTO> summaryChunks = chunks.stream()
                .filter(chunk -> StringUtils.isNotBlank(summaryOf(chunk)))
                .toList();
        if (summaryChunks.isEmpty()) {
            return 0;
        }
        List<List<Float>> summaryEmbeddings = new ArrayList<>();
        for (KnowledgeBaseChunkDTO chunk : summaryChunks) {
            summaryEmbeddings.add(embeddingService.embed(summaryOf(chunk)));
        }
        upsertSummaryCandidatesInBatches(summaryChunks, summaryEmbeddings, 512);
        return summaryChunks.size();
    }

    private void upsertInBatches(List<KnowledgeBaseChunkDTO> chunks, List<List<Float>> embeddings, int batchSize) {
        int totalSize = chunks.size();
        for (int i = 0; i < totalSize; i += batchSize) {
            int end = Math.min(i + batchSize, totalSize);
            List<KnowledgeBaseChunkDTO> chunkBatch = chunks.subList(i, end);
            List<List<Float>> embeddingBatch = embeddings.subList(i, end);
            vectorStoreService.upsert(chunkBatch, embeddingBatch);
        }
    }

    private void upsertSummaryCandidatesInBatches(List<KnowledgeBaseChunkDTO> chunks, List<List<Float>> embeddings, int batchSize) {
        int totalSize = chunks.size();
        for (int i = 0; i < totalSize; i += batchSize) {
            int end = Math.min(i + batchSize, totalSize);
            List<KnowledgeBaseChunkDTO> chunkBatch = chunks.subList(i, end);
            List<List<Float>> embeddingBatch = embeddings.subList(i, end);
            vectorStoreService.upsertSummaryCandidates(chunkBatch, embeddingBatch);
        }
    }

    private String summaryOf(KnowledgeBaseChunkDTO chunk) {
        Object summary = chunk.getMetadata() == null ? null : chunk.getMetadata().get("chunk_summary");
        return summary == null ? null : String.valueOf(summary);
    }

    private KnowledgeBaseOperationResult error(String operation, String ticker, String documentId, Exception e) {
        return KnowledgeBaseOperationResult.builder()
                .success(false)
                .operation(operation)
                .ticker(StringUtils.upperCase(ticker))
                .documentId(documentId)
                .knowledgeBaseId(knowledgeBaseId(ticker))
                .chunkCount(0)
                .message(e.getMessage())
                .errors(List.of(e.getClass().getSimpleName() + ": " + e.getMessage()))
                .build();
    }

    private List<String> documentIds(List<KnowledgeBaseChunkDTO> chunks) {
        return chunks.stream().map(KnowledgeBaseChunkDTO::getDocumentId).distinct().toList();
    }

    private String knowledgeBaseId(String ticker) {
        return "filing_kb_" + StringUtils.upperCase(ticker);
    }
}
