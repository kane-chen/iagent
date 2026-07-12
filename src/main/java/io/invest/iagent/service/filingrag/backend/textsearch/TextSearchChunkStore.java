package io.invest.iagent.service.filingrag.backend.textsearch;

import com.alibaba.fastjson2.JSON;
import io.invest.iagent.service.filingrag.model.FilingChunk;
import io.invest.iagent.utils.WorkspacePaths;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/**
 * 将chunks以JSON格式持久化到磁盘：workspace/portfolio/&lt;TICKER&gt;/processed/&lt;documentId&gt;/chunks.json。
 * 使用原子写入（写.tmp后move）保证文件完整性。
 */
@Slf4j
public class TextSearchChunkStore {

    private static final String CHUNKS_FILE = "chunks.json";
    private static final String TMP_SUFFIX = ".tmp";

    private final Path workspace;

    public TextSearchChunkStore(Path workspace) {
        this.workspace = workspace;
    }

    /**
     * 保存chunks到磁盘（幂等：先删除旧文件再写入）。
     */
    public void saveChunks(String ticker, String documentId, List<FilingChunk> chunks) throws IOException {
        Path dir = WorkspacePaths.processedDir(workspace, ticker, documentId);
        Files.createDirectories(dir);
        Path target = dir.resolve(CHUNKS_FILE);
        Path tmp = dir.resolve(CHUNKS_FILE + TMP_SUFFIX);
        // 序列化并写入临时文件
        String json = JSON.toJSONString(chunks);
        Files.writeString(tmp, json);
        // 原子移动
        try {
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            // 非原子move回退
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        log.debug("TextSearchChunkStore saved {} chunks to {}", chunks.size(), target);
    }

    /**
     * 从磁盘加载chunks。文件不存在时返回空列表。
     */
    public List<FilingChunk> loadChunks(String ticker, String documentId) {
        Path file = WorkspacePaths.processedDir(workspace, ticker, documentId).resolve(CHUNKS_FILE);
        if (!Files.exists(file)) {
            return Collections.emptyList();
        }
        try {
            String json = Files.readString(file);
            if (StringUtils.isBlank(json)) return Collections.emptyList();
            List<FilingChunk> chunks = JSON.parseArray(json, FilingChunk.class);
            return chunks == null ? Collections.emptyList() : chunks;
        } catch (Exception e) {
            log.warn("Failed to load chunks from {}: {}", file, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 删除chunks文件。返回删除的文件数（0或1）。
     */
    public int deleteChunks(String ticker, String documentId) {
        Path file = WorkspacePaths.processedDir(workspace, ticker, documentId).resolve(CHUNKS_FILE);
        try {
            if (Files.exists(file)) {
                Files.delete(file);
                return 1;
            }
        } catch (IOException e) {
            log.warn("Failed to delete chunks file {}: {}", file, e.getMessage());
        }
        return 0;
    }

    /**
     * 检查指定文档是否已有chunks索引。
     */
    public boolean hasChunks(String ticker, String documentId) {
        Path file = WorkspacePaths.processedDir(workspace, ticker, documentId).resolve(CHUNKS_FILE);
        return Files.exists(file);
    }
}
