package io.invest.iagent.service.filingrag.backend.textsearch;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class FinancialTermDictionaryTest {

    @Test
    void expandIncludesOriginalAndSynonyms() {
        Set<String> result = FinancialTermDictionary.expand(List.of("收入"));
        assertTrue(result.contains("收入"), "应包含原词");
        assertTrue(result.contains("营收"), "应包含同义词'营收'");
        assertTrue(result.contains("revenue"), "应包含英文同义词");
    }

    @Test
    void expandHandlesEnglishTerms() {
        Set<String> result = FinancialTermDictionary.expand(List.of("revenue"));
        assertTrue(result.contains("revenue"));
        assertTrue(result.contains("收入"), "英文词应扩展到中文同义词");
    }

    @Test
    void expandHandlesMultipleTerms() {
        Set<String> result = FinancialTermDictionary.expand(List.of("收入", "净利润"));
        assertTrue(result.contains("营收"), "应包含收入同义词'营收'");
        assertTrue(result.contains("net income"), "应包含净利润英文同义词'net income'");
        assertTrue(result.contains("收入"), "应包含原词'收入'");
        assertTrue(result.contains("净利润"), "应包含原词'净利润'");
    }

    @Test
    void expandIgnoresBlankTerms() {
        // List.of() doesn't allow null, use ArrayList
        List<String> input = new ArrayList<>();
        input.add("");
        input.add("  ");
        Set<String> result = FinancialTermDictionary.expand(input);
        assertTrue(result.isEmpty());
    }

    @Test
    void getPrimaryTermsIsNonEmpty() {
        List<String> terms = FinancialTermDictionary.getPrimaryTerms();
        assertNotNull(terms);
        assertFalse(terms.isEmpty());
        // 至少应包含常见的财报术语
        assertTrue(terms.contains("收入"));
        assertTrue(terms.contains("利润"));
        assertTrue(terms.contains("增长"));
    }
}
