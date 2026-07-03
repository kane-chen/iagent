package io.invest.iagent.tools.filing;

import io.agentscope.core.message.Msg;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.util.Assert;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

class FinancialSegmentMetricsToolTest {


    private FinancialSegmentMetricsTool tool ;

    @BeforeEach
    public void init() {
        Path workspace = Paths.get(System.getProperty("user.dir")).resolve("workspace");
        this.tool = new FinancialSegmentMetricsTool(workspace);
    }

    @Test
    public void test_excel_baba() {
        String responseText = tool.exportSegmentExcel("BABA");
        Assert.notNull(responseText, "call response");
    }

    @Test
    public void test_excel_pdd() {
        String responseText = tool.exportSegmentExcel("PDD");
        Assert.notNull(responseText, "call response");
    }

    @Test
    public void test_excel_google() {
        String responseText = tool.exportSegmentExcel("GOOG");
        Assert.notNull(responseText, "call response");
    }

    @Test
    public void test_excel_tcom() {
        String responseText = tool.exportSegmentExcel("TCOM");
        Assert.notNull(responseText, "call response");
    }

    @Test
    public void test_excel_tencent() {
        String responseText = tool.exportSegmentExcel("00900");
        Assert.notNull(responseText, "call response");
    }

    @Test
    public void test_excel_meituan() {
        String responseText = tool.exportSegmentExcel("83690");
        Assert.notNull(responseText, "call response");
    }

}