// src/main/java/com/finance/downloader/FilingDownloader.java
package io.invest.iagent.service.filing;


import io.invest.iagent.service.filing.model.FilingResult;
import io.invest.iagent.model.TickerMarket;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

public interface FilingDownloader {
    /**
     * 识别股票代码对应的市场
     */
    TickerMarket resolveMarket(String ticker);

    /**
     * 下载财报文件
     */
    List<FilingResult> download(
            String ticker,
            Set<String> formTypes,
            LocalDate fromDate,
            LocalDate toDate,
            boolean overwrite,
            String dataDir
    );
}




