package org.forgesilicon.ceasar;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

public final class MainActivity extends Activity {
    private static final String[] PAGES = {"Today", "Trends", "Sleep", "Insights", "Device"};
    private static final int[] ICONS = {R.drawable.ic_home, R.drawable.ic_trend, R.drawable.ic_moon, R.drawable.ic_spark, R.drawable.ic_device};
    private Palette p;
    private int BG, CARD, TEXT, MUTED;
    private final java.util.concurrent.ExecutorService storage = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private ThermalStore store;
    private Analysis analysis;
    private DemoData demoData;
    private final List<LocalDate> demoStarts = new ArrayList<>();
    private List<LocalDate> realStarts = new ArrayList<>();
    private int page, periodOffset, nightOffset, analysisRequest, generation;
    private Trend.Period period = Trend.Period.DAY;
    private boolean dark = true, fahrenheit, cycleOn, demoCycle = true, devMode, demo, heaterOverlay, destroyed, receiverRegistered, syncing, syncSent, dirty;
    private double rOut = DemoData.R_OUT;
    private ScrollView scroll;
    private TextView heroValue, heroCaption, liveCore, liveTop, liveHeater, liveLock, status, syncStatus, debugText, rateText;
    private TempChart liveChart;
    private Button connectButton, demoButton, syncButton;
    private String connectionMessage = "Connect your wearable to start", syncMessage = "Connect your wearable to import its overnight buffer";
    private final ArrayDeque<ThermalSample> live = new ArrayDeque<>();
    private final ArrayDeque<String> rawLog = new ArrayDeque<>();
    private final Map<Long, ThermalStore.Sums> pending = new HashMap<>();
    private long accepted, rejected, history, lastSampleNanos, syncRequestedNanos, lastFlush, lastAnalysis, demoStartNanos;
    private UsbManager usb;
    private UsbSerial serial;
    private UsbDevice activeDevice;
    private String permissionAction, source = "No wearable connected";

    private final Runnable refresh = new Runnable() {
        @Override public void run() { render(); main.postDelayed(this, 500); }
    };
    private final Runnable simulate = new Runnable() {
        @Override public void run() {
            if (!demo || demoData == null) return;
            long n = SystemClock.elapsedRealtimeNanos();
            add(demoData.live(System.currentTimeMillis(), n, (n - demoStartNanos) / 1_000_000));
            main.postDelayed(this, 1000);
        }
    };
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (permissionAction.equals(intent.getAction())) {
                if (activeDevice == null || device == null || device.getDeviceId() != activeDevice.getDeviceId()) return;
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) open(device);
                else { disconnect(); setStatus("USB permission denied · tap Connect to retry"); }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction()) && activeDevice != null
                    && device != null && device.getDeviceId() == activeDevice.getDeviceId()) {
                disconnect(); setStatus("Wearable disconnected · plug it in to reconnect");
            }
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        usb = (UsbManager) getSystemService(USB_SERVICE);
        store = new ThermalStore(this);
        SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        dark = prefs.getBoolean("dark", true); fahrenheit = prefs.getBoolean("fahrenheit", false);
        cycleOn = prefs.getBoolean("cycle", false); rOut = prefs.getFloat("rOut", (float) DemoData.R_OUT);
        permissionAction = getPackageName() + ".USB_PERMISSION";
        IntentFilter filter = new IntentFilter(permissionAction);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, filter);
        receiverRegistered = true;
        if (state != null) { page = state.getInt("page"); devMode = state.getBoolean("dev"); }
        applyColors(); buildUi(); requestAnalysis();
    }

    // ---------- view helpers (shared with BEACON) ----------
    private int dp(float n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private GradientDrawable shape(int color, int radius) { GradientDrawable bg = new GradientDrawable(); bg.setColor(color); bg.setCornerRadius(dp(radius)); return bg; }
    private TextView label(String text, int size, int color) {
        TextView v = new TextView(this); v.setText(text); v.setTextSize(size); v.setTextColor(color);
        v.setPadding(0, dp(3), 0, dp(3)); v.setFontFeatureSettings("tnum"); return v;
    }
    private TextView heading(String text, int size) { TextView v = label(text, size, TEXT); v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); return v; }
    private TextView eyebrow(String text, int color) { TextView v = label(text, 11, color); v.setLetterSpacing(.08f); v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); return v; }
    private Button action(String text, boolean primary, int icon) {
        Button b = new Button(this); b.setText(text); b.setAllCaps(false); b.setTextSize(14); b.setLetterSpacing(.01f);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); b.setStateListAnimator(null); b.setElevation(0);
        int fill = primary ? p.fill() : p.chip, foreground = primary ? p.onAccent : p.accent, disabled = dark ? Color.rgb(110, 100, 92) : Color.rgb(170, 160, 150);
        b.setTextColor(new android.content.res.ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}}, new int[]{disabled, foreground}));
        GradientDrawable body = shape(fill, 14);
        body.setColor(new android.content.res.ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}}, new int[]{p.chip, fill}));
        b.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33FF8C3A), body, null));
        b.setMinHeight(dp(48)); b.setMinimumHeight(dp(48)); b.setMinWidth(0); b.setMinimumWidth(0); b.setPadding(dp(14), dp(6), dp(14), dp(6));
        if (icon != 0) { android.graphics.drawable.Drawable d = getDrawable(icon).mutate(); d.setTintList(b.getTextColors()); d.setBounds(0, 0, dp(19), dp(19)); b.setCompoundDrawables(d, null, null, null); b.setCompoundDrawablePadding(dp(7)); }
        return b;
    }
    private ImageButton iconButton(int icon, String description) {
        ImageButton b = new ImageButton(this); b.setImageResource(icon); b.setImageTintList(android.content.res.ColorStateList.valueOf(p.accent));
        b.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33FF8C3A), shape(p.chip, 16), null));
        b.setPadding(dp(13), dp(13), dp(13), dp(13)); b.setContentDescription(description); return b;
    }
    private LinearLayout column() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout card(LinearLayout parent) {
        LinearLayout v = column(); v.setBackground(shape(CARD, 24)); v.setPadding(dp(20), dp(18), dp(20), dp(18));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(16); parent.addView(v, lp); return v;
    }
    private LinearLayout row(LinearLayout parent) {
        LinearLayout v = new LinearLayout(this); v.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(12); parent.addView(v, lp); return v;
    }
    private void rowItem(LinearLayout row, View child, boolean last) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1); lp.rightMargin = last ? 0 : dp(10); row.addView(child, lp);
    }
    private void fullAction(LinearLayout parent, View child) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(48)); lp.topMargin = dp(12); parent.addView(child, lp);
    }
    private Button go(LinearLayout parent, String text, int icon, int target) {
        Button b = action(text, false, icon); b.setOnClickListener(v -> { page = target; buildUi(); scroll.scrollTo(0, 0); }); fullAction(parent, b); return b;
    }
    /** Two-column grid of labelled values. */
    private void stats(LinearLayout parent, String[][] items) {
        for (int i = 0; i < items.length; i += 2) {
            LinearLayout r = row(parent);
            for (int j = i; j < i + 2; j++) {
                LinearLayout cell = column();
                if (j < items.length) {
                    cell.addView(label(items[j][0], 10, MUTED)); cell.addView(heading(items[j][1], 19));
                    if (items[j].length > 2 && items[j][2] != null) cell.addView(label(items[j][2], 11, MUTED));
                }
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1); if (j == i) lp.rightMargin = dp(12); r.addView(cell, lp);
            }
        }
    }
    private TextView chip(LinearLayout parent, String text, int color) {
        TextView t = label(text, 12, color); t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); t.setLetterSpacing(.06f);
        t.setBackground(shape(Palette.alpha(color, dark ? 46 : 30), 12)); t.setPadding(dp(10), dp(5), dp(10), dp(5));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.topMargin = dp(4); lp.bottomMargin = dp(4); parent.addView(t, lp); return t;
    }
    private void tip(LinearLayout parent, String text) {
        TextView t = label("•  " + text, 14, TEXT); t.setLineSpacing(dp(2), 1); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(6); parent.addView(t, lp);
    }
    private void applyColors() {
        p = new Palette(dark); BG = p.bg; CARD = p.card; TEXT = p.text; MUTED = p.muted;
        getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(CARD);
        getWindow().getDecorView().setSystemUiVisibility(dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }
    private AlertDialog.Builder dialog(String title) {
        return new AlertDialog.Builder(new ContextThemeWrapper(this, dark ? android.R.style.Theme_Material_Dialog_Alert : android.R.style.Theme_Material_Light_Dialog_Alert)).setTitle(title);
    }
    private void about(String body) {
        LinearLayout v = column(); v.setPadding(dp(24), dp(12), dp(24), 0);
        ImageView logo = new ImageView(this); logo.setImageResource(dark ? R.drawable.caesar_logo_dark : R.drawable.caesar_logo_light); logo.setAdjustViewBounds(true);
        logo.setContentDescription("CaeSAR, heat to health insights"); logo.setBackground(shape(dark ? Color.BLACK : Color.rgb(242, 235, 217), 16)); logo.setClipToOutline(true);
        v.addView(logo, new LinearLayout.LayoutParams(-1, -2));
        TextView t = label(body, 14, TEXT); t.setPadding(0, dp(14), 0, 0); v.addView(t);
        ScrollView sv = new ScrollView(this); sv.addView(v);
        dialog("About CaeSAR").setView(sv).setPositiveButton("Got it", null).show();
    }
    private void info(String title, String body) { dialog(title).setMessage(body).setPositiveButton("Got it", null).show(); }

    // ---------- formatting ----------
    private ZoneId zone() { return ZoneId.systemDefault(); }
    private String temp(double c) {
        if (!Double.isFinite(c)) return fahrenheit ? "— °F" : "— °C";
        return fahrenheit ? String.format(Locale.getDefault(), "%.1f °F", c * 9 / 5 + 32) : String.format(Locale.getDefault(), "%.2f °C", c);
    }
    /** Thresholds read better without false precision: 38.0 °C, 100.4 °F. */
    private String round(double c) { return fahrenheit ? String.format(Locale.getDefault(), "%.1f °F", c * 9 / 5 + 32) : String.format(Locale.getDefault(), "%.1f °C", c); }
    private String delta(double d) {
        if (!Double.isFinite(d)) return "—";
        return fahrenheit ? String.format(Locale.getDefault(), "%+.1f °F", d * 1.8) : String.format(Locale.getDefault(), "%+.2f °C", d);
    }
    private String size(double d) {
        if (!Double.isFinite(d)) return "—";
        return fahrenheit ? String.format(Locale.getDefault(), "%.1f °F", Math.abs(d) * 1.8) : String.format(Locale.getDefault(), "%.2f °C", Math.abs(d));
    }
    private String clock(long t) { return t <= 0 ? "—" : Instant.ofEpochMilli(t).atZone(zone()).format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())); }
    private String clockHours(double h) {
        if (!Double.isFinite(h)) return "—";
        long m = Math.floorMod(Math.round(h * 60), 1440);
        return LocalTime.of((int) (m / 60), (int) (m % 60)).format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()));
    }
    private String duration(double hours) {
        if (!Double.isFinite(hours)) return "—";
        long m = Math.round(Math.abs(hours) * 60);
        return m < 60 ? m + " min" : m % 60 == 0 ? (m / 60) + " h" : (m / 60) + " h " + (m % 60) + " min";
    }
    private String day(LocalDate d) { return d.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault())); }
    private String shortDay(LocalDate d) { return d.format(DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())); }
    private String chartTemp(double c) { return fahrenheit ? String.format(Locale.getDefault(), "%.1f°", c * 9 / 5 + 32) : String.format(Locale.getDefault(), "%.1f°", c); }
    private String chartDelta(double d) { return fahrenheit ? String.format(Locale.getDefault(), "%+.1f°", d * 1.8) : String.format(Locale.getDefault(), "%+.1f°", d); }

    // ---------- layout ----------
    private void buildUi() {
        int scrollY = scroll == null ? 0 : scroll.getScrollY();
        LinearLayout shell = column(); shell.setBackgroundColor(BG);
        shell.setOnApplyWindowInsetsListener((v, insets) -> { v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom()); return insets; });
        scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        LinearLayout root = column(); root.setPadding(dp(20), dp(12), dp(20), dp(28)); scroll.addView(root);
        shell.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout toolbar = new LinearLayout(this); toolbar.setGravity(Gravity.CENTER_VERTICAL);
        ImageView mark = new ImageView(this); mark.setImageResource(dark ? R.drawable.caesar_emblem_dark : R.drawable.caesar_emblem_light); mark.setContentDescription("CaeSAR emblem");
        mark.setScaleType(ImageView.ScaleType.CENTER_CROP); mark.setBackground(shape(Color.BLACK, 14)); mark.setClipToOutline(true);
        toolbar.addView(mark, new LinearLayout.LayoutParams(dp(46), dp(46)));
        LinearLayout brand = column(); brand.setPadding(dp(10), 0, 0, 0);
        TextView name = heading("CaeSAR", 22); name.setPadding(0, 0, 0, 0); brand.addView(name);
        TextView org = label("HEAT TO HEALTH INSIGHTS", 9, MUTED); org.setLetterSpacing(.14f); org.setPadding(0, 0, 0, 0); brand.addView(org);
        toolbar.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        ImageButton settings = iconButton(R.drawable.ic_settings, "Settings"); settings.setOnClickListener(v -> showSettings());
        toolbar.addView(settings, new LinearLayout.LayoutParams(dp(48), dp(48))); root.addView(toolbar);
        if (demo) { TextView banner = label("DEMO PREVIEW · all temperature data shown is simulated", 12, p.accent); banner.setPadding(0, dp(10), 0, 0); root.addView(banner); }
        String[] titles = {"Your core, at a glance", "Temperature trends", "Sleep, by temperature", "Health insights", "Your wearable"};
        String[] subtitles = {"Zero-heat-flux core temperature, turned into what to do next.", "Every minute of core temperature, by day, week, and month.",
                "How your body cooled, when it bottomed out, and how you woke.", "Illness watch, body clock, cycle, and heat strain.", "Connect, import, and check the live ZHF loop."};
        TextView title = heading(titles[page], 28); title.setPadding(0, dp(12), 0, 0); root.addView(title);
        root.addView(label(subtitles[page], 14, MUTED));
        heroValue = heroCaption = liveCore = liveTop = liveHeater = liveLock = status = syncStatus = debugText = rateText = null; liveChart = null; connectButton = demoButton = syncButton = null;
        boolean ready = analysis != null && !analysis.empty();
        if (page == 4) buildDevice(root);
        else if (analysis == null) { LinearLayout c = card(root); c.addView(heading("Reading your history…", 18)); c.addView(label("Crunching nights, rhythms, and heat episodes.", 13, MUTED)); }
        else if (!ready) { buildEmpty(root); if (page == 3) cycleCard(root, analysis); }
        else if (page == 0) buildToday(root);
        else if (page == 1) buildTrends(root);
        else if (page == 2) buildSleep(root);
        else buildInsights(root);
        LinearLayout nav = new LinearLayout(this); nav.setPadding(dp(8), dp(8), dp(8), dp(8)); nav.setBackgroundColor(CARD);
        for (int i = 0; i < PAGES.length; i++) {
            final int selected = i;
            LinearLayout item = column(); item.setGravity(Gravity.CENTER); item.setContentDescription(PAGES[i]);
            item.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33FF8C3A), shape(Color.TRANSPARENT, 16), null));
            ImageView icon = new ImageView(this); icon.setImageResource(ICONS[i]); icon.setPadding(dp(12), dp(4), dp(12), dp(4));
            icon.setImageTintList(android.content.res.ColorStateList.valueOf(page == i ? p.onAccent : MUTED));
            icon.setBackground(shape(page == i ? p.fill() : Color.TRANSPARENT, 16)); item.addView(icon, new LinearLayout.LayoutParams(dp(50), dp(30)));
            TextView text = label(PAGES[i], 11, page == i ? p.accent : MUTED); text.setGravity(Gravity.CENTER); text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); item.addView(text);
            item.setOnClickListener(v -> { page = selected; buildUi(); scroll.scrollTo(0, 0); });
            nav.addView(item, new LinearLayout.LayoutParams(0, dp(62), 1));
        }
        shell.addView(nav, new LinearLayout.LayoutParams(-1, -2));
        setContentView(shell);
        final int restore = scrollY; scroll.post(() -> scroll.scrollTo(0, restore));
        render();
    }

    /** The full CaeSAR logo on its own background so the artwork's edges never show. */
    private void logoCard(LinearLayout root) {
        LinearLayout c = card(root); c.setBackground(shape(dark ? Color.BLACK : Color.rgb(242, 235, 217), 24)); c.setPadding(dp(28), dp(20), dp(28), dp(16));
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView logo = new ImageView(this); logo.setImageResource(dark ? R.drawable.caesar_logo_dark : R.drawable.caesar_logo_light);
        logo.setAdjustViewBounds(true); logo.setContentDescription("CaeSAR, heat to health insights");
        c.addView(logo, new LinearLayout.LayoutParams(dp(250), -2));
        LinearLayout by = new LinearLayout(this); by.setGravity(Gravity.CENTER_VERTICAL);
        ImageView forge = new ImageView(this); forge.setImageResource(R.drawable.forge_mark); forge.setContentDescription("Forge Silicon Design");
        forge.setScaleType(ImageView.ScaleType.FIT_CENTER); by.addView(forge, new LinearLayout.LayoutParams(dp(30), dp(22)));
        TextView t = label("A FORGE SILICON DESIGN PROJECT", 10, dark ? Color.rgb(200, 168, 104) : Color.rgb(140, 105, 45)); t.setLetterSpacing(.12f); t.setPadding(dp(8), 0, 0, 0); by.addView(t);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2); bp.topMargin = dp(10); c.addView(by, bp);
    }
    private void buildEmpty(LinearLayout root) {
        logoCard(root);
        LinearLayout c = card(root); c.setBackground(shape(p.tint, 24));
        c.addView(eyebrow("CORE TEMPERATURE", p.accent)); c.addView(heading("No readings yet", 30));
        c.addView(label("Wear CaeSAR on your torso, connect it over USB, and leave it on overnight. Insights build from your own nights: sleep after one, illness watch after seven, body clock after three days.", 14, MUTED));
        Button device = action("Connect wearable", true, R.drawable.ic_device); device.setOnClickListener(v -> { page = 4; buildUi(); }); fullAction(c, device);
        Button d = action("Try the demo", false, R.drawable.ic_spark); d.setOnClickListener(v -> toggleDemo()); fullAction(c, d);
        LinearLayout how = card(root); how.addView(heading("How CaeSAR reads core temperature", 18));
        how.addView(label(zhfExplainer(), 13, MUTED));
    }
    private String zhfExplainer() {
        return "Zero heat flux (ZHF): a heater on top of an insulating pad is driven until the sensor above the pad matches the sensor below it. With no temperature difference, no heat flows out through the pad, so the skin under it warms to deep-tissue temperature. The Forge SoC muxes both sensors into its SAR ADC, a RISC-V core runs the heater control loop, and a pipelined FFT block pulls rhythms out of the signal.";
    }

    // ---------- Today ----------
    private void buildToday(LinearLayout root) {
        Analysis a = analysis;
        LinearLayout hero = card(root); hero.setBackground(shape(p.tint, 24));
        hero.addView(eyebrow("CORE TEMPERATURE", p.accent));
        heroValue = heading("—", 52); hero.addView(heroValue); heroCaption = label("", 13, MUTED); hero.addView(heroCaption);
        long now = a.now;
        double usual = a.usualAt(now), latest = a.latestCore();
        Trend today = new Trend(Trend.Period.DAY, 0, now, zone()).fill(a.series);
        stats(hero, new String[][]{
                {"VS YOUR USUAL AT THIS HOUR", Double.isFinite(usual) ? delta(latest - usual) : "Learning", Double.isFinite(usual) ? "14-day average " + temp(usual) : "needs 5 days"},
                {"TODAY'S RANGE", Double.isNaN(today.low) ? "—" : chartTemp(today.low) + " – " + chartTemp(today.high), today.highTime > 0 ? "High at " + clock(today.highTime) : null}});
        go(hero, "View temperature trends", R.drawable.ic_trend, 1);

        illnessCard(root, false);

        Night n = a.lastNight();
        LinearLayout sleep = card(root); sleep.addView(eyebrow("LAST NIGHT", p.sleep));
        if (n == null) sleep.addView(label("Wear CaeSAR overnight to see your sleep window and temperature drop.", 14, MUTED));
        else {
            sleep.addView(heading(duration(n.hours()) + " in bed", 26));
            sleep.addView(label(clock(n.onset) + " – " + clock(n.wake) + " · " + (n.fromHeater ? "from heater power" : "from core temperature"), 13, MUTED));
            stats(sleep, new String[][]{{"TEMPERATURE DROP", size(n.drop()), "evening to overnight low"}, {"LOWEST", temp(n.nadir), "at " + clock(n.nadirTime)},
                    {"SLEEP CYCLES", n.cycles() > 0 ? n.cycles() + " cycles" : "—", Double.isFinite(n.ultradianMinutes) ? String.format(Locale.getDefault(), "~%.0f min rhythm", n.ultradianMinutes) : "rhythm unclear"},
                    {"LOW TO WAKE", duration(n.nadirToWake()), n.nadirToWake() < 1.5 ? "woke near your low" : n.nadirToWake() < 2 ? "aim for 2 h" : "good buffer"}});
            go(sleep, "Sleep details", R.drawable.ic_moon, 2);
        }

        BodyClock c = a.clock;
        LinearLayout rhythm = card(root); rhythm.addView(heading("Rhythm score", 20));
        LinearLayout r = row(rhythm); ScoreRing ring = new ScoreRing(this); ring.setScore(c.score, p); r.addView(ring, new LinearLayout.LayoutParams(dp(128), dp(128)));
        LinearLayout info = column(); LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(0, -2, 1); ip.leftMargin = dp(16); r.addView(info, ip);
        info.addView(heading(c.score < 0 ? "Building your rhythm" : c.score >= 75 ? "Strong, steady rhythm" : c.score >= 50 ? "Rhythm could be steadier" : "Rhythm is flattened", 17));
        info.addView(label(c.score < 0 ? "Needs three days and a few nights." : String.format(Locale.getDefault(), "Daily swing %s · bedtime varies ±%s · overnight drop %s",
                size(2 * c.amplitude), duration(c.onsetSpread), size(c.medianDrop)), 12, MUTED));
        if (Double.isFinite(c.socialJetLag) && Math.abs(c.socialJetLag) >= 1)
            rhythm.addView(label("Social jet lag · your weekend sleep runs " + duration(c.socialJetLag) + " later than weekdays", 13, p.warn));
        TextView how = label("Amplitude, regularity, and overnight drop · tap for the formula", 11, MUTED); rhythm.addView(how);
        rhythm.setOnClickListener(v -> info("Rhythm score", "Rhythm score = 40 × amplitude + 30 × regularity + 30 × overnight drop, each scaled 0 to 1.\n\nAmplitude: half the daily core swing from a 24 h cosine fit over the last 72 h, full marks at 0.35 °C.\nRegularity: 1 − (spread of bedtimes over 7 nights ÷ 1.25 h).\nOvernight drop: median evening-to-low drop over 7 nights, full marks at 0.45 °C.\n\nA strong, regular rhythm is linked to better sleep and alertness. Illness, travel, shift work, and irregular schedules flatten it. This is an experimental wellness score, not a diagnosis."));

        LinearLayout timing = card(root); timing.addView(heading("Your timing today", 20));
        timing.addView(label("From your own temperature rhythm", 12, MUTED));
        stats(timing, new String[][]{
                {"START WINDING DOWN", clockHours(c.onsetHour - 1), "an hour before your usual bedtime"},
                {"PEAK PERFORMANCE", Double.isFinite(c.peakHour) ? clockHours(c.peakHour - 1.5).replace(" AM", "").replace(" PM", "") + "–" + clockHours(c.peakHour + 1.5) : "—", "around your daily temperature high"},
                {"BEST WAKE AFTER", clockHours(c.nadirHour + 2), "2 h past your temperature low"},
                {"WARM SHOWER", clockHours(c.onsetHour - 1.5), "1–2 h before bed helps you cool"}});
        timing.setOnClickListener(v -> info("Your timing", "Core temperature starts falling a couple of hours before natural sleep, and sleep comes easiest while it is dropping. Winding down then works with your rhythm.\n\nStrength, power, and reaction time tend to peak near your daily temperature high, usually late afternoon.\n\nWaking within about 2 h of your overnight low (often 2–3 h before habitual wake) is linked to more grogginess. Your low is measured each night, so these windows move with you.\n\nA warm shower or bath 1–2 h before bed draws heat to the skin and speeds the core drop that precedes sleep."));

        List<HeatStrain.Episode> recent = a.episodesSince(now - 36 * 3_600_000L);
        if (!recent.isEmpty()) {
            HeatStrain.Episode e = recent.get(recent.size() - 1);
            LinearLayout heat = card(root); heat.addView(eyebrow("LATEST WORKOUT HEAT", p.alert));
            heat.addView(heading("Peaked at " + temp(e.peak), 22));
            heat.addView(label(String.format(Locale.getDefault(), "%s · strain %.1f of 10 · cooled in %s", clock(e.peakTime), e.strain(), duration(e.recoveryMinutes() / 60)), 13, MUTED));
            go(heat, "Heat strain details", R.drawable.ic_flame, 3);
        }
    }

    private void illnessCard(LinearLayout root, boolean full) {
        Analysis a = analysis; IllnessWatch w = a.illness;
        LinearLayout c = card(root);
        int color = w.level == IllnessWatch.Level.FEVER || w.level == IllnessWatch.Level.ELEVATED ? p.alert : w.level == IllnessWatch.Level.WATCH ? p.warn : w.level == IllnessWatch.Level.NORMAL ? p.good : p.muted;
        c.addView(eyebrow("ILLNESS WATCH", p.accent));
        String[] titles = {"Learning your baseline", "Nights look normal", "Worth watching", "Possible illness signal", "Fever detected"};
        chip(c, w.level == IllnessWatch.Level.LEARNING ? "LEARNING" : w.level.name(), color);
        c.addView(heading(titles[w.level.ordinal()], 22));
        String compare = w.phaseMatched && a.cycle != null && w.lastNight != null ? " Compared with your " + a.cycle.phase(w.lastNight.date).toLowerCase(Locale.ROOT) + "-phase nights." : "";
        String body;
        switch (w.level) {
            case FEVER: body = String.format(Locale.getDefault(), "Core temperature has held at or above %s for over 30 minutes since %s, peaking at %s. Rest and drink fluids. Contact a clinician if it reaches %s, lasts more than 3 days, or comes with severe symptoms.", round(38.0), clock(w.feverStart), temp(w.feverPeak), round(39.4)); break;
            case ELEVATED: body = String.format(Locale.getDefault(), "Your last %d nights ran %s above your baseline. A rise like this often shows up a day or two before symptoms. Prioritize sleep and fluids, ease off hard training, and consider testing if you feel unwell.", w.elevatedNights, delta(w.lastDelta)) + compare; break;
            case WATCH: body = "Last night ran " + delta(w.lastDelta) + " above your baseline. One warm night can come from alcohol, a late workout, a late meal, or a warm room. Two in a row is a stronger signal." + compare; break;
            case NORMAL: body = "Last night ran " + delta(w.lastDelta) + " vs your baseline of " + w.baselineNights + " nights." + compare; break;
            default: body = "Wear CaeSAR overnight for 7 or more nights. Each night is then compared with your own recent nights.";
        }
        c.addView(label(body, 14, full ? TEXT : MUTED));
        int count = full ? 30 : 14, nn = a.nights.size(), from = Math.max(0, nn - count);
        double[] v = new double[count]; String[] l = new String[count]; boolean[] h = new boolean[count];
        Arrays.fill(v, Double.NaN);
        for (int i = from; i < nn; i++) {
            int k = count - (nn - i);
            v[k] = w.deltas[i]; h[k] = w.flagged[i] || a.nights.get(i).fever;
            if (i == nn - 1 || (nn - 1 - i) % 7 == 0) l[k] = i == nn - 1 ? "Last" : shortDay(a.nights.get(i).date);
        }
        BarChart chart = new BarChart(this); chart.set(v, l, BarChart.Mode.SIGNED, p).colors(p.good, p.alert).highlight(h).threshold(IllnessWatch.DELTA, "+" + (fahrenheit ? "0.5°F" : "0.3°C") + " watch line").axis(this::chartDelta).empty("Needs 7 nights of baseline");
        c.addView(label("Nightly temperature vs your baseline · " + count + " nights", 11, MUTED));
        c.addView(chart, new LinearLayout.LayoutParams(-1, dp(full ? 170 : 130)));
        if (full) {
            tip(c, "Baseline: median of nights 3 to 30 before, so a slow rise can't hide in its own baseline.");
            tip(c, "Flag: at least " + size(IllnessWatch.DELTA) + " above baseline and 2 robust standard deviations out. Two flagged nights in a row raise the signal.");
            tip(c, "Fever: smoothed core at or above " + round(38.0) + " for 30 minutes. Workout heat is excluded.");
            if (a.cycle != null) tip(c, "Cycle-aware: compares nights in the same cycle phase, so the normal post-ovulation rise is not flagged.");
            else tip(c, "Turn on cycle insights to keep the normal post-ovulation rise from being flagged.");
            c.addView(label("Early signal only, not a diagnosis. Alcohol, hard training, travel, and room temperature also move nightly temperature.", 11, MUTED));
        } else {
            Button more = action("Illness watch details", false, R.drawable.ic_shield); more.setOnClickListener(v1 -> { page = 3; buildUi(); scroll.scrollTo(0, 0); }); fullAction(c, more);
        }
    }

    // ---------- Trends ----------
    private void buildTrends(LinearLayout root) {
        Analysis a = analysis;
        LinearLayout c = card(root);
        LinearLayout periods = row(c); String[] names = {"D", "W", "M", "90D"}, full = {"Day", "Week", "Month", "Ninety days"};
        for (int i = 0; i < names.length; i++) {
            final int index = i; Button b = action(names[i], period.ordinal() == i, 0); b.setPadding(0, 0, 0, 0); b.setContentDescription(full[i]);
            b.setOnClickListener(v -> { period = Trend.Period.values()[index]; periodOffset = 0; buildUi(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1); lp.rightMargin = i == names.length - 1 ? 0 : dp(6); periods.addView(b, lp);
        }
        Trend t = new Trend(period, periodOffset, a.now, zone()).fill(a.series), prev = new Trend(period, periodOffset - 1, a.now, zone()).fill(a.series);
        LinearLayout dateRow = row(c); dateRow.addView(heading(t.title, 17), new LinearLayout.LayoutParams(0, -2, 1));
        ImageButton back = iconButton(R.drawable.ic_left, "Previous period"); back.setEnabled(t.start > a.series.start); back.setAlpha(back.isEnabled() ? 1 : .35f);
        back.setOnClickListener(v -> { periodOffset--; buildUi(); }); dateRow.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        ImageButton next = iconButton(R.drawable.ic_right, "Next period"); next.setEnabled(periodOffset < 0); next.setAlpha(periodOffset < 0 ? 1 : .35f);
        next.setOnClickListener(v -> { if (periodOffset < 0) { periodOffset++; buildUi(); } });
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(dp(48), dp(48)); np.leftMargin = dp(8); dateRow.addView(next, np);
        c.addView(heading(temp(t.avg), 36));
        c.addView(label(t.minutes == 0 ? "No readings in this period" : Double.isNaN(prev.avg) ? "Average core temperature" : "Average · " + delta(t.avg - prev.avg) + " vs previous period", 13, MUTED));
        TempChart chart = new TempChart(this);
        String fmt = period == Trend.Period.DAY ? "ha" : period == Trend.Period.WEEK ? "EEE" : "MMM d";
        chart.setData(t.buckets, t.start, t.end, fmt, period != Trend.Period.DAY, heaterOverlay, fahrenheit, p);
        if (period == Trend.Period.DAY || period == Trend.Period.WEEK) for (Night n : a.nights) if (n.valid() && n.wake > t.start && n.onset < t.end) chart.shade(n.onset, n.wake);
        c.addView(chart, new LinearLayout.LayoutParams(-1, dp(250)));
        stats(c, new String[][]{{"LOW", temp(t.low), t.lowTime > 0 ? Instant.ofEpochMilli(t.lowTime).atZone(zone()).format(DateTimeFormatter.ofPattern("EEE h:mm a", Locale.getDefault())) : null},
                {"HIGH", temp(t.high), t.highTime > 0 ? Instant.ofEpochMilli(t.highTime).atZone(zone()).format(DateTimeFormatter.ofPattern("EEE h:mm a", Locale.getDefault())) : null}});
        CheckBox overlay = new CheckBox(this); overlay.setText("Show heater power"); overlay.setTextColor(TEXT); overlay.setChecked(heaterOverlay); overlay.setMinHeight(dp(48));
        overlay.setButtonTintList(android.content.res.ColorStateList.valueOf(p.accent)); overlay.setOnCheckedChangeListener((v, on) -> { heaterOverlay = on; buildUi(); }); c.addView(overlay);
        c.addView(label((period == Trend.Period.DAY ? "Line: 15-minute average" : "Line: average · bars: low to high") + (period.ordinal() < 2 ? " · purple bands: in bed" : "") + (heaterOverlay ? " · blue: ZHF heater power" : ""), 11, MUTED));

        BodyClock b = a.clock;
        LinearLayout rhythm = card(root); rhythm.addView(heading("Daily rhythm", 20)); rhythm.addView(label("24 h cosine fit over your last 72 hours", 12, MUTED));
        stats(rhythm, new String[][]{{"RHYTHM-ADJUSTED MEAN", temp(b.mesor), "MESOR"}, {"DAILY SWING", size(2 * b.amplitude), "peak to trough"},
                {"DAILY HIGH AROUND", clockHours(b.peakHour), "acrophase"}, {"OVERNIGHT LOW AROUND", clockHours(b.nadirHour), "7-night median"}});

        // Environment from heater power: the heater replaces the heat the patch would lose to the air.
        LinearLayout env = card(root); env.addView(heading("Your environment", 20));
        env.addView(label("Estimated from ZHF heater power · no extra sensor", 12, MUTED));
        long dayStart = Instant.ofEpochMilli(a.now).atZone(zone()).toLocalDate().atStartOfDay(zone()).toInstant().toEpochMilli();
        double sum = 0; int cnt = 0, cold = 0, hot = 0;
        Night last = a.lastNight();
        for (int i = a.series.clamp(a.series.index(dayStart)); i < a.series.size(); i++) {
            if (Float.isNaN(a.series.core[i]) || Float.isNaN(a.series.heater[i])) continue;
            if (last != null && a.series.time(i) < last.wake) continue;
            double amb = MinuteSeries.ambient(a.series.core[i], a.series.heater[i], rOut); sum += amb; cnt++;
            if (amb < 10) cold++; if (amb > 30) hot++;
        }
        stats(env, new String[][]{{"AIR AROUND YOU TODAY", cnt == 0 ? "—" : temp(sum / cnt), "awake average"}, {"BED MICROCLIMATE", last == null ? "—" : temp(last.microclimate), "last night, under covers"},
                {"COLD EXPOSURE", cold + " min", "below " + round(10)}, {"HOT EXPOSURE", hot + " min", "above " + round(30)}});
        Button why = action("How the heater sees the room", false, R.drawable.ic_info);
        why.setOnClickListener(v -> info("Environment from heater power", "At zero heat flux the heater supplies exactly the heat the patch would otherwise lose to the air: power ≈ (core − air) ÷ R, where R is the outward thermal resistance of the patch (" + String.format(Locale.getDefault(), "%.0f", rOut) + " K/W, set in Settings).\n\nSo air ≈ core − power × R. Wind, clothing, and sweat change R, so treat this as an estimate. Under bedding it reads the warm pocket around you, not the room.\n\nIt also makes heater power an in-bed detector: bedding warms the air around the patch and the heater backs off sharply."));
        fullAction(env, why);
    }

    // ---------- Sleep ----------
    private void buildSleep(LinearLayout root) {
        Analysis a = analysis;
        List<Night> valid = new ArrayList<>();
        for (Night n : a.nights) if (n.valid()) valid.add(n);
        if (valid.isEmpty()) { LinearLayout c = card(root); c.addView(heading("No nights yet", 20)); c.addView(label("Wear CaeSAR overnight. Your first night appears the next morning.", 14, MUTED)); return; }
        nightOffset = Math.max(-(valid.size() - 1), Math.min(0, nightOffset));
        Night n = valid.get(valid.size() - 1 + nightOffset);
        LinearLayout c = card(root);
        LinearLayout nav = row(c); LinearLayout titles = column();
        titles.addView(heading(nightOffset == 0 ? "Last night" : day(n.date.minusDays(1)), 20)); titles.addView(label("Night of " + shortDay(n.date.minusDays(1)) + " – " + shortDay(n.date), 12, MUTED));
        nav.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        ImageButton back = iconButton(R.drawable.ic_left, "Previous night"); back.setEnabled(nightOffset > -(valid.size() - 1)); back.setAlpha(back.isEnabled() ? 1 : .35f);
        back.setOnClickListener(v -> { nightOffset--; buildUi(); }); nav.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        ImageButton next = iconButton(R.drawable.ic_right, "Next night"); next.setEnabled(nightOffset < 0); next.setAlpha(nightOffset < 0 ? 1 : .35f);
        next.setOnClickListener(v -> { if (nightOffset < 0) { nightOffset++; buildUi(); } });
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(dp(48), dp(48)); np.leftMargin = dp(8); nav.addView(next, np);
        c.addView(heading(duration(n.hours()), 36));
        c.addView(label("In bed " + clock(n.onset) + " – " + clock(n.wake) + (n.fever ? " · fever overnight" : ""), 13, n.fever ? p.alert : MUTED));
        long from = n.onset - 3 * 3_600_000L, to = n.wake + 2 * 3_600_000L;
        List<Trend.Bucket> buckets = new ArrayList<>();
        for (long t = from; t < to; t += 5 * MinuteSeries.MINUTE) {
            Trend.Bucket b = new Trend.Bucket(t, t + 5 * MinuteSeries.MINUTE);
            int i = a.series.index(t);
            double m = MinuteSeries.mean(a.series.core, i, i + 5), h = MinuteSeries.mean(a.series.heater, i, i + 5);
            if (Double.isFinite(m)) { b.avg = m; b.low = m; b.high = m; b.count = 1; }
            b.heater = h; buckets.add(b);
        }
        TempChart chart = new TempChart(this); chart.setData(buckets, from, to, "h a", false, true, fahrenheit, p); chart.shade(n.onset, n.wake);
        if (n.nadirTime > 0) chart.marker(n.nadirTime, n.nadir);
        c.addView(chart, new LinearLayout.LayoutParams(-1, dp(240)));
        c.addView(label("Orange: core · blue: heater power · purple: in bed · dot: overnight low", 11, MUTED));
        stats(c, new String[][]{
                {"TEMPERATURE DROP", size(n.drop()), "2 h before bed to low"}, {"OVERNIGHT LOW", temp(n.nadir), "at " + clock(n.nadirTime)},
                {"LOW TO WAKE", duration(n.nadirToWake()), "aim for 2 h or more"}, {"SLEEP CYCLES", n.cycles() > 0 ? String.valueOf(n.cycles()) : "—", Double.isFinite(n.ultradianMinutes) ? String.format(Locale.getDefault(), "%.0f min rhythm · FFT", n.ultradianMinutes) : "no clear rhythm"},
                {"BED MICROCLIMATE", temp(n.microclimate), "from heater power"}, {"NIGHT AVERAGE", temp(n.mean), "used by illness watch"}});
        LinearLayout tips = card(root); tips.addView(heading("What to try", 20));
        int count = 0;
        BodyClock b = a.clock;
        if (n.nadirToWake() < 1.5) { tip(tips, "You woke " + duration(n.nadirToWake()) + " after your temperature low. Waking near the low is linked to grogginess. Aim to wake after " + clockHours(b.nadirHour + 2) + ", or shift bedtime earlier."); count++; }
        if (n.drop() < 0.3) { tip(tips, "Your temperature fell only " + size(n.drop()) + ". A small drop can follow a warm room, late exercise, alcohol, or a late meal. A cooler bedroom or a warm shower 1–2 h before bed helps."); count++; }
        if (n.microclimate > 31.5) { tip(tips, "Your bed microclimate averaged " + temp(n.microclimate) + ". Lighter bedding or a cooler room may help your core cool."); count++; }
        if (Double.isFinite(b.socialJetLag) && b.socialJetLag >= 1) { tip(tips, "Weekend sleep runs " + duration(b.socialJetLag) + " later than weekdays. Keeping wake time within an hour every day steadies your body clock."); count++; }
        if (b.onsetSpread > 1) { tip(tips, "Bedtimes varied by ±" + duration(b.onsetSpread) + " this week. A regular bedtime strengthens the nightly temperature drop."); count++; }
        if (count == 0) tip(tips, "Steady night. Your temperature fell " + size(n.drop()) + " and bottomed out at " + clock(n.nadirTime) + ". Keep the same routine.");

        LinearLayout week = card(root); week.addView(heading("Last 14 nights", 20));
        int len = 14; double[] dur = new double[len], low = new double[len], drop = new double[len]; String[] labels = new String[len]; Arrays.fill(dur, Double.NaN); Arrays.fill(low, Double.NaN); Arrays.fill(drop, Double.NaN);
        int[] tones = new int[len];
        List<Night> all = a.nights;
        for (int i = Math.max(0, all.size() - len); i < all.size(); i++) {
            int k = len - (all.size() - i); Night x = all.get(i);
            if (x.valid()) { dur[k] = x.hours(); low[k] = x.clockHours(x.nadirTime, zone()); drop[k] = x.drop(); }
            if (x.weekendMorning()) tones[k] = p.sleep;
            labels[k] = k % 2 == 1 ? x.date.format(DateTimeFormatter.ofPattern("EEEEE", Locale.getDefault())) : null;
        }
        week.addView(label("Time in bed · purple: weekend mornings", 11, MUTED));
        BarChart d = new BarChart(this); d.set(dur, labels, BarChart.Mode.BARS, p).tones(tones).colors(p.accent, p.alert).axis(v -> String.format(Locale.getDefault(), "%.0fh", v)).range(0, 11);
        week.addView(d, new LinearLayout.LayoutParams(-1, dp(130)));
        week.addView(label("Overnight low time · your body clock", 11, MUTED));
        BarChart l = new BarChart(this); l.set(low, labels, BarChart.Mode.DOTS, p).tones(tones).axis(this::clockHours);
        week.addView(l, new LinearLayout.LayoutParams(-1, dp(130)));
        week.addView(label("Temperature drop", 11, MUTED));
        BarChart dr = new BarChart(this); dr.set(drop, labels, BarChart.Mode.BARS, p).tones(tones).threshold(0.3, "").axis(this::chartTempSize);
        week.addView(dr, new LinearLayout.LayoutParams(-1, dp(120)));
        Button how = action("How sleep is estimated", false, R.drawable.ic_info);
        how.setOnClickListener(v -> info("Sleep from temperature", "In bed: bedding warms the air around the patch, so the ZHF heater needs much less power. CaeSAR finds the longest low-power stretch between 6 PM and 2 PM (bridging breaks up to 25 min, at least 3 h). Without heater data it uses a core-temperature threshold.\n\nTemperature drop: the evening 2 h average minus the overnight low (30-minute smoothed). A larger drop usually goes with deeper, more consolidated sleep.\n\nSleep cycles: core temperature ripples with the 90-ish-minute NREM/REM cycle. CaeSAR detrends the night, runs an FFT (the same transform the Forge SoC runs in hardware), and finds the strongest period from 60 to 130 min. Experimental.\n\nThis is time in bed, not EEG-scored sleep."));
        fullAction(week, how);
    }
    private String chartTempSize(double d) { return fahrenheit ? String.format(Locale.getDefault(), "%.1f°", d * 1.8) : String.format(Locale.getDefault(), "%.1f°", d); }

    // ---------- Insights ----------
    private void buildInsights(LinearLayout root) {
        Analysis a = analysis;
        illnessCard(root, true);
        bodyClockCard(root, a);
        cycleCard(root, a);
        heatCard(root, a);
        LinearLayout how = card(root); how.addView(heading("Why core temperature", 20));
        how.addView(label("Wrist and finger skin temperature swing with the room and with blood flow. A ZHF patch reads deep-tissue temperature, so small shifts like a 0.3 °C luteal rise, a 0.5 °C pre-illness rise, or a 90-minute sleep ripple stand out from the noise.\n\n" + zhfExplainer(), 13, MUTED));
    }
    private void bodyClockCard(LinearLayout root, Analysis a) {
        BodyClock b = a.clock;
        LinearLayout c = card(root); c.addView(eyebrow("BODY CLOCK", p.sleep));
        c.addView(heading(Double.isFinite(b.peakHour) ? "High at " + clockHours(b.peakHour) + ", low at " + clockHours(b.nadirHour) : "Learning your rhythm", 20));
        c.addView(label("Your core peaks in the late afternoon and bottoms out before dawn. Both times move with your schedule, light, and travel.", 13, MUTED));
        if (Double.isFinite(b.nadirShift) && Math.abs(b.nadirShift) >= 0.5)
            tip(c, "Your overnight low moved " + duration(b.nadirShift) + (b.nadirShift > 0 ? " later" : " earlier") + " over the last two weeks. After travel, the body clock shifts about an hour a day; watch the low settle to see when you've adapted.");
        if (Double.isFinite(b.socialJetLag)) tip(c, String.format(Locale.getDefault(), "Social jet lag: %s between weekday and weekend sleep midpoints.%s", duration(b.socialJetLag), Math.abs(b.socialJetLag) >= 1 ? " That's like flying a time zone every weekend." : " Nicely consistent."));
        if (b.spectrumPower != null) {
            c.addView(label("Rhythm spectrum · 7 days · FFT", 11, MUTED));
            int n = b.spectrumPower.length; String[] l = new String[n];
            int[] marks = {48, 24, 12, 6, 3};
            for (int m : marks) { int best = -1; for (int i = 0; i < n; i++) if (best < 0 || Math.abs(b.spectrumPeriods[i] - m) < Math.abs(b.spectrumPeriods[best] - m)) best = i; if (best >= 0) l[best] = m + "h"; }
            BarChart s = new BarChart(this); s.set(b.spectrumPower, l, BarChart.Mode.AREA, p).colors(p.sleep, p.alert).axis(v -> String.format(Locale.getDefault(), "%.0f%%", v * 100)).range(0, 1.05);
            c.addView(s, new LinearLayout.LayoutParams(-1, dp(130)));
            c.addView(label(String.format(Locale.getDefault(), "The 24 h rhythm explains %.0f%% of your temperature variation this week. A sharp 24 h peak means a robust clock; shift work, travel, and illness flatten it.", 100 * b.rhythmShare), 13, TEXT));
        }
    }
    private void cycleCard(LinearLayout root, Analysis a) {
        LinearLayout c = card(root); c.addView(eyebrow("CYCLE INSIGHTS", p.sleep));
        if (!cycleActive()) {
            c.addView(heading("Off", 20));
            c.addView(label("After ovulation, core temperature rises about 0.3 °C and stays up until your period. Turn this on to confirm ovulation, predict your next period, and keep the illness watch from flagging that rise.", 13, MUTED));
            Button on = action("Turn on cycle insights", false, R.drawable.ic_cycle); on.setOnClickListener(v -> setCycle(true)); fullAction(c, on); return;
        }
        CycleTracker t = a.cycle;
        if (t == null) {
            c.addView(heading("Log your period to start", 20)); c.addView(label("Add the first day of your most recent period. CaeSAR finds the temperature shift from there.", 13, MUTED));
        } else {
            CycleTracker.Cycle cur = t.current();
            LocalDate today = Instant.ofEpochMilli(a.now).atZone(zone()).toLocalDate();
            c.addView(heading(cur == null ? "No recent period logged" : "Day " + t.cycleDay() + " · " + t.phase(today) + " phase", 22));
            if (t.predictedNext != null) {
                long days = java.time.temporal.ChronoUnit.DAYS.between(today, t.predictedNext);
                c.addView(label("Next period expected around " + day(t.predictedNext) + (days >= 0 ? " (in " + days + " days)" : ""), 14, TEXT));
            }
            CycleTracker.Cycle confirmed = null;
            for (CycleTracker.Cycle x : t.cycles) if (x.shift != null) confirmed = x;
            List<String[]> items = new ArrayList<>();
            items.add(new String[]{"LAST CONFIRMED OVULATION", confirmed == null ? "—" : shortDay(confirmed.ovulation()), confirmed == null ? "no shift yet" : "shift of " + size(shiftSize(a, confirmed))});
            items.add(new String[]{"LUTEAL PHASE", t.averageLuteal + " days", "average"});
            items.add(new String[]{"CYCLE LENGTH", t.averageLength + " days", "average"});
            items.add(new String[]{"TRACKED CYCLES", String.valueOf(t.cycles.size()), "from logged periods"});
            stats(c, items.toArray(new String[0][]));
            if (t.shortLuteal() != null) tip(c, "Your last luteal phase was " + t.shortLuteal().luteal() + " days. Under 10 days is worth mentioning to a clinician if it repeats.");
            if (t.noShift() != null) tip(c, "No temperature shift was found in the cycle starting " + shortDay(t.noShift().start) + ". Cycles without ovulation happen occasionally; a pattern is worth raising with a clinician.");
            LocalDate from = t.cycles.size() >= 2 ? t.cycles.get(t.cycles.size() - 2).start : t.cycles.get(0).start;
            int len = (int) Math.min(70, java.time.temporal.ChronoUnit.DAYS.between(from, today) + 1);
            double[] v = new double[len]; String[] l = new String[len]; int[] tones = new int[len]; Arrays.fill(v, Double.NaN);
            Map<LocalDate, Night> byDate = new HashMap<>(); for (Night n : a.nights) byDate.put(n.date, n);
            Set<LocalDate> starts = new HashSet<>(); for (CycleTracker.Cycle x : t.cycles) starts.add(x.start);
            for (int i = 0; i < len; i++) {
                LocalDate d = today.minusDays(len - 1 - i); Night n = byDate.get(d);
                if (n != null && n.valid() && !n.fever) v[i] = n.mean;
                tones[i] = "Luteal".equals(t.phase(d)) ? p.accent : p.sleep;
                if (starts.contains(d)) l[i] = shortDay(d);
            }
            c.addView(label("Nightly temperature · purple: follicular · orange: luteal", 11, MUTED));
            BarChart chart = new BarChart(this); chart.set(v, l, BarChart.Mode.DOTS, p).tones(tones).axis(this::chartTemp);
            if (confirmed != null) chart.threshold(confirmed.coverline, "coverline");
            c.addView(chart, new LinearLayout.LayoutParams(-1, dp(150)));
        }
        LinearLayout actions = row(c);
        Button log = action("Log period", false, 0); log.setOnClickListener(v -> logPeriod()); rowItem(actions, log, false);
        Button edit = action("Edit dates", false, 0); edit.setOnClickListener(v -> editPeriods()); rowItem(actions, edit, false);
        Button off = action("Turn off", false, 0); off.setOnClickListener(v -> setCycle(false)); rowItem(actions, off, true);
        c.addView(label("Confirms ovulation after the fact with the three-over-six rule. Not for contraception or diagnosis.", 11, MUTED));
    }
    private double shiftSize(Analysis a, CycleTracker.Cycle c) {
        double sum = 0; int n = 0;
        for (Night x : a.nights) if (x.valid() && !x.fever && !x.date.isBefore(c.shift) && x.date.isBefore(c.shift.plusDays(5))) { sum += x.mean; n++; }
        return n == 0 ? Double.NaN : sum / n - c.coverline;
    }
    private void heatCard(LinearLayout root, Analysis a) {
        LinearLayout c = card(root); c.addView(eyebrow("HEAT STRAIN", p.alert));
        List<HeatStrain.Episode> eps = a.episodes;
        double eta = demo ? Double.NaN : HeatStrain.minutesTo39(a.series);
        if (Double.isFinite(eta)) { chip(c, "CLIMBING", p.alert); c.addView(heading("39 °C in about " + Math.round(eta) + " min at this pace", 18)); }
        if (eps.isEmpty()) { c.addView(heading("No workouts detected yet", 20)); c.addView(label("A fast climb of 0.5 °C or more past 37.8 °C is tracked as a heat episode.", 13, MUTED)); return; }
        HeatStrain.Episode e = eps.get(eps.size() - 1);
        double s = e.strain();
        String level = s < 3 ? "Low" : s < 5 ? "Moderate" : s < 7 ? "High" : "Very high";
        c.addView(heading(level + " strain · " + String.format(Locale.getDefault(), "%.1f", s) + " of 10", 22));
        c.addView(label("Latest: " + Instant.ofEpochMilli(e.peakTime).atZone(zone()).format(DateTimeFormatter.ofPattern("EEE, MMM d · h:mm a", Locale.getDefault())), 13, MUTED));
        stats(c, new String[][]{{"PEAK CORE", temp(e.peak), "from " + temp(e.base)}, {"FASTEST CLIMB", fahrenheit ? String.format(Locale.getDefault(), "%.2f °F", e.maxRate * 1.8) : String.format(Locale.getDefault(), "%.2f °C", e.maxRate), "per 10 minutes"},
                {"ABOVE " + (fahrenheit ? "101.3 °F" : "38.5 °C"), e.minutesAbove385 + " min", e.minutesAbove39 > 0 ? e.minutesAbove39 + " min above 39 °C" : null}, {"COOL-DOWN", duration(e.recoveryMinutes() / 60), "back within 0.3 °C"}});
        int n = Math.min(14, eps.size()); double[] v = new double[n]; String[] l = new String[n]; boolean[] h = new boolean[n];
        for (int i = 0; i < n; i++) { HeatStrain.Episode x = eps.get(eps.size() - n + i); v[i] = x.strain(); h[i] = x.strain() >= 7; if ((n - 1 - i) % 3 == 0) l[i] = Instant.ofEpochMilli(x.peakTime).atZone(zone()).format(DateTimeFormatter.ofPattern("M/d", Locale.getDefault())); }
        c.addView(label("Strain per workout · last " + n, 11, MUTED));
        BarChart chart = new BarChart(this); chart.set(v, l, BarChart.Mode.BARS, p).colors(p.accent, p.alert).highlight(h).range(0, 10).threshold(7, "very high").axis(x -> String.format(Locale.getDefault(), "%.0f", x));
        c.addView(chart, new LinearLayout.LayoutParams(-1, dp(130)));
        double adapt = HeatStrain.adaptation(eps);
        if (Double.isFinite(adapt) && adapt <= -0.1) tip(c, "Your recent workouts raise core temperature " + size(adapt) + " less than the five before. That's a sign of heat adaptation.");
        else if (Double.isFinite(adapt) && adapt >= 0.1) tip(c, "Recent workouts raise core temperature " + size(adapt) + " more than before. Heat, dehydration, fatigue, or illness can do this; go easier and drink more.");
        if (s >= 7) tip(c, "Very high strain: slow down, get to shade or air conditioning, drink fluids, and cool your skin. Stop if you feel dizzy, confused, or nauseated.");
        else tip(c, "Above " + round(39.0) + ", exercise feels much harder and heat illness risk climbs. Pre-cool, hydrate, and pace in hot weather.");
        if (Double.isFinite(e.ambient)) tip(c, "Air around the patch averaged " + temp(e.ambient) + " during this workout (from heater power; airflow and sweat read colder).");
        Button how = action("How strain is scored", false, R.drawable.ic_info);
        how.setOnClickListener(v1 -> info("Heat strain", "Strain = 10 × (peak − starting core) ÷ (39.5 °C − starting core), capped 0 to 10. It is the temperature half of the Physiological Strain Index (Moran et al., 1998); the heart-rate half would need a pulse sensor.\n\nA workout is a climb of at least 0.5 °C, faster than 0.1 °C per 10 minutes, past 37.8 °C. Slower climbs are left to the illness watch.\n\nCool-down: minutes from peak until core is back within 0.3 °C of where it started. Faster cool-downs track fitness and heat adaptation.\n\nNot a medical device. Heat illness can occur below these numbers."));
        fullAction(c, how);
    }

    // ---------- Device ----------
    private void buildDevice(LinearLayout root) {
        LinearLayout c = card(root); c.addView(heading("Your CaeSAR wearable", 23));
        status = label(connectionMessage, 14, TEXT); c.addView(status);
        c.addView(label("Connect the wearable with a USB data cable. It streams 1 Hz readings and can upload its overnight buffer.", 13, MUTED));
        LinearLayout actions = row(c);
        connectButton = action(activeDevice == null ? "Connect" : "Disconnect", true, 0); connectButton.setOnClickListener(v -> { if (activeDevice != null) disconnect(); else chooseDevice(); });
        demoButton = action(demo ? "Stop demo" : "Try demo", false, 0); demoButton.setOnClickListener(v -> toggleDemo());
        rowItem(actions, connectButton, false); rowItem(actions, demoButton, true);
        syncButton = action("Import overnight data", false, R.drawable.ic_sync); syncButton.setOnClickListener(v -> requestSync()); fullAction(c, syncButton);
        syncStatus = label(syncMessage, 12, MUTED); c.addView(syncStatus);
        Button dev = action(devMode ? "Product mode" : "Dev mode", false, R.drawable.ic_thermo); dev.setOnClickListener(v -> { devMode = !devMode; buildUi(); }); fullAction(c, dev);

        LinearLayout loop = card(root); loop.addView(heading("Live ZHF loop", 20)); loop.addView(label("Up to the last 10 minutes · 1 Hz", 12, MUTED));
        LinearLayout r1 = row(loop); liveCore = reading(r1, "CORE", p.accent, true); liveHeater = reading(r1, "HEATER", p.heater, false);
        LinearLayout r2 = row(loop); liveTop = reading(r2, "TOP SENSOR", MUTED, true); liveLock = reading(r2, "FLUX", p.good, false);
        liveChart = new TempChart(this); liveChart.empty("Waiting for live readings", "Connect your wearable or try the demo");
        loop.addView(liveChart, new LinearLayout.LayoutParams(-1, dp(200)));
        rateText = label("", 12, MUTED); loop.addView(rateText);
        if (devMode) {
            LinearLayout debug = card(root); debug.addView(heading("Developer diagnostics", 20));
            debugText = label("", 11, MUTED); debugText.setTypeface(Typeface.MONOSPACE); debugText.setTextIsSelectable(true); debug.addView(debugText);
            Button clear = action("Clear live session", false, 0); clear.setOnClickListener(v -> { live.clear(); rawLog.clear(); accepted = rejected = history = 0; render(); }); fullAction(debug, clear);
            debug.addView(label("Firmware line format:\n{\"core\":36.94,\"top\":36.91,\"heater_mw\":182.5,\"lock\":true,\"t_ms\":123456}\nBuffered history adds \"epoch\" (Unix seconds).", 11, MUTED));
        }
    }
    private TextView reading(LinearLayout parent, String name, int color, boolean first) {
        LinearLayout panel = column(); panel.addView(label(name, 11, color)); TextView value = heading("—", 22); panel.addView(value);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1); if (first) lp.rightMargin = dp(12); parent.addView(panel, lp); return value;
    }

    // ---------- settings ----------
    private void showSettings() {
        dialog("Settings").setItems(new String[]{"Appearance", "Temperature units", cycleActive() ? "Turn off cycle insights" : "Turn on cycle insights", "Log period start", "Edit period dates", "Heater calibration", "About CaeSAR", "Delete saved data"}, (d, which) -> {
            if (which == 0) dialog("Appearance").setSingleChoiceItems(new String[]{"Dark mode", "Light mode"}, dark ? 0 : 1, (dd, w) -> { dark = w == 0; getPreferences(MODE_PRIVATE).edit().putBoolean("dark", dark).apply(); applyColors(); buildUi(); dd.dismiss(); }).setNegativeButton("Close", null).show();
            else if (which == 1) dialog("Temperature units").setSingleChoiceItems(new String[]{"Celsius (°C)", "Fahrenheit (°F)"}, fahrenheit ? 1 : 0, (dd, w) -> { fahrenheit = w == 1; getPreferences(MODE_PRIVATE).edit().putBoolean("fahrenheit", fahrenheit).apply(); buildUi(); dd.dismiss(); }).setNegativeButton("Close", null).show();
            else if (which == 2) setCycle(!cycleActive());
            else if (which == 3) logPeriod();
            else if (which == 4) editPeriods();
            else if (which == 5) calibrate();
            else if (which == 6) about("CaeSAR is the wearable from Forge Silicon Design, Northeastern University's chip design organization. Its mixed-signal SoC pairs an on-die and a board temperature sensor through a 2:1 mux into a SAR ADC, with a pipelined FFT, a RISC-V core, and a zero-heat-flux heater.\n\nThis app keeps everything on your phone. No account, no Internet permission, no cloud backup. Insights are experimental wellness information, not medical advice.");
            else dialog("Delete saved data?").setMessage("This deletes recorded temperature history and logged periods from this phone. Settings stay.").setNegativeButton("Keep data", null)
                    .setPositiveButton("Delete", (dd, w) -> storage.execute(() -> { store.clear(); main.post(() -> { if (!destroyed) { realStarts = new ArrayList<>(); requestAnalysis(); } }); })).show();
        }).setNegativeButton("Close", null).show();
    }
    /** The demo keeps its own cycle setting so trying it never changes the real one. */
    private boolean cycleActive() { return demo ? demoCycle : cycleOn; }
    private void setCycle(boolean on) {
        if (demo) demoCycle = on;
        else { cycleOn = on; getPreferences(MODE_PRIVATE).edit().putBoolean("cycle", on).apply(); }
        Toast.makeText(this, on ? "Cycle insights on" : "Cycle insights off", Toast.LENGTH_SHORT).show();
        requestAnalysis();
    }
    private List<LocalDate> periodStarts() { List<LocalDate> list = new ArrayList<>(new TreeSet<>(demo ? demoStarts : realStarts)); return list; }
    private void logPeriod() {
        LocalDate today = LocalDate.now(zone());
        DatePickerDialog picker = new DatePickerDialog(this, dark ? R.style.DateDialog : R.style.DateDialog_Light, (v, y, m, d) -> checkPeriod(LocalDate.of(y, m + 1, d)),
                today.getYear(), today.getMonthValue() - 1, today.getDayOfMonth());
        picker.getDatePicker().setMaxDate(System.currentTimeMillis());
        picker.setTitle(demo ? "First day of period (demo)" : "First day of period"); picker.show();
    }
    /** A start within two weeks of another is usually a mistap or spotting, so ask before splitting the cycle. */
    private void checkPeriod(LocalDate day) {
        LocalDate near = null;
        for (LocalDate s : periodStarts()) if (Math.abs(java.time.temporal.ChronoUnit.DAYS.between(s, day)) < 14) near = s;
        if (near == null) { savePeriod(day, null); return; }
        if (near.equals(day)) { Toast.makeText(this, shortDay(day) + " is already logged", Toast.LENGTH_SHORT).show(); return; }
        final LocalDate other = near;
        long gap = Math.abs(java.time.temporal.ChronoUnit.DAYS.between(other, day));
        dialog("Period logged " + gap + " days " + (other.isBefore(day) ? "earlier" : "later")).setMessage("You already logged " + day(other) + ". Two starts " + gap + " days apart make a " + gap + "-day cycle. Replace that date, or keep both?")
                .setPositiveButton("Replace", (d, w) -> savePeriod(day, other)).setNeutralButton("Keep both", (d, w) -> savePeriod(day, null)).setNegativeButton("Cancel", null).show();
    }
    private void savePeriod(LocalDate day, LocalDate replace) {
        boolean enable = !cycleActive();
        if (enable) { if (demo) demoCycle = true; else { cycleOn = true; getPreferences(MODE_PRIVATE).edit().putBoolean("cycle", true).apply(); } }
        String note = "Period start saved: " + day(day) + (enable ? " · cycle insights on" : "");
        if (demo) { if (replace != null) demoStarts.remove(replace); demoStarts.add(day); requestAnalysis(); Toast.makeText(this, note, Toast.LENGTH_SHORT).show(); }
        else storage.execute(() -> {
            if (replace != null) store.removePeriod(replace);
            store.logPeriod(day);
            main.post(() -> { if (!destroyed) { requestAnalysis(); Toast.makeText(this, note, Toast.LENGTH_SHORT).show(); } });
        });
    }
    private void editPeriods() {
        List<LocalDate> starts = periodStarts(); Collections.reverse(starts);
        if (starts.isEmpty()) { dialog("No period dates").setMessage("Log the first day of a period to start cycle insights.").setPositiveButton("Log period start", (d, w) -> logPeriod()).setNegativeButton("Close", null).show(); return; }
        String[] names = new String[starts.size()];
        for (int i = 0; i < names.length; i++) names[i] = day(starts.get(i)) + (i + 1 < names.length ? " · " + java.time.temporal.ChronoUnit.DAYS.between(starts.get(i + 1), starts.get(i)) + "-day cycle before" : "");
        dialog(demo ? "Period dates (demo)" : "Period dates").setItems(names, (d, which) -> {
            LocalDate day = starts.get(which);
            dialog("Remove " + day(day) + "?").setMessage("Cycles are recalculated without this date.").setNegativeButton("Keep", null).setPositiveButton("Remove", (dd, w) -> {
                if (demo) { demoStarts.remove(day); requestAnalysis(); }
                else storage.execute(() -> { store.removePeriod(day); main.post(() -> { if (!destroyed) requestAnalysis(); }); });
            }).show();
        }).setPositiveButton("Add date", (d, w) -> logPeriod()).setNegativeButton("Close", null).show();
    }
    private void calibrate() {
        LinearLayout form = column(); form.setPadding(dp(24), dp(8), dp(24), dp(8));
        form.addView(label("Outward thermal resistance of the patch, used to estimate air temperature from heater power. Default 80 K/W from the Forge thermal model.", 13, MUTED));
        EditText e = new EditText(this); e.setTextColor(TEXT); e.setText(String.format(Locale.US, "%.0f", rOut)); e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setContentDescription("Thermal resistance in kelvin per watt"); form.addView(e, new LinearLayout.LayoutParams(-1, dp(48)));
        TextView error = label("", 12, p.alert); form.addView(error);
        AlertDialog d = dialog("Heater calibration").setView(form).setPositiveButton("Save", null).setNegativeButton("Cancel", null).create(); d.show();
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try { double r = Double.parseDouble(e.getText().toString()); if (r < 10 || r > 400) throw new IllegalArgumentException();
                rOut = r; getPreferences(MODE_PRIVATE).edit().putFloat("rOut", (float) r).apply(); d.dismiss(); requestAnalysis();
            } catch (Exception bad) { error.setText("Enter 10 to 400 K/W."); }
        });
    }

    // ---------- analysis ----------
    private void requestAnalysis() {
        if (destroyed || (demo && demoData == null)) return;   // demo data is still generating
        final int request = ++analysisRequest;
        final boolean preview = demo; final boolean cycle = cycleActive(); final double r = rOut; final ZoneId z = zone();
        final List<LocalDate> starts = new ArrayList<>(demoStarts);
        final DemoData data = demoData;
        lastAnalysis = SystemClock.elapsedRealtime(); dirty = false;
        storage.execute(() -> {
            try {
                Analysis result;
                if (preview) result = new Analysis(data.series, z, data.series.time(data.series.latest()), r, cycle, starts);
                else {
                    long now = System.currentTimeMillis();
                    List<LocalDate> periods = store.periodStarts();
                    MinuteSeries s = store.load(now - 100L * 86_400_000L, now);
                    result = new Analysis(s, z, now, r, cycle, periods);
                    main.post(() -> realStarts = periods);
                }
                main.post(() -> { if (!destroyed && request == analysisRequest) { analysis = result; buildUi(); } });
            } catch (Exception error) {
                main.post(() -> { if (!destroyed && request == analysisRequest) { analysis = new Analysis(new MinuteSeries(System.currentTimeMillis(), 1), z, System.currentTimeMillis(), r, false, new ArrayList<>()); buildUi(); Toast.makeText(this, "Could not read saved history", Toast.LENGTH_LONG).show(); } });
            }
        });
    }
    /** For tests: true once an analysis matching the current mode has been delivered. */
    boolean analysisReady() { boolean idle = storageIdle(); return idle && analysis != null; }
    private boolean storageIdle() {
        java.util.concurrent.Future<?> f = storage.submit(() -> {});
        try { f.get(5, java.util.concurrent.TimeUnit.SECONDS); return true; } catch (Exception e) { return false; }
    }

    // ---------- live data ----------
    private void add(ThermalSample s) {
        accepted++;
        if (s.epochMillis > 0) {
            history++;
            if (s.locked && !demo) record(s, s.epochMillis);
            if (syncing && syncSent) { syncing = false; setSync("Importing overnight buffer…"); }
            return;
        }
        live.addLast(s); lastSampleNanos = s.receivedNanos;
        while (live.size() > 600) live.removeFirst();
        if (syncing && syncSent && s.receivedNanos > syncRequestedNanos) { syncing = false; setSync("Live readings received · no buffered history in reply"); }
        if (s.locked && !demo) record(s, System.currentTimeMillis());
    }
    private void record(ThermalSample s, long wall) {
        long minute = Math.floorDiv(wall, MinuteSeries.MINUTE) * MinuteSeries.MINUTE;
        ThermalStore.Sums sums = pending.get(minute);
        if (sums == null) { sums = new ThermalStore.Sums(minute); pending.put(minute, sums); }
        sums.add(s); dirty = true;
    }
    private void flush(boolean all) {
        if (pending.isEmpty()) return;
        long current = Math.floorDiv(System.currentTimeMillis(), MinuteSeries.MINUTE) * MinuteSeries.MINUTE;
        List<ThermalStore.Sums> done = new ArrayList<>();
        for (Iterator<ThermalStore.Sums> it = pending.values().iterator(); it.hasNext(); ) { ThermalStore.Sums m = it.next(); if (all || m.minute < current) { done.add(m); it.remove(); } }
        if (!done.isEmpty()) storage.execute(() -> { try { store.record(done); } catch (Exception e) { main.post(() -> setSync("Could not save readings")); } });
    }
    private void render() {
        long now = SystemClock.elapsedRealtimeNanos();
        ThermalSample latest = live.peekLast();
        boolean fresh = latest != null && now - lastSampleNanos < 5_000_000_000L;
        if (SystemClock.elapsedRealtime() - lastFlush > 2000) { lastFlush = SystemClock.elapsedRealtime(); flush(false); }
        if (!demo && dirty && SystemClock.elapsedRealtime() - lastAnalysis > 60_000) { flush(true); requestAnalysis(); }
        if (syncing && now - syncRequestedNanos > 5_000_000_000L) { syncing = false; setSync("No reply · check the firmware sync handler"); }
        if (heroValue != null) {
            double v = fresh ? latest.core : analysis == null ? Double.NaN : analysis.latestCore();
            String text = temp(v); android.text.SpannableString styled = new android.text.SpannableString(text);
            int unit = text.lastIndexOf(' ');
            if (unit > 0) styled.setSpan(new android.text.style.RelativeSizeSpan(.4f), unit, text.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            heroValue.setText(styled);
            heroCaption.setText(demo ? "Simulated reading · not a measurement" : fresh ? (latest.locked ? "Live · ZHF loop locked" : "Live · ZHF loop settling, readings not saved yet")
                    : analysis != null && analysis.latestTime() > 0 ? "Latest saved reading · " + Instant.ofEpochMilli(analysis.latestTime()).atZone(zone()).format(DateTimeFormatter.ofPattern("EEE h:mm a", Locale.getDefault())) : "No readings yet");
        }
        if (syncButton != null) { syncButton.setEnabled(serial != null && !syncing); syncButton.setText(syncing ? "Requesting…" : "Import overnight data"); }
        if (serial != null) setStatus(fresh ? "Connected · receiving live readings" : "Wearable connected · waiting for readings");
        if (liveCore != null) {
            liveCore.setText(fresh ? temp(latest.core) : "—");
            liveTop.setText(fresh && Double.isFinite(latest.top) ? temp(latest.top) : "—");
            liveHeater.setText(fresh && Double.isFinite(latest.heaterMw) ? String.format(Locale.getDefault(), "%.0f mW", latest.heaterMw) : "—");
            liveLock.setText(!fresh ? "—" : latest.locked ? "Nulled" : "Settling");
            List<Trend.Bucket> buckets = new ArrayList<>();
            long wall = System.currentTimeMillis();
            for (ThermalSample s : live) {
                long t = wall - (now - s.receivedNanos) / 1_000_000;
                Trend.Bucket b = new Trend.Bucket(t, t); b.avg = b.low = b.high = s.core; b.count = 1; b.heater = s.heaterMw; buckets.add(b);
            }
            liveChart.setData(buckets, wall - 600_000, wall, "h:mm", false, true, fahrenheit, p);
            int recent = 0; for (ThermalSample s : live) if (now - s.receivedNanos < 5_000_000_000L) recent++;
            rateText.setText(String.format(Locale.getDefault(), "%s · %.1f readings/s", demo ? "SIMULATED DATA" : fresh ? "Live readings" : "No live readings", recent / 5.0));
        }
        if (debugText != null) {
            StringBuilder t = new StringBuilder(source).append("\nCDC: 115200 / 8N1 · DTR + RTS\nValid: ").append(accepted).append("  Rejected: ").append(rejected)
                    .append("  Buffered history: ").append(history).append("\nPending minutes: ").append(pending.size()).append("\nR_out: ").append(String.format(Locale.US, "%.0f K/W", rOut));
            if (lastSampleNanos > 0) t.append("\nLast live sample: ").append((now - lastSampleNanos) / 1_000_000).append(" ms ago");
            if (latest != null && latest.sensorMillis >= 0) t.append("\nFirmware t_ms: ").append(latest.sensorMillis);
            t.append("\n\nRecent input (last 8 lines):\n"); for (String line : rawLog) t.append(line).append('\n');
            debugText.setText(t);
        }
    }
    private void setStatus(String text) { connectionMessage = text; if (status != null) status.setText(text); }
    private void setSync(String text) { syncMessage = text; if (syncStatus != null) syncStatus.setText(text); }

    // ---------- USB ----------
    private void chooseDevice() {
        if (demo) toggleDemo();
        List<UsbDevice> devices = new ArrayList<>();
        if (usb != null) for (UsbDevice device : usb.getDeviceList().values()) if (UsbSerial.isSupported(device)) devices.add(device);
        if (devices.isEmpty()) { setStatus("No wearable found · check your USB data cable and phone USB support"); return; }
        if (devices.size() == 1) request(devices.get(0));
        else {
            String[] names = new String[devices.size()];
            for (int i = 0; i < names.length; i++) names[i] = deviceName(devices.get(i));
            dialog("Choose your wearable").setItems(names, (d, which) -> request(devices.get(which))).show();
        }
    }
    private String deviceName(UsbDevice device) { return String.format(Locale.US, "CaeSAR #%d · %04X:%04X", device.getDeviceId(), device.getVendorId(), device.getProductId()); }
    private void request(UsbDevice device) {
        disconnect(); live.clear(); activeDevice = device; if (connectButton != null) connectButton.setText("Disconnect");
        if (usb.hasPermission(device)) open(device);
        else {
            setStatus("Allow USB access in the system prompt");
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            usb.requestPermission(device, PendingIntent.getBroadcast(this, device.getDeviceId(), new Intent(permissionAction).setPackage(getPackageName()), flags));
        }
    }
    private void open(UsbDevice device) {
        final int session = ++generation;
        try {
            serial = new UsbSerial();
            serial.open(usb, device, new UsbSerial.Listener() {
                @Override public void onLines(List<String> lines) {
                    final long arrival = SystemClock.elapsedRealtimeNanos();
                    main.post(() -> {
                        if (session != generation) return;
                        for (String line : lines) {
                            rawLog.addLast(line.length() > 200 ? line.substring(0, 200) + "…" : line);
                            while (rawLog.size() > 8) rawLog.removeFirst();
                            ThermalSample s = ThermalSample.parse(line, arrival);
                            if (s == null) rejected++; else add(s);
                        }
                    });
                }
                @Override public void onError(String message) { main.post(() -> { if (session == generation) { disconnect(); setStatus(message); } }); }
            });
            source = deviceName(device); setStatus("Wearable connected · waiting for readings");
            if (connectButton != null) connectButton.setText("Disconnect");
        } catch (Exception e) { disconnect(); setStatus("Connection failed: " + e.getMessage()); }
    }
    private void requestSync() {
        if (serial == null || syncing) return;
        final UsbSerial port = serial; final int session = generation;
        syncing = true; syncSent = false; syncRequestedNanos = SystemClock.elapsedRealtimeNanos(); setSync("Asking the wearable for its overnight buffer…");
        new Thread(() -> {
            try {
                port.requestSync();
                final long sentAt = SystemClock.elapsedRealtimeNanos();
                main.post(() -> {
                    if (session != generation) return;
                    syncSent = true; syncRequestedNanos = sentAt;
                    rawLog.addLast("TX {\"cmd\":\"sync\"}"); while (rawLog.size() > 8) rawLog.removeFirst();
                    if (syncing) setSync("Request sent · waiting for buffered readings");
                    main.postDelayed(() -> { if (session == generation && history > 0) { flush(true); requestAnalysis(); setSync("Imported " + history + " buffered readings"); } }, 6000);
                });
            } catch (Exception error) { main.post(() -> { if (session == generation) { syncing = false; setSync("Import failed: " + error.getMessage()); } }); }
        }, "ceasar-sync").start();
    }
    private void disconnect() {
        generation++; syncing = false;
        if (serial != null) { serial.close(); serial = null; flush(true); }
        activeDevice = null; source = "No wearable connected";
        if (connectButton != null) { connectButton.setText("Connect"); setStatus("Disconnected"); }
        setSync("Connect your wearable to import its overnight buffer");
    }
    private void toggleDemo() {
        main.removeCallbacks(simulate);
        if (demo) {
            demo = false; demoData = null; live.clear(); source = "No wearable connected"; setStatus("Demo stopped");
            analysis = null; page = page == 4 ? 4 : 0; buildUi(); requestAnalysis(); return;
        }
        disconnect(); live.clear();
        demo = true; demoCycle = true; source = "Synthetic ZHF data · no USB"; demoStartNanos = SystemClock.elapsedRealtimeNanos();
        setStatus("Demo preview · simulated readings");
        analysis = null; ++analysisRequest; buildUi();
        // Nine weeks of synthetic minutes is too much work for the UI thread on slower phones.
        final ZoneId z = zone(); final long started = demoStartNanos;
        storage.execute(() -> {
            DemoData data = new DemoData(System.currentTimeMillis(), z);
            main.post(() -> {
                if (destroyed || !demo || demoStartNanos != started) return;
                demoData = data; demoStarts.clear(); demoStarts.addAll(data.periodStarts);
                long nowNanos = SystemClock.elapsedRealtimeNanos(), wall = System.currentTimeMillis();
                for (int s = 600; s > 0; s--) live.addLast(data.live(wall - s * 1000L, nowNanos - s * 1_000_000_000L, -1));
                requestAnalysis(); main.post(simulate);
            });
        });
    }

    @Override protected void onStart() { super.onStart(); main.removeCallbacks(refresh); main.post(refresh); if (demo) { main.removeCallbacks(simulate); main.post(simulate); } }
    @Override protected void onStop() { main.removeCallbacks(refresh); main.removeCallbacks(simulate); flush(true); super.onStop(); }
    @Override protected void onSaveInstanceState(Bundle state) { state.putInt("page", page); state.putBoolean("dev", devMode); super.onSaveInstanceState(state); }
    @Override protected void onDestroy() {
        destroyed = true; disconnect(); main.removeCallbacksAndMessages(null);
        storage.execute(() -> store.close()); storage.shutdown();
        if (receiverRegistered) unregisterReceiver(receiver);
        super.onDestroy();
    }
}
