package io.invest.iagent.financial.option;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.invest.iagent.financial.config.FinancialProperties;
import io.invest.iagent.financial.ingest.FutuCodeUtil;
import io.invest.iagent.utils.ProcessRunner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 期权链取数器：调用 futu-option-analysis skill 的 fetch_option_chain_json.py，
 * 经富途 OpenD 拉取正股最近 N 个到期日的期权链快照（价格/买卖价/IV/未平仓量/Greeks/合约乘数）。
 * 脚本与富途 OpenD 网关的连接由 futuapi skill 的 common 模块负责。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.financial", name = "enabled", havingValue = "true")
public class OptionChainFetcher {

    private static final String SCRIPT_REL = "skills/futu-option-analysis/scripts/fetch_option_chain_json.py";

    @Autowired
    private Path workspace;

    @Autowired
    private FinancialProperties properties;

    /** 期权链快照：正股现价 + 各到期日期权报价。 */
    public record OptionChain(String code, String market, String currency,
                              BigDecimal spot, List<OptionExpiry> expiries) {}

    /** 某到期日：到期日（yyyy-MM-dd）、距到期天数、该日全部期权报价。 */
    public record OptionExpiry(String strikeTime, int daysToExpiry, List<OptionQuote> options) {}

    /** 单张期权合约快照。 */
    public record OptionQuote(String code, String type, BigDecimal strike,
                              BigDecimal last, BigDecimal bid, BigDecimal ask,
                              BigDecimal iv, long openInterest, long volume,
                              BigDecimal delta, int contractSize) {}

    /**
     * 拉取期权链。
     *
     * @param ticker   裸 ticker 或带市场前缀的 futu 代码（00700 / HK.00700 / AAPL）
     * @param expiries 最近到期日数量
     */
    public OptionChain fetch(String ticker, int expiries) throws Exception {
        String futuCode = FutuCodeUtil.toFutuCode(ticker);
        Path output = workspace.resolve("temp").resolve(
                futuCode.replace(".", "_") + "_options.json");
        Files.createDirectories(output.getParent());

        Path script = workspace.resolve(SCRIPT_REL);
        List<String> cmd = List.of(
                properties.getPythonExecutable(), script.toAbsolutePath().toString(),
                futuCode, "--expiries", String.valueOf(expiries),
                "--output", output.toAbsolutePath().toString());
        ProcessRunner.Result result = ProcessRunner.run(cmd, null, properties.getPythonTimeoutSeconds());
        if (!result.isSuccess() || !Files.isRegularFile(output)) {
            throw new IllegalStateException("期权链取数脚本失败(rc=" + result.getExitCode() + "): "
                    + lastLines(result.getStderr(), 500));
        }

        JSONObject root = JSON.parseObject(Files.readString(output, StandardCharsets.UTF_8));
        return parse(root);
    }

    /** 纯解析逻辑（不依赖外部进程，便于单元测试）。 */
    OptionChain parse(JSONObject root) {
        String code = root.getString("code");
        String market = root.getString("market");
        String currency = root.getString("currency");
        BigDecimal spot = root.getBigDecimal("spot");

        List<OptionExpiry> expiries = new ArrayList<>();
        JSONArray expiryArr = root.getJSONArray("expiries");
        if (expiryArr != null) {
            for (int i = 0; i < expiryArr.size(); i++) {
                JSONObject e = expiryArr.getJSONObject(i);
                JSONArray opts = e.getJSONArray("options");
                List<OptionQuote> quotes = new ArrayList<>();
                if (opts != null) {
                    for (int j = 0; j < opts.size(); j++) {
                        JSONObject o = opts.getJSONObject(j);
                        quotes.add(new OptionQuote(
                                o.getString("code"),
                                o.getString("option_type"),
                                o.getBigDecimal("strike"),
                                o.getBigDecimal("last"),
                                o.getBigDecimal("bid"),
                                o.getBigDecimal("ask"),
                                o.getBigDecimal("iv"),
                                o.getLongValue("oi"),
                                o.getLongValue("volume"),
                                o.getBigDecimal("delta"),
                                o.getIntValue("contract_size", 100)));
                    }
                }
                expiries.add(new OptionExpiry(e.getString("strike_time"),
                        e.getIntValue("days_to_expiry", 0), quotes));
            }
        }
        return new OptionChain(code, market, currency, spot, expiries);
    }

    private static String lastLines(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(s.length() - max);
    }
}
