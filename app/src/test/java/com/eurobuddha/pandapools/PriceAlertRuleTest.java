package com.eurobuddha.pandapools;

import static org.junit.Assert.*;

import java.math.BigDecimal;
import org.junit.Test;

public class PriceAlertRuleTest {
    private BigDecimal last;
    private boolean step(String p, String n) {
        PriceAlertRule.Result r = PriceAlertRule.evaluate(new BigDecimal(p), last, new BigDecimal(n));
        last = r.lastAlertPct;
        return r.fire;
    }

    @Test public void tenPercentEitherSideWithHysteresis() {
        assertFalse(step("-9.99", "10"));
        assertTrue(step("-10", "10"));      // ≥10 % cheaper
        assertFalse(step("-11", "10"));     // already told
        assertTrue(step("-15.1", "10"));    // widened by ≥5 points
        assertFalse(step("-9", "10"));      // hysteresis gap
        assertFalse(step("-7.9", "10"));    // re-armed
        assertNull(last);
        assertTrue(step("12", "10"));       // ≥10 % dearer
        assertTrue(step("-10.5", "10"));    // flipped side
    }

    @Test public void customThreshold() {
        assertFalse(step("-2.9", "3"));
        assertTrue(step("-3", "3"));
        assertFalse(step("-4", "3"));       // step is 1.5
        assertTrue(step("-4.5", "3"));
        assertFalse(step("-2.5", "3"));     // gap (re-arm under 2.4)
        assertFalse(step("-2.3", "3"));
        assertNull(last);
    }

    @Test public void clampAndPct() {
        assertEquals(0, PriceAlertRule.clampThreshold(new BigDecimal("0.1")).compareTo(new BigDecimal("0.5")));
        assertEquals(0, PriceAlertRule.clampThreshold(new BigDecimal("500")).compareTo(new BigDecimal("90")));
        assertEquals(0, PriceAlertRule.clampThreshold(null).compareTo(new BigDecimal("10")));
        assertEquals(0, PriceAlertRule.pct(new BigDecimal("0.0018"), new BigDecimal("0.002")).compareTo(new BigDecimal("-10")));
    }
}
