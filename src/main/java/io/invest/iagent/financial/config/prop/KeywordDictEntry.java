package io.invest.iagent.financial.config.prop;

import lombok.Data;

import java.util.List;

/**
 * 关键字中英（简/繁）对照字典条目（keyword-dict.yml 中的一条）。
 * 按指标编码给出简体中文、繁体中文、英文三类同义关键字，
 * 检索时任一语言命中文本即算匹配，以兼容港股繁体、A 股简体、美股英文财报。
 */
@Data
public class KeywordDictEntry {

    /** 标准指标编码（须存在于 metric-catalog.yml） */
    private String code;

    /** 简体中文关键字 */
    private List<String> cn;

    /** 繁体中文关键字 */
    private List<String> tw;

    /** 英文关键字 */
    private List<String> en;
}
