package io.invest.iagent.financial.model;

import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 标准指标目录：加载 metric-catalog.yml 后提供编码/别名解析与分层访问。
 */
@Getter
public class MetricCatalog {

    /** code -> 定义（按 sortOrder 有序） */
    private final Map<String, MetricDef> byCode = new LinkedHashMap<>();

    /** 别名(小写) -> code，包含中文名/英文名/显式 aliases */
    private final Map<String, String> aliasIndex = new HashMap<>();

    public MetricCatalog(List<MetricDef> defs) {
        defs.stream()
                .sorted(Comparator.comparingInt(MetricDef::getSortOrder))
                .forEach(d -> {
                    byCode.put(d.getCode(), d);
                    index(d.getCode(), d.getCode());
                    index(d.getCode(), d.getNameCn());
                    index(d.getCode(), d.getNameEn());
                    if (d.getAliases() != null) {
                        for (String a : d.getAliases()) {
                            index(d.getCode(), a);
                        }
                    }
                });
    }

    private void index(String code, String alias) {
        if (alias == null || alias.isBlank()) {
            return;
        }
        aliasIndex.putIfAbsent(alias.trim().toLowerCase(Locale.ROOT), code);
    }

    public MetricDef get(String code) {
        return byCode.get(code);
    }

    public boolean contains(String code) {
        return byCode.containsKey(code);
    }

    /**
     * 按指标名解析标准编码：先按 code 精确匹配，再按中文名/英文名/别名（小写）匹配。
     *
     * @return 标准 code；无法解析返回 null
     */
    public String resolveCode(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String trimmed = name.trim();
        if (byCode.containsKey(trimmed.toUpperCase(Locale.ROOT))) {
            return trimmed.toUpperCase(Locale.ROOT);
        }
        return aliasIndex.get(trimmed.toLowerCase(Locale.ROOT));
    }

    /** 某张报表（或派生）下的全部指标，按 sortOrder 有序 */
    public List<MetricDef> byStatement(StatementType type) {
        List<MetricDef> result = new ArrayList<>();
        for (MetricDef d : byCode.values()) {
            if (d.statementType() == type) {
                result.add(d);
            }
        }
        return result;
    }

    /** 子指标列表（按 sortOrder 有序） */
    public List<MetricDef> children(String parentCode) {
        List<MetricDef> result = new ArrayList<>();
        for (MetricDef d : byCode.values()) {
            if (parentCode == null ? d.getParent() == null : parentCode.equals(d.getParent())) {
                result.add(d);
            }
        }
        result.sort(Comparator.comparingInt(MetricDef::getSortOrder));
        return result;
    }

    /** 指标所在层级（顶层=1） */
    public int levelOf(String code) {
        int level = 1;
        MetricDef d = byCode.get(code);
        while (d != null && d.getParent() != null) {
            level++;
            d = byCode.get(d.getParent());
        }
        return level;
    }

    public Map<String, MetricDef> all() {
        return Collections.unmodifiableMap(byCode);
    }
}
