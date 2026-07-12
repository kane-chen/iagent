package io.invest.iagent.service.filingrag.backend.textsearch;

import io.invest.iagent.service.filingrag.model.FilingChunk;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 基于BM25F的多字段关键词检索评分器。
 *
 * <p>BM25F是BM25的多字段扩展，对title（sectionTitle）和content两个字段分别计算词频，
 * 并使用权重和长度归一化合并得分。相比简单词频计数：
 * <ul>
 *   <li>IDF（逆文档频率）：在少数chunk中出现的关键词获得更高权重</li>
 *   <li>TF饱和度：词频达到一定程度后边际收益递减（K1参数）</li>
 *   <li>长度归一化：长文档不会因为包含更多词而过度占优（B参数）</li>
 *   <li>字段权重：title命中权重3倍于content</li>
 * </ul>
 *
 * <p>中文处理：由于查询关键词经过词典和LLM优化后通常是完整术语（如"营业收入"、"净利润"），
 * 因此直接使用关键词短语作为匹配单元，不进行自动分词。对于query中的英文词，做小写化处理。
 */
public class KeywordScorer {

    /** title字段权重（章节标题命中） */
    static final double TITLE_WEIGHT = 3.0;
    /** content字段权重（正文命中） */
    static final double CONTENT_WEIGHT = 1.0;
    /** BM25 K1参数：词频饱和度 */
    private static final double K1 = 1.2;
    /** BM25 B参数：长度归一化强度（title通常较短，使用更小的B） */
    private static final double TITLE_B = 0.35;
    private static final double CONTENT_B = 0.75;

    /**
     * 评分结果：chunk + 分数。
     */
    public record ScoredChunk(FilingChunk chunk, double score) {}

    /**
     * 对候选chunks按BM25F评分，返回按分数降序排列的topN结果。
     *
     * @param chunks   候选chunk列表（同一ticker、已元数据过滤后）
     * @param keywords 查询关键词集合（已去重、已过滤）
     * @param topN     返回最大数量
     * @param minScore 最低分数阈值（BM25F分数大于0即可）
     * @return 评分排序后的结果
     */
    public List<ScoredChunk> score(List<FilingChunk> chunks, Set<String> keywords, int topN, double minScore) {
        if (chunks.isEmpty() || keywords.isEmpty()) {
            return new ArrayList<>();
        }

        // 1. 预处理：提取字段文本并统计字段长度，计算DF（文档频率）
        int N = chunks.size();
        List<ChunkFields> fieldsList = new ArrayList<>(N);
        Map<String, Integer> titleDf = new HashMap<>();
        Map<String, Integer> contentDf = new HashMap<>();
        long totalTitleLen = 0;
        long totalContentLen = 0;

        for (FilingChunk chunk : chunks) {
            String title = StringUtils.defaultString(chunk.getSectionTitle()).toLowerCase();
            String content = StringUtils.defaultString(chunk.getContent()).toLowerCase();
            ChunkFields cf = new ChunkFields(chunk, title, content);
            fieldsList.add(cf);
            totalTitleLen += title.length();
            totalContentLen += content.length();

            // 统计每个关键词在title和content中是否出现（DF = 文档频率）
            Set<String> seenInTitle = new HashSet<>();
            Set<String> seenInContent = new HashSet<>();
            for (String kw : keywords) {
                String kwl = kw.toLowerCase();
                if (kwl.isEmpty()) continue;
                if (countOccurrences(title, kwl) > 0 && seenInTitle.add(kwl)) {
                    titleDf.merge(kwl, 1, Integer::sum);
                }
                if (countOccurrences(content, kwl) > 0 && seenInContent.add(kwl)) {
                    contentDf.merge(kwl, 1, Integer::sum);
                }
            }
        }

        double avgTitleLen = (double) totalTitleLen / N;
        double avgContentLen = (double) totalContentLen / N;

        // 2. 对每个chunk计算BM25F分数
        List<ScoredChunk> scored = new ArrayList<>();
        for (ChunkFields cf : fieldsList) {
            double bm25f = bm25fScore(cf, keywords, N, titleDf, contentDf, avgTitleLen, avgContentLen);
            if (bm25f > minScore) {
                // 将分数归一化到0-1区间并写回chunk
                double normalized = normalizeScore(bm25f);
                cf.chunk.setScore(normalized);
                scored.add(new ScoredChunk(cf.chunk, normalized));
            }
        }

        // 3. 排序并截取topN
        scored.sort(Comparator.comparingDouble(ScoredChunk::score).reversed());
        if (scored.size() > topN) {
            return new ArrayList<>(scored.subList(0, topN));
        }
        return scored;
    }

    /**
     * 计算单个chunk的BM25F分数。
     *
     * <p>BM25F公式：
     * <pre>
     * score(q,d) = sum over t in q of:
     *     IDF(t) * ( (K1 + 1) * weightedTf(t,d) ) / ( K1 + weightedTf(t,d) )
     *
     * weightedTf(t,d) = sum over fields f of:
     *     W_f * tf_{t,f,d} / (1 - B_f + B_f * (len_{f,d} / avgLen_f))
     *
     * IDF(t) = ln(1 + (N - df(t) + 0.5) / (df(t) + 0.5))
     * </pre>
     */
    private double bm25fScore(ChunkFields cf, Set<String> keywords, int N,
                              Map<String, Integer> titleDf, Map<String, Integer> contentDf,
                              double avgTitleLen, double avgContentLen) {
        double score = 0.0;
        int titleLen = cf.title.length();
        int contentLen = cf.content.length();

        // 合并两个字段的DF：一个关键词在任一字段出现即算文档包含该词
        Map<String, Integer> combinedDf = new HashMap<>();
        for (String kw : keywords) {
            String kwl = kw.toLowerCase();
            if (kwl.isEmpty()) continue;
            int df = Math.max(titleDf.getOrDefault(kwl, 0), contentDf.getOrDefault(kwl, 0));
            // 若两个字段都出现，取较大者（即至少在一个字段出现的文档数）
            combinedDf.put(kwl, df);
        }

        for (String kw : keywords) {
            String kwl = kw.toLowerCase();
            if (kwl.isEmpty()) continue;

            int df = combinedDf.getOrDefault(kwl, 0);
            if (df <= 0) continue;

            // IDF
            double idf = Math.log(1.0 + (N - df + 0.5) / (df + 0.5));

            // 字段词频
            int tfTitle = countOccurrences(cf.title, kwl);
            int tfContent = countOccurrences(cf.content, kwl);

            // 加权且长度归一化的TF
            double weightedTf = 0.0;
            if (tfTitle > 0) {
                double normTfTitle = normalizedTf(tfTitle, titleLen, avgTitleLen, TITLE_B);
                weightedTf += TITLE_WEIGHT * normTfTitle;
            }
            if (tfContent > 0) {
                double normTfContent = normalizedTf(tfContent, contentLen, avgContentLen, CONTENT_B);
                weightedTf += CONTENT_WEIGHT * normTfContent;
            }

            if (weightedTf <= 0) continue;

            // BM25 term score
            score += idf * ((K1 + 1.0) * weightedTf) / (K1 + weightedTf);
        }

        return score;
    }

    /**
     * BM25字段内TF长度归一化：tf / (1 - B + B * (fieldLen / avgFieldLen))
     */
    private double normalizedTf(int tf, int fieldLen, double avgFieldLen, double b) {
        if (tf <= 0) return 0.0;
        if (fieldLen <= 0 || avgFieldLen <= 0) return tf;
        double denom = 1.0 - b + b * (fieldLen / avgFieldLen);
        if (denom <= 0) return tf;
        return tf / denom;
    }

    /**
     * 归一化BM25F分数到0-1区间，使用sigmoid-like压缩。
     */
    private double normalizeScore(double rawScore) {
        if (rawScore <= 0) return 0.0;
        // BM25F对于一般查询原始分数通常在0-20之间；通过tanh压缩到0-1
        return Math.tanh(rawScore / 5.0);
    }

    /**
     * 统计sub在str中的非重叠出现次数。
     */
    static int countOccurrences(String str, String sub) {
        if (StringUtils.isEmpty(str) || StringUtils.isEmpty(sub)) return 0;
        int count = 0;
        int idx = 0;
        while ((idx = str.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }

    /**
     * 内部：chunk的字段文本缓存。
     */
    private record ChunkFields(FilingChunk chunk, String title, String content) {}
}
