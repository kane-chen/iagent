package io.invest.iagent.service.filing.downloader;

import io.invest.iagent.service.filing.FilingDownloader;
import io.invest.iagent.service.filing.model.FilingResult;
import io.invest.iagent.model.FinancialFormType;
import io.invest.iagent.model.TickerMarket;
import io.invest.iagent.utils.FileUtils;
import io.invest.iagent.service.filing.util.TickerMarketUtil;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public abstract class AbstractFilingDownloader implements FilingDownloader {

    @Override
    public TickerMarket resolveMarket(String ticker) {
        return TickerMarketUtil.resolveMarket(ticker) ;
    }


    /**
     * 校验表单类型是否支持当前市场
     */
    protected Set<FinancialFormType> validateFormTypes(Set<String> formTypeCodes, TickerMarket market) {
        if (formTypeCodes == null || formTypeCodes.isEmpty()) {
            // 无指定则返回当前市场所有支持的类型
            return List.of(FinancialFormType.values()).stream()
                    .filter(type -> type.isSupported(market))
                    .collect(Collectors.toSet());
        }

        // 校验并转换为枚举
        Set<FinancialFormType> financialFormTypes = formTypeCodes.stream()
                .map(FinancialFormType::fromCode)
                .filter(type -> type.isSupported(market))
                .collect(Collectors.toSet());

        if (financialFormTypes.isEmpty()) {
            throw new IllegalArgumentException("无支持的表单类型，市场：" + market);
        }
        return financialFormTypes;
    }

    /**
     * 处理A股/港股Q2/Q4跳过逻辑
     */
    protected FilingResult skipQ2Q4(String documentId, String formType) {
        return FilingResult.builder()
                .documentId(documentId)
                .status("skipped")
                .formType(formType)
                .reason("A股/港股无Q2/Q4独立报告")
                .downloadedFiles(0)
                .hasXbrl(false)
                .build();
    }

    /**
     * 构建基础数据目录
     */
    protected String buildBaseDir(String ticker, String dataDir) {
        return FileUtils.createDir(dataDir).resolve(ticker).toString();
    }

    // 子类实现具体下载逻辑
    protected abstract List<FilingResult> doDownload(
            String ticker,
            Set<FinancialFormType> financialFormTypes,
            LocalDate fromDate,
            LocalDate toDate,
            boolean overwrite,
            String baseDir
    );

    @Override
    public List<FilingResult> download(String ticker, Set<String> formTypes, LocalDate fromDate, LocalDate toDate, boolean overwrite, String dataDir) {
        TickerMarket market = resolveMarket(ticker);
        Set<FinancialFormType> validatedFinancialFormTypes = validateFormTypes(formTypes, market);
        String baseDir = buildBaseDir(ticker, dataDir);

        // 执行具体下载逻辑
        List<FilingResult> results = doDownload(ticker, validatedFinancialFormTypes, fromDate, toDate, overwrite, baseDir);

        // 写入Manifest文件
        FileUtils.writeManifest(FileUtils.createDir(baseDir), results);

        return results;
    }
}