package io.invest.iagent.financial.config;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

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

    /** 是否启用关键字补充指标提取（本地财报文件关键字检索 + LLM，无需 RAG 知识库） */
    private boolean keywordExtractEnabled = true;

    /** 关键字提取时每个指标保留送 LLM 的最多段落数 */
    private int keywordTopSnippets = 10;

    /** 调用 futu python 脚本的超时秒数 */
    private int pythonTimeoutSeconds = 300;

    /** 分部数据提取脚本超时秒数（PDF 解析较慢，默认 900） */
    private int segmentTimeoutSeconds = 900;

    /** python 可执行文件（默认 python；实际调用时经 PythonResolver 探测，macOS/Linux 无 python 时自动回退 python3） */
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

    /** 期权策略（宽跨式推荐）配置 */
    private Option option = new Option();

    /** 期权策略分析配置。 */
    @Data
    public static class Option {
        /** 宽跨式组合扫描的最近到期日数量 */
        private int expiries = 4;

        /** 期权腿最低未平仓量（流动性过滤，0 不过滤） */
        private int minOpenInterest = 100;

        /**
         * 期权腿最大价外幅度（相对现价）：put 行权价 ≥ 现价×(1-x)、call ≤ 现价×(1+x)。
         * 限制过宽/尘屑合约入选，避免亏损窗口过大。
         */
        private double maxOtmPct = 0.25;

        /** 亏损窗口宽度上限（相对现价）：盈亏平衡区间宽度超过现价×x 的组合直接剔除。 */
        private double maxLossWindowPct = 0.30;

        /** 收益分布曲线采样点数 */
        private int distributionPoints = 31;

        /**
         * 分市场手续费（近似富途公开费率，币种为当地货币，可用 app.financial.option.commissions.* 覆盖）。
         * 期权费按张/笔计；正股费按成交金额费率与每股费取大后与单笔最低取大。
         */
        private Map<String, Commission> commissions = new HashMap<>(Map.of(
                // 美股：期权 佣金$0.65+平台$0.30/张（每单最低约$1.99）；正股 佣金$0.0049+平台$0.005/股（每单最低约$1.99）
                "US", new Commission(0.95, 1.99, 0.0, 0.0099, 1.99),
                // 港股：期权 佣金HK$3+平台HK$1.5/张；正股 佣金+平台+印花等约 0.2%（每单最低 HK$6）
                "HK", new Commission(4.5, 0.0, 0.002, 0.0, 6.0)));
    }

    /**
     * 期权/正股交易手续费配置（单边开仓或行权后正股平仓各计一次）。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Commission {
        /** 每张期权开仓手续费（佣金+平台费，当地货币/张，每条腿） */
        private double optionFeePerContract;

        /** 期权每笔订单最低手续费（当地货币/单） */
        private double optionMinFeePerOrder;

        /** 正股手续费率（按成交金额，单边；0.002 = 0.2%，含佣金/平台/印花税等） */
        private double stockFeeRate;

        /** 正股每股手续费（美股按股收费，当地货币/股；按金额收费的市场填 0） */
        private double stockFeePerShare;

        /** 正股单笔最低手续费（当地货币/单） */
        private double stockMinFee;
    }
}
