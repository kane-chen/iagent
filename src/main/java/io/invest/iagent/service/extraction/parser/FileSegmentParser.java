package io.invest.iagent.service.extraction.parser;

import io.invest.iagent.service.extraction.model.CompanyConfig;
import io.invest.iagent.service.extraction.model.Segment;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * 财报文件解析器统一接口：每种文件格式（HTML/PDF/...）实现本接口，
 * 直接从文件产出 {@link Segment} 列表。实现内部可以自由组合 parser + orchestrator/handler，
 * 上层 {@code FinancialExtractionService} 只需按 {@link #supports(File)} 分发。
 */
public interface FileSegmentParser {

    /**
     * 从文件中解析出分部数据。
     *
     * @param file   财报文件
     * @param config 公司配置（含 segment 定义、metric mapping、pdf column mapping 等）
     * @return 解析出的 {@link Segment} 列表；未命中返回空列表
     */
    List<Segment> parse(File file, CompanyConfig config) throws IOException;

    /**
     * 是否支持该文件（通常按扩展名判断：.html/.htm → HTML；.pdf → PDF）。
     */
    boolean supports(File file);
}
