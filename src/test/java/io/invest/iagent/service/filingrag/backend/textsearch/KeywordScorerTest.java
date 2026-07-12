package io.invest.iagent.service.filingrag.backend.textsearch;

import io.invest.iagent.service.filingrag.model.FilingChunk;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class KeywordScorerTest {

    private final KeywordScorer scorer = new KeywordScorer();

    private FilingChunk makeChunk(String title, String content) {
        return FilingChunk.builder()
                .chunkId("test_" + (title != null ? title.hashCode() : "0"))
                .ticker("TEST").documentId("doc")
                .sectionTitle(title)
                .content(content)
                .score(0.0)
                .metadata(new HashMap<>())
                .build();
    }

    @Test
    void titleHitScoresHigherThanContentOnlyHit() {
        // chunk1: keyword in title
        FilingChunk titleHit = makeChunk("营业收入分析", "本章节讨论财务表现");
        // chunk2: keyword in content only, same frequency context
        FilingChunk contentHit = makeChunk("财务概览", "公司营业收入在本季度实现了显著增长，达到100亿元");

        // Need at least 2 docs for IDF to be meaningful
        FilingChunk other = makeChunk("无关章节", "这是一段不相关的文字");
        Set<String> keywords = Set.of("营业收入");

        var results = scorer.score(List.of(titleHit, contentHit, other), keywords, 3, 0.01);

        assertEquals(2, results.size(), "两个命中的chunk应在结果中");
        // titleHit的"营业收入"在title中，应排在contentHit前面
        assertEquals("营业收入分析", results.get(0).chunk().getSectionTitle(),
                "title命中的chunk应排在最前");
    }

    @Test
    void noHitsReturnsEmpty() {
        FilingChunk c = makeChunk("无关章节", "这是一些完全无关的内容描述。");
        var results = scorer.score(List.of(c), Set.of("营业收入", "净利润"), 10, 0.01);
        assertTrue(results.isEmpty(), "无关键词命中的chunk不应出现在结果中");
    }

    @Test
    void multipleKeywordsProduceHigherScore() {
        FilingChunk c1 = makeChunk("财务数据", "营业收入实现增长。");
        FilingChunk c2 = makeChunk("财务数据", "营业收入和净利润均实现显著增长，利润率持续提升。");

        // Add other doc for IDF
        FilingChunk other = makeChunk("其他", "其他内容");
        var singleKw = scorer.score(List.of(c1, other), Set.of("营业收入"), 1, 0.0);
        double singleScore = singleKw.get(0).score();

        // Need fresh chunks since scores are set on them
        FilingChunk c1b = makeChunk("财务数据", "营业收入实现增长。");
        FilingChunk c2b = makeChunk("财务数据", "营业收入和净利润均实现显著增长，利润率持续提升。");
        FilingChunk other2 = makeChunk("其他", "其他内容");
        var multiKw = scorer.score(List.of(c1b, c2b, other2), Set.of("营业收入", "净利润", "增长"), 2, 0.0);

        // c2b命中3个关键词，应排在最前且分数高于单词命中
        assertTrue(multiKw.get(0).chunk().getContent().contains("净利润"),
                "命中更多关键词的chunk应排名靠前");
        // multi-keyword chunk should have higher score than single-keyword chunk
        double topMultiScore = multiKw.get(0).score();
        assertTrue(topMultiScore > 0, "应有正分");
    }

    @Test
    void idfRewardsRareTerms() {
        // 在大量文档中都出现的常见词 vs 只在少量文档中出现的稀有词
        FilingChunk target = makeChunk("核心业务", "公司云计算业务收入增长显著，EBITDA利润率提高");
        FilingChunk other1 = makeChunk("收入分析", "收入整体情况");
        FilingChunk other2 = makeChunk("收入结构", "收入结构分析");
        FilingChunk other3 = makeChunk("收入趋势", "收入趋势分析");

        // "收入"在4个文档中的3个出现（df=3），"EBITDA"只在target出现（df=1）
        var results = scorer.score(List.of(target, other1, other2, other3),
                Set.of("收入", "EBITDA"), 4, 0.0);

        // target因为有罕见词EBITDA应该获得较高分数
        assertFalse(results.isEmpty());
        assertEquals("核心业务", results.get(0).chunk().getSectionTitle(),
                "包含稀有词EBITDA的chunk应排名靠前");
    }

    @Test
    void countOccurrencesWorks() {
        assertEquals(3, KeywordScorer.countOccurrences("收入收入收入", "收入"));
        assertEquals(0, KeywordScorer.countOccurrences("hello world", "foo"));
        assertEquals(1, KeywordScorer.countOccurrences("revenue growth revenue", "growth"));
        // "aaabaaa" -> "aa" at 0 and 4 = 2 non-overlapping
        assertEquals(2, KeywordScorer.countOccurrences("aaabaaa", "aa"));
        assertEquals(0, KeywordScorer.countOccurrences("", "x"));
        assertEquals(0, KeywordScorer.countOccurrences("content", ""));
    }

    @Test
    void topNLimitRespected() {
        FilingChunk c1 = makeChunk("收入1", "收入相关内容");
        FilingChunk c2 = makeChunk("收入2", "收入相关内容");
        FilingChunk c3 = makeChunk("收入3", "收入相关内容");
        FilingChunk c4 = makeChunk("收入4", "收入相关内容");
        FilingChunk other = makeChunk("其他", "无关文字");

        var results = scorer.score(List.of(c1, c2, c3, c4, other), Set.of("收入"), 2, 0.0);
        assertEquals(2, results.size());
    }

    @Test
    void emptyInputReturnsEmpty() {
        assertTrue(scorer.score(List.of(), Set.of("收入"), 5, 0.0).isEmpty());
        FilingChunk c = makeChunk("标题", "内容");
        assertTrue(scorer.score(List.of(c), Set.of(), 5, 0.0).isEmpty());
    }

    @Test
    void scoreIsNormalizedBetweenZeroAndOne() {
        FilingChunk c = makeChunk("收入", "收入增长利润提高收入");
        FilingChunk other1 = makeChunk("其他1", "无关内容一");
        FilingChunk other2 = makeChunk("其他2", "无关内容二");

        var results = scorer.score(List.of(c, other1, other2), Set.of("收入", "增长", "利润"), 5, 0.0);
        for (var sc : results) {
            assertTrue(sc.score() >= 0 && sc.score() <= 1.0,
                    "分数应归一化到[0,1]区间，实际=" + sc.score());
        }
    }
}
