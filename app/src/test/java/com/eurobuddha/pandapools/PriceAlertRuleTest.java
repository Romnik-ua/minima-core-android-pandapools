package com.eurobuddha.pandapools;

import static org.junit.Assert.*;

import java.math.BigDecimal;
import org.junit.Test;

public class PriceAlertRuleTest {
    private BigDecimal last;
    private boolean step(String p) {
        PriceAlertRule.Result r = PriceAlertRule.evaluate(new BigDecimal(p), last);
        last = r.lastAlertPct;
        return r.fire;
    }

    @Test public void firesAtTenPercentEitherSideWithHysteresis() {
        assertFalse(step("-9.99"));
        assertTrue(step("-10"));      // ≥10 % cheaper
        assertFalse(step("-11"));     // already told
        assertTrue(step("-15.1"));    // widened by ≥5 points
        assertFalse(step("-9"));      // hysteresis gap
        assertFalse(step("-7.9"));    // re-armed
        assertNull(last);
        assertTrue(step("12"));       // ≥10 % dearer
        assertTrue(step("-10.5"));    // flipped side
    }

    @Test public void pctSign() {
        assertEquals(0, PriceAlertRule.pct(new BigDecimal("0.0018"), new BigDecimal("0.002")).compareTo(new BigDecimal("-10")));
        assertNull(PriceAlertRule.pct(null, BigDecimal.ONE));
    }
}
