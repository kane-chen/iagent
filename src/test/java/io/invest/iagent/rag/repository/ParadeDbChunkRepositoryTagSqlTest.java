package io.invest.iagent.rag.repository;

import io.invest.iagent.rag.model.TagCondition;
import io.invest.iagent.rag.model.TagFilter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 针对 {@link ParadeDbChunkRepository#appendTagFilter} 的 SQL 构造单测，
 * 不依赖数据库。
 */
class ParadeDbChunkRepositoryTagSqlTest {

    @Test
    void nullFilter_appendsNothing() {
        StringBuilder sql = new StringBuilder("SELECT 1");
        List<Object> args = new ArrayList<>();
        ParadeDbChunkRepository.appendTagFilter(sql, args, null, "e.kb", "e.cid");
        assertThat(sql).hasToString("SELECT 1");
        assertThat(args).isEmpty();
    }

    @Test
    void emptyFilter_appendsNothing() {
        StringBuilder sql = new StringBuilder("SELECT 1");
        List<Object> args = new ArrayList<>();
        ParadeDbChunkRepository.appendTagFilter(sql, args, TagFilter.builder().build(), "e.kb", "e.cid");
        assertThat(sql).hasToString("SELECT 1");
        assertThat(args).isEmpty();
    }

    @Test
    void singleEq_generatesOneExistsWithTwoParams() {
        StringBuilder sql = new StringBuilder("SELECT 1");
        List<Object> args = new ArrayList<>();
        TagFilter filter = TagFilter.of(TagCondition.eq("ticker", "00700"));

        ParadeDbChunkRepository.appendTagFilter(sql, args, filter, "e.knowledge_base_id", "e.chunk_id");

        String s = sql.toString();
        assertThat(s).contains("AND EXISTS (SELECT 1 FROM chunk_tags ct_f0");
        assertThat(s).contains("ct_f0.knowledge_base_id = e.knowledge_base_id");
        assertThat(s).contains("ct_f0.chunk_id = e.chunk_id");
        assertThat(s).contains("ct_f0.tag_key = ?");
        assertThat(s).contains("ct_f0.tag_value = ?");
        // key + value 两个参数，顺序正确
        assertThat(args).containsExactly("ticker", "00700");
        // 不存在 IN
        assertThat(s).doesNotContain(" IN (");
    }

    @Test
    void singleIn_generatesPlaceholdersPerValue() {
        StringBuilder sql = new StringBuilder("SELECT 1");
        List<Object> args = new ArrayList<>();
        TagFilter filter = TagFilter.of(TagCondition.in("fiscal_period", List.of("2025Q1", "2025Q2", "2025Q3")));

        ParadeDbChunkRepository.appendTagFilter(sql, args, filter, "e.kb", "e.cid");

        String s = sql.toString();
        assertThat(s).contains("ct_f0.tag_value IN (?,?,?)");
        assertThat(args).containsExactly("fiscal_period", "2025Q1", "2025Q2", "2025Q3");
    }

    @Test
    void multipleConditions_areAndedWithUniqueAliases() {
        StringBuilder sql = new StringBuilder("SELECT 1");
        List<Object> args = new ArrayList<>();
        TagFilter filter = TagFilter.builder()
                .conditions(List.of(
                        TagCondition.eq("ticker", "00700"),
                        TagCondition.in("fiscal_period", List.of("2025Q1", "2025Q2"))
                ))
                .build();

        ParadeDbChunkRepository.appendTagFilter(sql, args, filter, "e.kb", "e.cid");

        String s = sql.toString();
        // 两个 EXISTS，AND 组合，别名分别为 ct_f0 / ct_f1
        assertThat(s).contains("ct_f0.").contains("ct_f1.");
        // 每个 condition 都是 AND EXISTS ( ... )
        assertThat(s).contains("AND EXISTS").containsOnlyOnce("ct_f0.tag_value = ?");
        assertThat(s).contains("ct_f1.tag_value IN (?,?)");
        // 参数顺序：key1,val1,key2,val2a,val2b
        assertThat(args).containsExactly("ticker", "00700", "fiscal_period", "2025Q1", "2025Q2");
    }

    @Test
    void illegalKey_throws() {
        StringBuilder sql = new StringBuilder("SELECT 1");
        List<Object> args = new ArrayList<>();
        TagFilter filter = TagFilter.of(TagCondition.eq("bad key'; DROP TABLE x", "v"));
        assertThatThrownBy(() ->
                ParadeDbChunkRepository.appendTagFilter(sql, args, filter, "e.kb", "e.cid"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
