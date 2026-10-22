package com.eurobuddha.pandapools;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Pure decision logic for the "pool is ≥ N % away from market" alert — no Android, so it is unit-testable on the JVM.
 *
 * pct = (poolSpot − marketMid) / marketMid × 100
 *   pct ≤ −N  ⇒ MINIMA is CHEAPER in the pool than on MEXC (buy in the pool)
 *   pct ≥ +N  ⇒ MINIMA is DEARER in the pool than on MEXC (sell into the pool)
 *
 * Fire rules (so a pool parked just past the threshold doesn't buzz every 15 minutes):
 *   - FIRE on the first crossing of ±N, or when the deviation flips to the opposite side;
 *   - FIRE AGAIN on the same side each time the deviation grows by ≥ N/2 points beyond the last alert;
 *   - RE-ARM once |pct| falls back under 0.8·N (hysteresis gap, so noise around N doesn't flap).
 */
public final class PriceAlertRule {
    private PriceAlertRule() {}

    public static final BigDecimal DEFAULT_THRESHOLD = new BigDecimal("10");
    public static final BigDecimal MIN_THRESHOLD = new BigDecimal("0.5");
    public static final BigDecimal MAX_THRESHOLD = new BigDecimal("90");

    /** Percent difference of pool vs market (negative = pool cheaper). Null if inputs are unusable. */
    public static BigDecimal pct(BigDecimal poolSpot, BigDecimal marketMid) {
        if (poolSpot == null || marketMid == null || poolSpot.signum() <= 0 || marketMid.signum() <= 0) return null;
        return poolSpot.subtract(marketMid).divide(marketMid, 8, RoundingMode.HALF_UP).multiply(new BigDecimal("100"));
    }

    /** Clamp a user-entered threshold into [MIN, MAX]; null/invalid → default. */
    public static BigDecimal clampThreshold(BigDecimal t) {
        if (t == null) return DEFAULT_THRESHOLD;
        if (t.compareTo(MIN_THRESHOLD) < 0) return MIN_THRESHOLD;
        if (t.compareTo(MAX_THRESHOLD) > 0) return MAX_THRESHOLD;
        return t.stripTrailingZeros();
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
     * @param threshold    alert threshold N in percent (positive)
     */
    public static Result evaluate(BigDecimal pct, BigDecimal lastAlertPct, BigDecimal threshold) {
        if (pct == null) return new Result(false, lastAlertPct);                    // no data → change nothing
        BigDecimal n = clampThreshold(threshold);
        BigDecimal rearm = n.multiply(new BigDecimal("0.8"));
        BigDecimal step = n.divide(new BigDecimal("2"), 8, RoundingMode.HALF_UP);
        BigDecimal dev = pct.abs();
        if (dev.compareTo(n) >= 0) {
            if (lastAlertPct == null || lastAlertPct.signum() != pct.signum()) return new Result(true, pct);  // new / flipped
            if (dev.subtract(lastAlertPct.abs()).compareTo(step) >= 0) return new Result(true, pct);          // widened
            return new Result(false, lastAlertPct);                                 // still out, already told
        }
        if (dev.compareTo(rearm) < 0) return new Result(false, null);               // back near market → re-arm
        return new Result(false, lastAlertPct);                                     // in the hysteresis gap
    }
}
