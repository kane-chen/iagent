package io.invest.iagent.service.filing.util;
 
import java.nio.file.Path;
 
/**
 * 工作区路径真源。
 * 对应 Python workspace_paths.py 的目录布局约定。
 */
public final class WorkspacePaths {
 
    private WorkspacePaths() {}
 
    public static Path companiesDir(Path workspaceRoot) {
        return workspaceRoot.resolve("portfolio");
    }

    public static Path metaDataDir(Path workspaceRoot) {
        return companiesDir(workspaceRoot).resolve("metadata");
    }

    public static Path companyDir(Path workspaceRoot, String ticker) {
        return companiesDir(workspaceRoot).resolve(ticker);
    }

    public static Path companyMetaFile(Path workspaceRoot, String ticker) {
        return companyDir(workspaceRoot, ticker).resolve("meta.json");
    }
 
    public static Path filingsDir(Path workspaceRoot, String ticker) {
        return companyDir(workspaceRoot, ticker).resolve("filings");
    }

    public static Path filingsDir(Path workspaceRoot, String ticker,String documentId) {
        return companyDir(workspaceRoot, ticker).resolve("filings").resolve(documentId);
    }
 
    public static Path materialsDir(Path workspaceRoot, String ticker) {
        return companyDir(workspaceRoot, ticker).resolve("materials");
    }
 
    public static Path processedDir(Path workspaceRoot, String ticker) {
        return companyDir(workspaceRoot, ticker).resolve("processed");
    }

    public static Path processedDir(Path workspaceRoot, String ticker,String documentId) {
        return processedDir(workspaceRoot, ticker).resolve(documentId);
    }


    public static Path sourceMetaFile(Path workspaceRoot, String ticker,
                                       String documentId, String sourceKind) {
        String kindDir = "filing".equalsIgnoreCase(sourceKind) ? "filings" : "materials";
        return companyDir(workspaceRoot, ticker)
                .resolve(kindDir)
                .resolve(documentId)
                .resolve("meta.json");
    }
 
    public static Path processedMetaFile(Path workspaceRoot, String ticker, String documentId) {
        return processedDir(workspaceRoot, ticker).resolve(documentId).resolve("meta.json");
    }
}