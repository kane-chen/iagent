package io.invest.iagent.tools.filing;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

public class FinancialFilingDownloadToolIntegrationTest {

    private FinancialFilingDownloadTool tool;

    @BeforeEach
    public void before(){
        Path workspace = Paths.get(System.getProperty("user.dir")).resolve("workspace");
        String userAgent = "io/yiying5@gmail.com";
        tool =  new FinancialFilingDownloadTool(workspace, userAgent);
    }

    @Test
    void downloadApple10Q() {
        String ticker = "AAPL";
        String fy = "2025";
        String filingType = "10-Q";
        String result = tool.downloadFiling(ticker, fy, filingType);
        System.out.println(result);
        Assertions.assertThat(Objects.requireNonNull(result))
                .containsAnyOf( ticker,fy,filingType);

    }

    @Test
    void downloadNvda() {
        String ticker = "NVDA";
        String fy = "2024,2025,2026";
        String filingType = "10-Q,10-K";
        String result = tool.downloadFiling(ticker, fy, filingType);
        System.out.println(result);
        Assertions.assertThat(Objects.requireNonNull(result))
                .containsAnyOf( ticker,fy,filingType);

    }

    @Test
    void downloadGoogle() {
        String ticker = "GOOG";
        String fy = "2024,2025,2026";
        String filingType = "10-Q,10-K";
        String result = tool.downloadFiling(ticker, fy, filingType);
        System.out.println(result);
        Assertions.assertThat(Objects.requireNonNull(result))
                .containsAnyOf( ticker,fy,filingType);
    }

    @Test
    void downloadMsft() {
        String ticker = "MSFT";
        String fy = "2024,2025,2026";
        String filingType = "10-Q,10-K";
        String result = tool.downloadFiling(ticker, fy, filingType);
        System.out.println(result);
        Assertions.assertThat(Objects.requireNonNull(result))
                .containsAnyOf( ticker,fy,filingType);
    }

    @Test
    void downloadLi() {
        String ticker = "LI";
        String fy = "2025,2026";
        String filingType = "20-F,6-K";
        String result = tool.downloadFiling(ticker, fy, filingType);
        System.out.println(result);
        Assertions.assertThat(Objects.requireNonNull(result))
                .containsAnyOf( ticker,fy,filingType);
    }

    @Test
    void downloadPdd() {
        String ticker = "PDD";
        String fy = "2025,2026";
        String filingType = "20-F,6-K";
        String result = tool.downloadFiling(ticker, fy, filingType);
        System.out.println(result);
        Assertions.assertThat(Objects.requireNonNull(result))
                .containsAnyOf( ticker,fy);
    }

    @Test
    void downloadJd() {
        String ticker = "JD";
        String fy = "2024,2025,2026";
        String filingType = "20-F,6-K";
        String result = tool.downloadFiling(ticker, fy, filingType);
        System.out.println(result);
        Assertions.assertThat(Objects.requireNonNull(result))
                .containsAnyOf( ticker,fy);
    }

    @Test
    void downloadBABA() {
        String ticker = "BABA";
        String fy = "2022,2023,2024,2025,2026";
        String filingType = "20-F,6-K";
        String result = tool.downloadFiling(ticker, fy, filingType);
        System.out.println(result);
        Assertions.assertThat(Objects.requireNonNull(result))
                .containsAnyOf( ticker,fy);
    }

    @Test
    void downloadBK() {
        String ticker = "BEKE";
        String fy = "2024,2025,2026";
        String filingType = "20-F,6-K";
        String result = tool.downloadFiling(ticker, fy, filingType);
        System.out.println(result);
        Assertions.assertThat(Objects.requireNonNull(result))
                .containsAnyOf( ticker,fy);
    }

    @Test
    @Disabled("Integration test requires network access, run manually if needed")
    void downloadTencent() {
        String ticker = "00700";
        String fy = "2024,2025,2026";
        String result = tool.downloadFiling(ticker, fy, null,false);
        System.out.println(result);
        Assertions.assertThat(Objects.requireNonNull(result))
                .containsAnyOf( ticker,fy);
    }

    @Test
    @Disabled("Integration test requires network access, run manually if needed")
    void downloadMeituan() {
        String ticker = "03690";
        String fy = "2024,2025,2026";
        String result = tool.downloadFiling(ticker, fy, null,false);
        System.out.println(result);
        Assertions.assertThat(Objects.requireNonNull(result))
                .containsAnyOf( ticker,fy);
    }

    @Test
    void downloadMao() {
        String ticker = "600519";
        String fy = "2025";
        String result = tool.downloadFiling(ticker, fy, null);
        System.out.println(result);
        Assertions.assertThat(Objects.requireNonNull(result))
                .containsAnyOf( ticker,fy);
    }

}
