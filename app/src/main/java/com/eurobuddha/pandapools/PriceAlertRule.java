package com.eurobuddha.pandapools;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Pure decision logic for the "pool is ≥ N % away from market" alert — no Android, so it is unit-testable on the JVM.
 *
 * pct = (poolSpot − marketMid) / marketMid × 100
 *   pct ≤ −10  ⇒ MINIMA is ≥10 % CHEAPER in the pool than on MEXC (buy in the pool)
 *   pct ≥ +10  ⇒ MINIMA is ≥10 % DEARER in the pool than on MEXC (sell into the pool)
 *
 * Fire rules (so a pool parked at −11 % doesn't buzz every 15 minutes):
 *   - FIRE on the first crossing of ±THRESHOLD, or when the deviation flips to the opposite side;
 *   - FIRE AGAIN on the same side each time the deviation grows by ≥ DEEPEN_STEP points beyond the last alert;
 *   - RE-ARM once |pct| falls back under REARM (hysteresis gap, so noise around 10 % doesn't flap).
 */
public final class PriceAlertRule {
    private PriceAlertRule() {}

    public static final BigDecimal THRESHOLD   = new BigDecimal("10");  // alert at ≥ 10 % either side
    public static final BigDecimal REARM       = new BigDecimal("8");   // re-arm under 8 %
    public static final BigDecimal DEEPEN_STEP = new BigDecimal("5");   // re-alert every further 5 points

    /** Percent difference of pool vs market (negative = pool cheaper). Null if inputs are unusable. */
    public static BigDecimal pct(BigDecimal poolSpot, BigDecimal marketMid) {
        if (poolSpot == null || marketMid == null || poolSpot.signum() <= 0 || marketMid.signum() <= 0) return null;
        return poolSpot.subtract(marketMid).divide(marketMid, 8, RoundingMode.HALF_UP).multiply(new BigDecimal("100"));
    }

    /** Outcome of one evaluation. {@code lastAlertPct} (signed) is null when armed (no active alert). */
    public static final class Result {
        public final boolean fire;
        public final BigDecimal lastAlertPct;
        Result(boolean fire, BigDecimal lastAlertPct) { this.fire = fire; this.lastAlertPct = lastAlertPct; }
    }

    /**
     * @param pct          current signed pool-vs-market percent (see {@link #pct})
     * @param lastAlertPct signed pct at the last alert while still outside the band, or null if armed
     */
    public static Result evaluate(BigDecimal pct, BigDecimal lastAlertPct) {
        if (pct == null) return new Result(false, lastAlertPct);                    // no data → change nothing
        BigDecimal dev = pct.abs();
        if (dev.compareTo(THRESHOLD) >= 0) {
            if (lastAlertPct == null || lastAlertPct.signum() != pct.signum()) return new Result(true, pct);  // new / flipped
            if (dev.subtract(lastAlertPct.abs()).compareTo(DEEPEN_STEP) >= 0) return new Result(true, pct);   // widened
            return new Result(false, lastAlertPct);                                 // still out, already told
        }
        if (dev.compareTo(REARM) < 0) return new Result(false, null);               // back near market → re-arm
        return new Result(false, lastAlertPct);                                     // in the hysteresis gap
    }
}
