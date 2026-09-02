package io.invest.iagent.financial.repository;

import io.invest.iagent.financial.model.CompanyDO;
import io.invest.iagent.financial.model.MetricValueDO;
import io.invest.iagent.financial.model.SegmentDO;
import io.invest.iagent.financial.model.SegmentValueDO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 基于 PostgreSQL/JdbcTemplate 的财务数据仓储实现。
 * 写法与 {@code ParadeDbChunkRepository} 一致：参数绑定 + ON CONFLICT 幂等 upsert。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.financial", name = "enabled", havingValue = "true")
public class JdbcFinancialRepository implements FinancialRepository {

    /** 同一指标多来源时的取值优先级（array_position 越小越优先） */
    private static final String SOURCE_PRIORITY = "ARRAY['FUTU_API','DERIVED','RAG','KEYWORD','SEGMENT_PARSE']::varchar[]";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // =========================================================
    //  写入
    // =========================================================

    @Override
    public void upsertCompany(CompanyDO c) {
        String sql = """
            INSERT INTO fin_company (ticker, market, name, currency, fy_end_month, updated_at)
            VALUES (?, ?, ?, ?, ?, now())
            ON CONFLICT (ticker) DO UPDATE SET
                market = EXCLUDED.market, name = EXCLUDED.name,
                currency = EXCLUDED.currency, fy_end_month = EXCLUDED.fy_end_month,
                updated_at = now()
            """;
        jdbcTemplate.update(sql, c.getTicker(), c.getMarket(), c.getName(),
                c.getCurrency(), c.getFyEndMonth() <= 0 ? 12 : c.getFyEndMonth());
    }

    @Override
    public void batchUpsertMetrics(List<MetricValueDO> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        String sql = """
            INSERT INTO fin_metric_value
                (ticker, fiscal_period, period_type, metric_code, value, yoy, qoq,
                 currency, unit, source, confidence, document_id, chunk_id, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now())
            ON CONFLICT (ticker, fiscal_period, period_type, metric_code, source) DO UPDATE SET
                value = EXCLUDED.value, yoy = EXCLUDED.yoy, qoq = EXCLUDED.qoq,
                currency = EXCLUDED.currency, unit = EXCLUDED.unit,
                confidence = EXCLUDED.confidence, document_id = EXCLUDED.document_id,
                chunk_id = EXCLUDED.chunk_id, updated_at = now()
            """;
        jdbcTemplate.batchUpdate(sql, values, values.size(), (PreparedStatement ps, MetricValueDO v) -> {
            ps.setString(1, v.getTicker());
            ps.setString(2, v.getFiscalPeriod());
            ps.setString(3, v.getPeriodType());
            ps.setString(4, v.getMetricCode());
            setNullableDecimal(ps, 5, v.getValue());
            setNullableDecimal(ps, 6, v.getYoy());
            setNullableDecimal(ps, 7, v.getQoq());
            ps.setString(8, v.getCurrency());
            ps.setString(9, v.getUnit() == null ? "million" : v.getUnit());
            ps.setString(10, v.getSource());
            if (v.getConfidence() == null) {
                ps.setNull(11, Types.INTEGER);
            } else {
                ps.setInt(11, v.getConfidence());
            }
            ps.setString(12, v.getDocumentId());
            ps.setString(13, v.getChunkId());
        });
        log.debug("batchUpsertMetrics: {} rows", values.size());
    }

    @Override
    public void batchUpsertSegments(List<SegmentDO> segments) {
        if (segments == null || segments.isEmpty()) {
            return;
        }
        String sql = """
            INSERT INTO fin_segment (ticker, segment_code, segment_name, parent_code, level, sort_order, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (ticker, segment_code) DO UPDATE SET
                segment_name = EXCLUDED.segment_name, parent_code = EXCLUDED.parent_code,
                level = EXCLUDED.level, sort_order = EXCLUDED.sort_order, updated_at = now()
            """;
        jdbcTemplate.batchUpdate(sql, segments, segments.size(), (PreparedStatement ps, SegmentDO s) -> {
            ps.setString(1, s.getTicker());
            ps.setString(2, s.getSegmentCode());
            ps.setString(3, s.getSegmentName());
            ps.setString(4, s.getParentCode());
            ps.setInt(5, s.getLevel());
            ps.setInt(6, s.getSortOrder());
        });
    }

    @Override
    public void batchUpsertSegmentValues(List<SegmentValueDO> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        String sql = """
            INSERT INTO fin_segment_value
                (ticker, fiscal_period, segment_code, metric_code, value, yoy,
                 currency, unit, source, confidence, document_id, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (ticker, fiscal_period, segment_code, metric_code) DO UPDATE SET
                value = EXCLUDED.value, yoy = EXCLUDED.yoy, currency = EXCLUDED.currency,
                unit = EXCLUDED.unit, source = EXCLUDED.source,
                confidence = EXCLUDED.confidence, document_id = EXCLUDED.document_id,
                updated_at = now()
            """;
        jdbcTemplate.batchUpdate(sql, values, values.size(), (PreparedStatement ps, SegmentValueDO v) -> {
            ps.setString(1, v.getTicker());
            ps.setString(2, v.getFiscalPeriod());
            ps.setString(3, v.getSegmentCode());
            ps.setString(4, v.getMetricCode());
            setNullableDecimal(ps, 5, v.getValue());
            setNullableDecimal(ps, 6, v.getYoy());
            ps.setString(7, v.getCurrency());
            ps.setString(8, v.getUnit() == null ? "million" : v.getUnit());
            ps.setString(9, v.getSource() == null ? "SEGMENT_PARSE" : v.getSource());
            if (v.getConfidence() == null) {
                ps.setNull(10, Types.INTEGER);
            } else {
                ps.setInt(10, v.getConfidence());
            }
            ps.setString(11, v.getDocumentId());
        });
    }

    @Override
    public void deleteMetricsByTicker(String ticker) {
        int n = jdbcTemplate.update("DELETE FROM fin_metric_value WHERE ticker = ?", ticker);
        log.info("deleteMetricsByTicker: ticker={}, deleted={}", ticker, n);
    }

    @Override
    public void deleteSegmentsByTicker(String ticker) {
        int v = jdbcTemplate.update("DELETE FROM fin_segment_value WHERE ticker = ?", ticker);
        int s = jdbcTemplate.update("DELETE FROM fin_segment WHERE ticker = ?", ticker);
        log.info("deleteSegmentsByTicker: ticker={}, values={}, segments={}", ticker, v, s);
    }

    @Override
    public void recordBatch(String ticker, String source, String status, String periods, String report) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO fin_ingest_batch (ticker, source, status, periods, report, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, now())",
                    ticker, source, status, periods, report);
        } catch (Exception e) {
            log.warn("recordBatch failed (ignored): {}", e.getMessage());
        }
    }

    // =========================================================
    //  查询
    // =========================================================

    @Override
    public List<MetricValueDO> queryMetrics(String ticker, List<String> metricCodes, List<String> periods) {
        StringBuilder sql = new StringBuilder("""
            SELECT DISTINCT ON (fiscal_period, period_type, metric_code)
                   ticker, fiscal_period, period_type, metric_code, value, yoy, qoq,
                   currency, unit, source, confidence, document_id, chunk_id
            FROM fin_metric_value
            WHERE ticker = ?
            """);
        List<Object> args = new java.util.ArrayList<>();
        args.add(ticker);
        appendInClause(sql, args, "metric_code", metricCodes);
        appendInClause(sql, args, "fiscal_period", periods);
        sql.append(" ORDER BY fiscal_period, period_type, metric_code, array_position(")
           .append(SOURCE_PRIORITY).append(", source)");
        return jdbcTemplate.query(sql.toString(), (rs, n) -> MetricValueDO.builder()
                .ticker(rs.getString("ticker"))
                .fiscalPeriod(rs.getString("fiscal_period"))
                .periodType(rs.getString("period_type"))
                .metricCode(rs.getString("metric_code"))
                .value(rs.getBigDecimal("value"))
                .yoy(rs.getBigDecimal("yoy"))
                .qoq(rs.getBigDecimal("qoq"))
                .currency(rs.getString("currency"))
                .unit(rs.getString("unit"))
                .source(rs.getString("source"))
                .confidence(rs.getObject("confidence", Integer.class))
                .documentId(rs.getString("document_id"))
                .chunkId(rs.getString("chunk_id"))
                .build(), args.toArray());
    }

    @Override
    public List<String> findMetricPeriods(String ticker) {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT fiscal_period FROM fin_metric_value "
                        + "WHERE ticker = ? AND source = 'FUTU_API' ORDER BY fiscal_period",
                String.class, ticker);
    }

    @Override
    public CompanyDO findCompany(String ticker) {
        List<CompanyDO> list = jdbcTemplate.query(
                "SELECT ticker, market, name, currency, fy_end_month FROM fin_company WHERE ticker = ?",
                (rs, n) -> CompanyDO.builder()
                        .ticker(rs.getString("ticker"))
                        .market(rs.getString("market"))
                        .name(rs.getString("name"))
                        .currency(rs.getString("currency"))
                        .fyEndMonth(rs.getInt("fy_end_month"))
                        .build(),
                ticker);
        return list.isEmpty() ? null : list.get(0);
    }

    @Override
    public List<SegmentDO> findSegments(String ticker) {
        return jdbcTemplate.query(
                "SELECT ticker, segment_code, segment_name, parent_code, level, sort_order "
                        + "FROM fin_segment WHERE ticker = ? ORDER BY sort_order, segment_code",
                (rs, n) -> SegmentDO.builder()
                        .ticker(rs.getString("ticker"))
                        .segmentCode(rs.getString("segment_code"))
                        .segmentName(rs.getString("segment_name"))
                        .parentCode(rs.getString("parent_code"))
                        .level(rs.getInt("level"))
                        .sortOrder(rs.getInt("sort_order"))
                        .build(),
                ticker);
    }

    @Override
    public List<SegmentValueDO> querySegmentValues(String ticker, List<String> periods) {
        StringBuilder sql = new StringBuilder("""
            SELECT ticker, fiscal_period, segment_code, metric_code, value, yoy,
                   currency, unit, source, confidence, document_id
            FROM fin_segment_value WHERE ticker = ?
            """);
        List<Object> args = new java.util.ArrayList<>();
        args.add(ticker);
        appendInClause(sql, args, "fiscal_period", periods);
        sql.append(" ORDER BY fiscal_period, segment_code, metric_code");
        return jdbcTemplate.query(sql.toString(), (rs, n) -> SegmentValueDO.builder()
                .ticker(rs.getString("ticker"))
                .fiscalPeriod(rs.getString("fiscal_period"))
                .segmentCode(rs.getString("segment_code"))
                .metricCode(rs.getString("metric_code"))
                .value(rs.getBigDecimal("value"))
                .yoy(rs.getBigDecimal("yoy"))
                .currency(rs.getString("currency"))
                .unit(rs.getString("unit"))
                .source(rs.getString("source"))
                .confidence(rs.getObject("confidence", Integer.class))
                .documentId(rs.getString("document_id"))
                .build(), args.toArray());
    }

    // =========================================================
    //  工具
    // =========================================================

    /** 动态 IN 条件：列名来自代码内部常量（白名单校验），值全部参数绑定。 */
    private static void appendInClause(StringBuilder sql, List<Object> args, String column, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        if (!column.matches("[a-z_]+")) {
            throw new IllegalArgumentException("illegal column: " + column);
        }
        String placeholders = values.stream().map(v -> "?").collect(Collectors.joining(","));
        sql.append(" AND ").append(column).append(" IN (").append(placeholders).append(")");
        args.addAll(values);
    }

    private static void setNullableDecimal(PreparedStatement ps, int idx, BigDecimal value) throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(idx, Types.DECIMAL);
        } else {
            ps.setBigDecimal(idx, value);
        }
    }
}
