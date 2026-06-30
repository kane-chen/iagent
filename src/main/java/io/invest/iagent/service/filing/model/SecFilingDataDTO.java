package io.invest.iagent.service.filing.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Data
public class SecFilingDataDTO {

    private Integer cik;

    private String entityName;

    private DocTypeDTO facts ;

    @Data
    public static class DocTypeDTO {
        /**
         * 美国通用会计财务数据
         */
        @JsonProperty("us-gaap")
        private Map<String,IndexItemDTO> usGaap;
        /**
         * Document and Entity Information
         * 公司名、CIK、申报期、交易所等
         */
        private Map<String,IndexItemDTO> dei;
        /**
         * SEC Reporting Taxonomy
         * SEC 报告分类标准。主要用于维度、表格结构和跨准则通用概念，比如：按分部/地区划分的收入、类别轴、范围轴等，常与 us-gaap 配合使用。
         */
        private Map<String,IndexItemDTO> srt;
        /**
         * Executive Compensation Disclosure
         * 高管薪酬披露分类标准。用于代理声明（DEF 14A）或其他薪酬相关报告中，包含高管各项薪酬、奖金、股票奖励等详细数据。
         */
        private Map<String,IndexItemDTO> ecd;
    }

    @Data
    public static class IndexItemDTO {
        private String label;
        private String description;
        private Map<String, List<IndexValueDTO>> units ;
    }


    @Data
    public static class IndexValueDTO {
        private BigDecimal val ;
        // accession number
        private String accn ;
        // 财年
        private Integer fy;
        // 财期 (FY, Q1, Q2...)
        private String fp;
        // 表单类型 (10-K, 10-Q...)
        private String form;
        // 申报日期 2022-03-11
        private String filed;
        // 可为空，可用于快速定位的 frame 标识 CY2021Q2I
        private String frame;
        // 可为空，2020-01-01
        private String start;
        // 可为空，2020-09-30
        private String end;
    }

}
