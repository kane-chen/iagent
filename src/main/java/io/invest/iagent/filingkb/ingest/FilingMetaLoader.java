package io.invest.iagent.filingkb.ingest;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 读取 document 目录下的 meta.json，产出建库所需的财报元数据。
 * <p>移植自遗留 DefaultFilingRagService.loadMeta + deriveFiscalPeriodFromDocumentId。
 */
@Slf4j
public class FilingMetaLoader {

    @Data
    @Builder
    public static class FilingMeta {
        private String ticker;
        private String documentId;
        private String formType;
        private Integer fiscalYear;
        /** 规范化周期：YYYYQn / YYYYHn / FYyyyy（可空） */
        private String fiscalPeriod;
        private String filingDate;
    }

    /**
     * 加载 meta.json；缺失或字段不全时按 documentId 后缀推导。
     */
    public FilingMeta load(Path docDir, String ticker, String documentId) {
        FilingMeta.FilingMetaBuilder builder = FilingMeta.builder()
                .ticker(ticker)
                .documentId(documentId);

        Path metaFile = docDir.resolve("meta.json");
        if (Files.isRegularFile(metaFile)) {
            try {
                JSONObject json = JSON.parseObject(Files.readString(metaFile));
                builder.formType(json.getString("formType"));
                Integer fiscalYear = json.getInteger("fiscalYear");
                String fp = json.getString("fiscalPeriod");
                if (fp == null || fp.isBlank()) {
                    fp = deriveFromDocumentId(documentId);
                }
                // 规范化（Q12025 -> 2025Q1 等）
                PeriodParser.ParsedPeriod parsed = PeriodParser.parse(fp);
                builder.fiscalPeriod(parsed.period() != null ? parsed.period() : fp);
                if (fiscalYear == null && parsed.fiscalYear() != null) {
                    fiscalYear = parsed.fiscalYear();
                }
                builder.fiscalYear(fiscalYear);
                String date = json.getString("filingDate");
                if (date == null || date.isBlank()) {
                    date = json.getString("reportDate");
                }
                builder.filingDate(date);
            } catch (IOException e) {
                log.warn("Failed to read meta.json at {}: {}", metaFile, e.getMessage());
            }
        } else {
            builder.fiscalPeriod(deriveFromDocumentId(documentId));
        }
        return builder.build();
    }

    /**
     * 从 documentId 后缀推导周期：_FY / _Q1..Q4 / _H1 / _H2。
     */
    private String deriveFromDocumentId(String documentId) {
        if (documentId == null) return null;
        String s = documentId.toUpperCase(Locale.ROOT);
        if (s.endsWith("_FY")) return "FY";
        for (int q = 1; q <= 4; q++) {
            if (s.contains("Q" + q)) return "Q" + q;
        }
        if (s.endsWith("_H1") || s.contains("_H1")) return "H1";
        if (s.endsWith("_H2") || s.contains("_H2")) return "H2";
        return null;
    }
}
