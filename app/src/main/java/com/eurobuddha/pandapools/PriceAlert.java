package com.eurobuddha.pandapools;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Background "pool ≥ 10 % below OR above market" alert for the MINIMA / mxUSDT pair.
 *
 * READ-ONLY: scans pools (the same PoolBook scan the Swap page uses) and reads the MEXC mid. It never builds,
 * signs or posts anything, so it cannot touch funds or WOTS key uses. Driven by {@link PoolKeepAliveService}'s
 * ~15-minute heartbeat, so it runs while the app is closed.
 *
 * Pool spot = Σ reserveT / Σ reserveM over every live pool whose token is the CANONICAL mxUSDT tokenid — never by
 * name, so a look-alike token called "USDT" can't trigger (or fake) an alert.
 */
public final class PriceAlert {
    private PriceAlert() {}

    static final String USDT_TOKENID = "0x7D39745FBD29049BE29850B55A18BF550E4D442F930F86266E34193D89042A90";

    private static final String PREFS = "pp_price_alert";
    private static final String K_ENABLED = "enabled";
    private static final String K_LAST = "last_alert_pct";   // absent ⇒ armed
    private static final String CH = "pp_price_alert";
    private static final int NOTIF_ID = 7302;

    public static boolean enabled(Context ctx) {
        return prefs(ctx).getBoolean(K_ENABLED, true);
    }

    public static void setEnabled(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(K_ENABLED, on).apply();
    }

    /** One check: scan pools → fetch MEXC → evaluate → maybe notify. All callbacks land on the main thread. */
    public static void check(Context ctx, NodeApi node) {
        final Context app = ctx.getApplicationContext();
        if (!enabled(app) || node == null) return;
        new PoolBook(node).scan(new PoolBook.Listener() {
            @Override public void onPools(List<Pool> pools) {
                final BigDecimal spot = usdtSpot(pools);
                if (spot == null) return;   // no live mxUSDT pool → nothing to compare
                MarketPrice.refreshThen(() -> evaluate(app, spot));
            }
            @Override public void onError(String msg) { /* try again next heartbeat */ }
        });
    }

    static BigDecimal usdtSpot(List<Pool> pools) {
        if (pools == null) return null;
        BigDecimal m = BigDecimal.ZERO, t = BigDecimal.ZERO;
        for (Pool p : pools) {
            if (p == null || !p.funded() || p.tok == null || !USDT_TOKENID.equalsIgnoreCase(p.tok)) continue;
            m = m.add(p.reserveM);
            t = t.add(p.reserveT);
        }
        if (m.signum() <= 0 || t.signum() <= 0) return null;
        return t.divide(m, 12, RoundingMode.DOWN);
    }

    private static void evaluate(Context app, BigDecimal spot) {
        if (!MarketPrice.fresh()) return;   // stale/unavailable MEXC → never alert on old data
        BigDecimal mid = BigDecimal.valueOf(MarketPrice.mid());
        BigDecimal pct = PriceAlertRule.pct(spot, mid);
        SharedPreferences sp = prefs(app);
        String lastS = sp.getString(K_LAST, null);
        BigDecimal last = null;
        try { if (lastS != null) last = new BigDecimal(lastS); } catch (Exception ignored) {}
        PriceAlertRule.Result r = PriceAlertRule.evaluate(pct, last);
        SharedPreferences.Editor ed = sp.edit();
        if (r.lastAlertPct == null) ed.remove(K_LAST); else ed.putString(K_LAST, r.lastAlertPct.toPlainString());
        ed.apply();
        if (r.fire) notify(app, pct, spot, mid);
    }

    private static void notify(Context app, BigDecimal pct, BigDecimal spot, BigDecimal mid) {
        try {
            NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel(CH, "Відхилення ціни від ринку", NotificationManager.IMPORTANCE_HIGH));
            }
            PendingIntent pi = PendingIntent.getActivity(app, 25, new Intent(app, MainActivity.class),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            boolean cheaper = pct.signum() < 0;
            String pctTxt = pct.abs().setScale(1, RoundingMode.HALF_UP).toPlainString();
            String title = cheaper
                    ? "MINIMA у пулі на " + pctTxt + "% ДЕШЕВШЕ за ринок — вигідно купити"
                    : "MINIMA у пулі на " + pctTxt + "% ДОРОЖЧЕ за ринок — вигідно продати";
            String body = "Пул " + six(spot) + " USDT/MINIMA · MEXC " + six(mid) + " USDT/MINIMA";
            nm.notify(NOTIF_ID, new NotificationCompat.Builder(app, CH)
                    .setSmallIcon(android.R.drawable.stat_notify_more)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(body
                            + "\nЦе ціна спот; фактична ціна обміну залежить від суми (комісія 0,5% + проковзування)."))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .setContentIntent(pi)
                    .build());
        } catch (Exception ignored) { /* e.g. POST_NOTIFICATIONS denied — nothing else to do */ }
    }

    private static String six(BigDecimal b) {
        return Util.tidyAmount(b.setScale(6, RoundingMode.DOWN).toPlainString());
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
