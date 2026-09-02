package io.invest.iagent.financial.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 财务数据服务配置（app.financial.*）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.financial")
public class FinancialProperties {

    /** 是否启用财务数据服务（注册工具、初始化表结构） */
    private boolean enabled = false;

    /** 采集时默认拉取的期数（futu API num 参数） */
    private int defaultPeriods = 16;

    /** 是否启用 RAG 补充指标提取（需要 filing 知识库已构建） */
    private boolean ragExtractEnabled = true;

    /** RAG 提取时每个指标检索的片段数 */
    private int ragTopK = 5;

    /** 调用 futu python 脚本的超时秒数 */
    private int pythonTimeoutSeconds = 300;

    /** 分部数据提取脚本超时秒数（PDF 解析较慢，默认 900） */
    private int segmentTimeoutSeconds = 900;

    /** python 可执行文件（默认 python，Windows 上也可能是 python） */
    private String pythonExecutable = "python";

    /** 采集的期数下限，低于该覆盖率视为缺失（用于查询时自动补采判断） */
    private int minPeriodsForQuery = 4;

    /** 查询时默认返回的最近期数（不指定 periods 时） */
    private int defaultQueryPeriods = 6;

    private String reportBaseDir = "./workspace/financial_reports";
    private int reportMaxRetry = 3;
    private long reportSleepMs = 1500;
    private String reportUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    // SEC EDGAR 要求带邮箱
    private String reportSecUserAgent = "FinancialResearch yiying5@gmail.com";
}
