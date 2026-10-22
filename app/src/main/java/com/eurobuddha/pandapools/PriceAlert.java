package com.eurobuddha.pandapools;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Background "pool ≥ N % below OR above market" alert for the MINIMA / mxUSDT pair. N, on/off and the
 * notification language (Ukrainian / English) are user settings (see {@link #showSettings}).
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
    private static final String K_THRESHOLD = "threshold_pct";
    private static final String K_LANG = "lang";              // "uk" | "en"
    private static final String K_INTERVAL = "interval_min";  // 5 | 10 | 15
    private static final long IN_APP_GAP_MS = 60_000;         // in-app (per-block) checks: at most once a minute
    private static long lastInAppMs = 0;
    private static final String K_LAST = "last_alert_pct";    // absent ⇒ armed
    private static final String CH = "pp_price_alert";
    private static final int NOTIF_ID = 7302;

    // ---------------- settings ----------------

    public static boolean enabled(Context ctx) { return prefs(ctx).getBoolean(K_ENABLED, true); }

    public static BigDecimal threshold(Context ctx) {
        try { return PriceAlertRule.clampThreshold(new BigDecimal(prefs(ctx).getString(K_THRESHOLD, "10"))); }
        catch (Exception e) { return PriceAlertRule.DEFAULT_THRESHOLD; }
    }

    /** Background check interval in minutes (5 / 10 / 15; default 10). */
    public static int intervalMin(Context ctx) {
        int m = prefs(ctx).getInt(K_INTERVAL, 10);
        return (m == 5 || m == 10 || m == 15) ? m : 10;
    }

    /** Heartbeat interval: the chosen alert interval while alerts are on, else the original 15 min. */
    public static long heartbeatMs(Context ctx) {
        return (enabled(ctx) ? intervalMin(ctx) : 15) * 60_000L;
    }

    public static boolean english(Context ctx) { return "en".equals(prefs(ctx).getString(K_LANG, "uk")); }

    /** Text for the resident foreground notification while the alert is on. */
    public static String watchingText(Context ctx) {
        String n = threshold(ctx).toPlainString();
        int m = intervalMin(ctx);
        return english(ctx)
                ? "Watching MINIMA/USDT every " + m + " min (alert at ≥" + n + "% from market)"
                : "Стежу за MINIMA/USDT кожні " + m + " хв (сповіщення при відхиленні ≥" + n + "%)";
    }

    /** Settings dialog: on/off, threshold %, interval, language. The dialog's own text follows the language
     *  radio LIVE, so flipping Українська/English re-labels everything at once. {@code onSaved} runs after a save
     *  (e.g. to (re)start the background service). */
    public static void showSettings(final Activity act, final Runnable onSaved) {
        int pad = Ui.dp(act, 20);
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, Ui.dp(act, 8), pad, 0);

        final Switch sw = new Switch(act);
        sw.setChecked(enabled(act));
        box.addView(sw);

        final TextView lbl = new TextView(act);
        box.addView(lbl);
        final EditText pct = new EditText(act);
        pct.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        pct.setText(threshold(act).toPlainString());
        pct.setHint("10");
        box.addView(pct);

        final TextView il = new TextView(act);
        box.addView(il);
        final RadioGroup ig = new RadioGroup(act);
        ig.setOrientation(LinearLayout.HORIZONTAL);
        final int[] mins = {5, 10, 15};
        final RadioButton[] minBtns = new RadioButton[mins.length];
        for (int i = 0; i < mins.length; i++) {
            minBtns[i] = new RadioButton(act);
            minBtns[i].setId(100 + mins[i]);
            ig.addView(minBtns[i]);
        }
        ig.check(100 + intervalMin(act));
        box.addView(ig);
        final TextView inote = new TextView(act);
        inote.setTextSize(12);
        box.addView(inote);

        final TextView ll = new TextView(act);
        box.addView(ll);
        final RadioGroup rg = new RadioGroup(act);
        final RadioButton uk = new RadioButton(act); uk.setText("Українська"); uk.setId(1);
        final RadioButton eng = new RadioButton(act); eng.setText("English"); eng.setId(2);
        rg.addView(uk); rg.addView(eng);
        rg.check(english(act) ? 2 : 1);
        box.addView(rg);

        final AlertDialog dlg = new AlertDialog.Builder(act)
                .setTitle(" ")
                .setView(box)
                .setPositiveButton(" ", (d, w) -> {
                    BigDecimal t;
                    try { t = new BigDecimal(pct.getText().toString().trim().replace(',', '.')); }
                    catch (Exception e) { t = null; }
                    t = PriceAlertRule.clampThreshold(t);
                    boolean changed = t.compareTo(threshold(act)) != 0;
                    SharedPreferences.Editor ed = prefs(act).edit()
                            .putBoolean(K_ENABLED, sw.isChecked())
                            .putString(K_THRESHOLD, t.toPlainString())
                            .putString(K_LANG, rg.getCheckedRadioButtonId() == 2 ? "en" : "uk")
                            .putInt(K_INTERVAL, Math.max(5, Math.min(15, ig.getCheckedRadioButtonId() - 100)));
                    if (changed || !sw.isChecked()) ed.remove(K_LAST);   // new threshold / off → start fresh
                    ed.apply();
                    boolean nowEn = english(act);
                    Toast.makeText(act, sw.isChecked()
                            ? (nowEn ? "Alert on: ±" : "Сповіщення увімкнено: ±") + t.toPlainString() + "%"
                            : (nowEn ? "Price alerts off" : "Сповіщення вимкнено"), Toast.LENGTH_SHORT).show();
                    if (onSaved != null) onSaved.run();
                })
                .setNegativeButton(" ", null)
                .create();

        // Every visible string in one place, re-applied whenever the language radio changes.
        final Runnable relabel = () -> {
            boolean en = rg.getCheckedRadioButtonId() == 2;
            dlg.setTitle(en ? "Price alert" : "Сповіщення про ціну");
            sw.setText(en ? "Price alerts" : "Сповіщення про ціну");
            lbl.setText(en ? "\nAlert when the pool is this many % below or above MEXC:"
                           : "\nСповіщати, коли пул на стільки % дешевший або дорожчий за MEXC:");
            il.setText(en ? "\nCheck every (in background):" : "\nПеревіряти кожні (у фоні):");
            for (int i = 0; i < mins.length; i++) minBtns[i].setText(mins[i] + (en ? " min  " : " хв  "));
            inote.setText(en ? "While the app is open, the price is checked with every new block."
                             : "Поки застосунок відкритий, ціна перевіряється з кожним новим блоком.");
            ll.setText(en ? "\nLanguage:" : "\nМова:");
            if (dlg.getButton(AlertDialog.BUTTON_POSITIVE) != null) {
                dlg.getButton(AlertDialog.BUTTON_POSITIVE).setText(en ? "Save" : "Зберегти");
                dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setText(en ? "Cancel" : "Скасувати");
            }
        };
        rg.setOnCheckedChangeListener((group, id) -> relabel.run());
        relabel.run();                       // labels before show (buttons don't exist yet)
        dlg.setOnShowListener(d -> relabel.run());   // …and again once the buttons exist
        dlg.show();
    }

    // ---------------- the check ----------------

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

    /** In-app path: called with every shared pool scan (one per new block while the app is open). Reuses the
     *  scan the Swap page already did — no extra node queries — and fetches MEXC at most once a minute. */
    public static void onPools(Context ctx, List<Pool> pools) {
        final Context app = ctx.getApplicationContext();
        if (!enabled(app)) return;
        long now = System.currentTimeMillis();
        if (now - lastInAppMs < IN_APP_GAP_MS) return;
        final BigDecimal spot = usdtSpot(pools);
        if (spot == null) return;
        lastInAppMs = now;
        MarketPrice.refreshThen(() -> evaluate(app, spot));
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
        if (!enabled(app)) return;          // switched off while the fetch was in flight
        if (!MarketPrice.fresh()) return;   // stale/unavailable MEXC → never alert on old data
        BigDecimal mid = BigDecimal.valueOf(MarketPrice.mid());
        BigDecimal pct = PriceAlertRule.pct(spot, mid);
        SharedPreferences sp = prefs(app);
        String lastS = sp.getString(K_LAST, null);
        BigDecimal last = null;
        try { if (lastS != null) last = new BigDecimal(lastS); } catch (Exception ignored) {}
        PriceAlertRule.Result r = PriceAlertRule.evaluate(pct, last, threshold(app));
        SharedPreferences.Editor ed = sp.edit();
        if (r.lastAlertPct == null) ed.remove(K_LAST); else ed.putString(K_LAST, r.lastAlertPct.toPlainString());
        ed.apply();
        if (r.fire) notify(app, pct, spot, mid);
    }

    private static void notify(Context app, BigDecimal pct, BigDecimal spot, BigDecimal mid) {
        try {
            NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            boolean en = english(app);
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel(CH,
                        en ? "Price vs market" : "Відхилення ціни від ринку", NotificationManager.IMPORTANCE_HIGH));
            }
            PendingIntent pi = PendingIntent.getActivity(app, 25, new Intent(app, MainActivity.class),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            boolean cheaper = pct.signum() < 0;
            String p = pct.abs().setScale(1, RoundingMode.HALF_UP).toPlainString();
            // Action FIRST and short, so it survives truncation on a narrow phone screen.
            String title;
            if (en) title = cheaper ? "🟢 BUY: pool −" + p + "% vs market"
                                    : "🔴 SELL: pool +" + p + "% vs market";
            else    title = cheaper ? "🟢 КУПИТИ: пул −" + p + "% від ринку"
                                    : "🔴 ПРОДАТИ: пул +" + p + "% від ринку";
            nm.notify(NOTIF_ID, new NotificationCompat.Builder(app, CH)
                    .setSmallIcon(android.R.drawable.stat_notify_more)
                    .setContentTitle(title)   // title only — the user asked for no body text
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
