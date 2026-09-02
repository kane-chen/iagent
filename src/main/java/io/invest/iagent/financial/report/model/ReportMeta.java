package io.invest.iagent.financial.report.model;

import lombok.Data;

@Data
public class ReportMeta {
    public String title;
    public String publishDate;   // yyyy-MM-dd
    public String pdfUrl;
    public String localPath;    // 下载后本地路径
    public boolean success;
    public String errorMsg;
}