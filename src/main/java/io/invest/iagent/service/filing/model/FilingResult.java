// src/main/java/com/finance/model/FilingResult.java
package io.invest.iagent.service.filing.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FilingResult {
    /** 文档唯一标识 */
    private String documentId;

    // 0001104659-26-067186
    private String accessionNumber ;
    /** 状态: downloaded / skipped / failed */
    private String status;
    /** 表单类型 */
    private String formType;
    /** 披露日期 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @JsonSerialize(using = LocalDateSerializer.class)
    private LocalDate reportDate;
    /** 披露日期 */
    @JsonFormat(pattern = "yyyy-MM-dd")
    @JsonSerialize(using = LocalDateSerializer.class)
    private LocalDate filingDate;
    /** 成功下载文件数 */
    private int downloadedFiles;
    /** 指纹 */
    private String fingerprint ;
    /** 是否包含XBRL数据 */
    private boolean hasXbrl;
    /** 失败/跳过原因 */
    private String reason;
}