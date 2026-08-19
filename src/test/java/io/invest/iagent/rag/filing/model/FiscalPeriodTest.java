package io.invest.iagent.rag.filing.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FiscalPeriodTest {

    @Test
    void ordersQuartersWithinYear() {
        FiscalPeriod q1 = FiscalPeriod.parse("2025Q1");
        FiscalPeriod q2 = FiscalPeriod.parse("2025Q2");
        FiscalPeriod q4 = FiscalPeriod.parse("2025Q4");
        assertThat(q1.compareTo(q2)).isNegative();
        assertThat(q4.compareTo(q2)).isPositive();
    }

    @Test
    void ordersHalfYearBetweenQuarters() {
        assertThat(FiscalPeriod.parse("2025Q1").compareTo(FiscalPeriod.parse("2025H1"))).isNegative();
        assertThat(FiscalPeriod.parse("2025H1").compareTo(FiscalPeriod.parse("2025Q3"))).isNegative();
        assertThat(FiscalPeriod.parse("2025Q3").compareTo(FiscalPeriod.parse("2025H2"))).isNegative();
    }

    @Test
    void ordersFyAfterQ4() {
        assertThat(FiscalPeriod.parse("2025Q4").compareTo(FiscalPeriod.parse("FY2025"))).isNegative();
    }

    @Test
    void ordersAcrossYears() {
        assertThat(FiscalPeriod.parse("FY2024").compareTo(FiscalPeriod.parse("2025Q1"))).isNegative();
    }

    @Test
    void previousQuarterWrapsYear() {
        assertThat(FiscalPeriod.parse("2025Q1").previous().canonical()).isEqualTo("2024Q4");
        assertThat(FiscalPeriod.parse("2025Q2").previous().canonical()).isEqualTo("2025Q1");
        assertThat(FiscalPeriod.parse("2025Q4").previous().canonical()).isEqualTo("2025Q3");
    }

    @Test
    void yearAgoKeepsOrdinal() {
        assertThat(FiscalPeriod.parse("2025Q3").yearAgo().canonical()).isEqualTo("2024Q3");
        assertThat(FiscalPeriod.parse("2025H2").yearAgo().canonical()).isEqualTo("2024H2");
        assertThat(FiscalPeriod.parse("FY2025").yearAgo().canonical()).isEqualTo("FY2024");
    }

    @Test
    void unparseableReturnsNull() {
        assertThat(FiscalPeriod.parse("garbage")).isNull();
        assertThat(FiscalPeriod.parse("")).isNull();
    }
}
