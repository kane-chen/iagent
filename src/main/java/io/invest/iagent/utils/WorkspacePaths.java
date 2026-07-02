package io.invest.iagent.utils;

import java.nio.file.Path;

/**
 * 工作区路径真源。定义 iagent workspace 的目录布局约定：
 * <pre>
 * workspace/
 *   portfolio/
 *     &lt;TICKER&gt;/
 *       filings/&lt;documentId&gt;/meta.json  ← 财报文件
 *       materials/&lt;documentId&gt;/          ← 其它材料
 *       processed/&lt;documentId&gt;/          ← 预处理产物
 *       meta.json                        ← 公司元数据
 *     metadata/                          ← 全局元数据
 * </pre>
 * <p>
 * 与 Python skill 侧 {@code workspace_paths.py} 的约定完全一致。
 */
public final class WorkspacePaths {

    private WorkspacePaths() {}

    public static Path companiesDir(Path workspaceRoot) {
        return workspaceRoot.resolve("portfolio");
    }

    public static Path companyDir(Path workspaceRoot, String ticker) {
        return companiesDir(workspaceRoot).resolve(ticker);
    }

    public static Path filingsDir(Path workspaceRoot, String ticker) {
        return companyDir(workspaceRoot, ticker).resolve("filings");
    }

    public static Path filingsDir(Path workspaceRoot, String ticker, String documentId) {
        return companyDir(workspaceRoot, ticker).resolve("filings").resolve(documentId);
    }

    public static Path materialsDir(Path workspaceRoot, String ticker) {
        return companyDir(workspaceRoot, ticker).resolve("materials");
    }

    public static Path processedDir(Path workspaceRoot, String ticker) {
        return companyDir(workspaceRoot, ticker).resolve("processed");
    }

    public static Path processedDir(Path workspaceRoot, String ticker, String documentId) {
        return processedDir(workspaceRoot, ticker).resolve(documentId);
    }

    public static Path processedMetaFile(Path workspaceRoot, String ticker, String documentId) {
        return processedDir(workspaceRoot, ticker).resolve(documentId).resolve("meta.json");
    }
}
