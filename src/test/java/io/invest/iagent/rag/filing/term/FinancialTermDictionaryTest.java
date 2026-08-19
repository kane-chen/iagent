package io.invest.iagent.rag.filing.term;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FinancialTermDictionaryTest {

    @Test
    void expandsChineseTermToSynonyms() {
        Set<String> expanded = FinancialTermDictionary.expand(Set.of("营收"));
        assertThat(expanded).contains("营收", "收入", "revenue", "营业收入");
    }

    @Test
    void expandsEnglishTermCaseInsensitive() {
        Set<String> expanded = FinancialTermDictionary.expand(Set.of("EBITDA"));
        assertThat(expanded).contains("EBITDA", "ebitda");
    }

    @Test
    void seedsExtractCjkBigramsAndAsciiTokens() {
        Set<String> seeds = FinancialTermDictionary.extractSeeds("公司的净利润和 revenue 情况");
        assertThat(seeds).contains("净利", "利润", "revenue");
    }

    @Test
    void queryExpansionAddsSynonymsNotAlreadyPresent() {
        // 种子词为 CJK bigram："下滑" 命中词典，扩展出 decline/decrease 等英文同义词
        Set<String> seeds = FinancialTermDictionary.extractSeeds("收入为什么下滑");
        Set<String> expanded = FinancialTermDictionary.expand(seeds);
        assertThat(expanded).anyMatch(s -> s.equalsIgnoreCase("decline"));
        assertThat(expanded).contains("收入");
    }
}
