package io.invest.iagent.rag.filing.ingest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PeriodParserTest {

    @Test
    void parsesYyyyQn() {
        PeriodParser.ParsedPeriod p = PeriodParser.parse("2026Q1");
        assertThat(p.fiscalYear()).isEqualTo(2026);
        assertThat(p.period()).isEqualTo("2026Q1");
    }

    @Test
    void parsesReversedQnYyyy() {
        PeriodParser.ParsedPeriod p = PeriodParser.parse("Q12025");
        assertThat(p.fiscalYear()).isEqualTo(2025);
        assertThat(p.period()).isEqualTo("2025Q1");
    }

    @Test
    void parsesHalfYear() {
        PeriodParser.ParsedPeriod p = PeriodParser.parse("2024H1");
        assertThat(p.fiscalYear()).isEqualTo(2024);
        assertThat(p.period()).isEqualTo("2024H1");
    }

    @Test
    void parsesFyPrefix() {
        PeriodParser.ParsedPeriod p = PeriodParser.parse("FY2025");
        assertThat(p.fiscalYear()).isEqualTo(2025);
        assertThat(p.period()).isEqualTo("FY2025");
    }

    @Test
    void parsesFySuffix() {
        PeriodParser.ParsedPeriod p = PeriodParser.parse("2025FY");
        assertThat(p.fiscalYear()).isEqualTo(2025);
        assertThat(p.period()).isEqualTo("FY2025");
    }

    @Test
    void plainYearReturnsYearOnly() {
        PeriodParser.ParsedPeriod p = PeriodParser.parse("2025");
        assertThat(p.fiscalYear()).isEqualTo(2025);
        assertThat(p.period()).isNull();
    }

    @Test
    void blankReturnsNulls() {
        assertThat(PeriodParser.parse(null).fiscalYear()).isNull();
        assertThat(PeriodParser.parse("  ").period()).isNull();
        assertThat(PeriodParser.parse("not a period").period()).isNull();
    }

    @Test
    void canonicalNormalizes() {
        assertThat(PeriodParser.canonical("q1-2025")).isEqualTo("2025Q1");
        assertThat(PeriodParser.canonical("FY 2025")).isEqualTo("FY2025");
    }
}
