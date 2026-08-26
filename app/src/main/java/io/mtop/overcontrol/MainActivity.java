package io.mtop.overcontrol;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Setup/status screen: no root, no network calls. Walks the user through granting the two
 * permissions the overlay needs (accessibility access to cn.damai's on-screen text, and
 * draw-overlay), shows live status for each, and offers a clean way to shut everything
 * down again.
 *
 * <p>Built in code rather than XML to match the rest of this app, which ships no layout
 * resources. The palette is shared with the overlay pill: {@link #INK} on {@link #CANVAS},
 * {@link #TEAL} for anything actionable.
 */
public class MainActivity extends Activity {

    private static final int CANVAS = 0xFFF2F5F6;  // page background
    private static final int SURFACE = 0xFFFFFFFF; // card background
    private static final int INK = 0xFF10231F;     // primary text
    private static final int INK_SOFT = 0xFF5C6F6B; // secondary text
    private static final int TEAL = 0xFF0B6B6B;    // brand / primary action
    private static final int MINT = 0xFF3DD6C0;    // accent, matches the pill's stroke
    private static final int OK = 0xFF1E8E5A;      // granted
    private static final int WARN = 0xFFC2410C;    // not granted
    private static final int HAIRLINE = 0xFFE2E8E7;

    private TextView a11yState;
    private TextView overlayState;
    private TextView readyBanner;
    private TextView pillState;
    private TextView pillToggle;
    private LinearLayout crashCard;
    private TextView crashText;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        CrashLog.install(this);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(36), dp(20), dp(32));

        page.addView(header());
        page.addView(permissionsCard());
        page.addView(howItWorksCard());
        page.addView(shutdownCard());
        page.addView(crashCard());

        ScrollView scroller = new ScrollView(this);
        scroller.setBackgroundColor(CANVAS);
        scroller.setFillViewport(true);
        scroller.addView(page);
        setContentView(scroller);
    }

    // ---------------------------------------------------------------- sections

    private View header() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(4), 0, dp(4), dp(20));

        TextView title = new TextView(this);
        title.setText("大麦倒计时");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(INK);

        TextView subtitle = new TextView(this);
        subtitle.setText("Overcontrol · 开票倒计时悬浮窗");
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        subtitle.setTextColor(INK_SOFT);
        subtitle.setPadding(0, dp(4), 0, 0);

        box.addView(title);
        box.addView(subtitle);
        return box;
    }

    private View permissionsCard() {
        LinearLayout card = card();
        card.addView(cardTitle("权限 Permissions"));

        a11yState = new TextView(this);
        overlayState = new TextView(this);

        card.addView(permissionRow(
                "读屏权限 Accessibility",
                "读取大麦界面上的开抢时间，并代为点击购票按钮",
                a11yState,
                "开启 Grant",
                v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
        card.addView(divider());
        card.addView(permissionRow(
                "悬浮窗权限 Overlay",
                "在大麦之上显示倒计时",
                overlayState,
                "开启 Grant",
                v -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())))));

        readyBanner = new TextView(this);
        readyBanner.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        readyBanner.setPadding(dp(12), dp(10), dp(12), dp(10));
        readyBanner.setBackground(rounded(0xFFEAF7F2, dp(10)));
        LinearLayout.LayoutParams bannerLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bannerLp.topMargin = dp(14);
        readyBanner.setLayoutParams(bannerLp);
        card.addView(readyBanner);
        return card;
    }

    private View howItWorksCard() {
        LinearLayout card = card();
        card.addView(cardTitle("使用方法 How it works"));
        card.addView(bullet("轻触", "展开/收起演出名称  Tap to expand the show's name"));
        card.addView(bullet("拖动", "移动悬浮窗位置  Drag to move the pill"));
        card.addView(bullet("长按", "立即点击当前页面的购票按钮  Long-press to press the buy button now"));
        card.addView(bullet("🟢 已预约", "自动跟踪标有“已预约”的演出并显示其开票倒计时"
                + "  Tracks the show marked 已预约 and counts down to its on-sale time"));
        card.addView(bullet("倒计时归零", "自动点击该演出的“立即预订”"
                + "  Presses 立即预订 for that show the moment the countdown hits zero"));
        return card;
    }

    private View shutdownCard() {
        LinearLayout card = card();
        card.addView(cardTitle("控制 Controls"));

        pillState = new TextView(this);
        pillToggle = (TextView) button("", false, v -> togglePill());
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = new TextView(this);
        label.setText("悬浮窗 Floating pill");
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setTextColor(INK);
        label.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        pillState.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        pillState.setPadding(dp(10), dp(4), dp(10), dp(4));
        top.addView(label);
        top.addView(pillState);

        ((LinearLayout.LayoutParams) pillToggle.getLayoutParams()).topMargin = dp(10);
        row.addView(top);
        row.addView(pillToggle);
        card.addView(row);

        card.addView(divider());

        TextView note = new TextView(this);
        note.setText("退出会关闭悬浮窗并停止后台读屏。\nExiting closes the pill and stops screen reading.");
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        note.setTextColor(INK_SOFT);
        note.setPadding(0, 0, 0, dp(14));
        card.addView(note);

        card.addView(button("退出 Exit", true, v -> confirmExit()));
        return card;
    }

    private void togglePill() {
        if (OverlayService.isRunning()) {
            stopOverlay();
        } else if (Settings.canDrawOverlays(this)) {
            startOverlay();
        } else {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        // The service tears down / spins up asynchronously; re-read it a beat later.
        pillToggle.postDelayed(this::refresh, 250);
    }

    private void startOverlay() {
        Intent intent = new Intent(this, OverlayService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private View crashCard() {
        crashCard = card();
        crashCard.addView(cardTitle("⚠ 上次崩溃 Last crash"));

        crashText = new TextView(this);
        crashText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        crashText.setTypeface(Typeface.MONOSPACE);
        crashText.setTextColor(INK);
        crashText.setTextIsSelectable(true); // so the trace can be copied, not just read
        crashText.setPadding(dp(10), dp(10), dp(10), dp(10));
        crashText.setBackground(rounded(0xFFFDF3F0, dp(8)));
        crashCard.addView(crashText);

        View clear = button("清除 Clear", false, v -> {
            CrashLog.clear(this);
            refresh();
        });
        ((LinearLayout.LayoutParams) clear.getLayoutParams()).topMargin = dp(12);
        crashCard.addView(clear);
        return crashCard;
    }

    // ---------------------------------------------------------------- state

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        boolean a11yOk = isAccessibilityServiceEnabled();
        boolean overlayOk = Settings.canDrawOverlays(this);

        setChip(a11yState, a11yOk);
        setChip(overlayState, overlayOk);

        if (a11yOk && overlayOk) {
            readyBanner.setTextColor(OK);
            readyBanner.setBackground(rounded(0xFFEAF7F2, dp(10)));
            readyBanner.setText("就绪 — 打开大麦，浏览含开抢时间的页面即可。\n"
                    + "Ready — open Damai and browse to a page showing an on-sale time.");
        } else {
            readyBanner.setTextColor(WARN);
            readyBanner.setBackground(rounded(0xFFFDF3F0, dp(10)));
            readyBanner.setText("请先完成上面两项授权。\nGrant both permissions above first.");
        }

        boolean pillUp = OverlayService.isRunning();
        setChip(pillState, pillUp);
        pillState.setText(pillUp ? "显示中 ON" : "已隐藏 OFF");
        pillToggle.setText(pillUp ? "隐藏悬浮窗 Hide pill" : "显示悬浮窗 Show pill");

        String crash = CrashLog.read(this);
        if (crash == null) {
            crashCard.setVisibility(View.GONE);
        } else {
            crashCard.setVisibility(View.VISIBLE);
            crashText.setText(crash);
        }
    }

    private void setChip(TextView chip, boolean granted) {
        chip.setText(granted ? "已开启 ON" : "未开启 OFF");
        chip.setTextColor(granted ? OK : WARN);
        chip.setBackground(rounded(granted ? 0xFFE8F5EE : 0xFFFDECE5, dp(999)));
    }

    // ---------------------------------------------------------------- exit

    /**
     * Exit means exit: the overlay goes away and the accessibility service is turned off
     * too, so nothing keeps reading the screen afterwards. Offered as a separate, quieter
     * option is hiding just the overlay, for when the countdown is done with but the
     * permission should stay granted (re-granting it needs a trip to system settings).
     */
    private void confirmExit() {
        new AlertDialog.Builder(this)
                .setTitle("退出 Overcontrol?")
                .setMessage("将关闭悬浮窗并停止后台读屏。再次使用需要重新开启读屏权限。\n\n"
                        + "Closes the pill and stops screen reading. Using it again means "
                        + "re-granting accessibility access in system settings.")
                .setPositiveButton("退出 Exit", (d, w) -> exitCompletely())
                .setNeutralButton("仅关闭悬浮窗 Hide pill", (d, w) -> hideOverlayOnly())
                .setNegativeButton("取消 Cancel", null)
                .show();
    }

    private void exitCompletely() {
        stopOverlay();
        CountdownAccessibilityService.disableService();
        finishAndRemoveTask(); // API 21; leaves nothing behind in recents
    }

    private void hideOverlayOnly() {
        stopOverlay();
        pillToggle.postDelayed(this::refresh, 250);
    }

    private void stopOverlay() {
        startService(new Intent(this, OverlayService.class).setAction(OverlayService.ACTION_STOP));
    }

    private boolean isAccessibilityServiceEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) return false;
        String me = getPackageName() + "/" + CountdownAccessibilityService.class.getName();
        for (String s : enabled.split(":")) {
            if (s.equalsIgnoreCase(me)) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- widgets

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(18));
        card.setBackground(rounded(SURFACE, dp(16)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(14);
        card.setLayoutParams(lp);
        return card;
    }

    private TextView cardTitle(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(TEAL);
        tv.setAllCaps(false);
        tv.setLetterSpacing(0.04f);
        tv.setPadding(0, 0, 0, dp(12));
        return tv;
    }

    private View permissionRow(String name, String why, TextView chip, String action, View.OnClickListener onAction) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView label = new TextView(this);
        label.setText(name);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setTextColor(INK);
        label.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        chip.setPadding(dp(10), dp(4), dp(10), dp(4));

        top.addView(label);
        top.addView(chip);

        TextView reason = new TextView(this);
        reason.setText(why);
        reason.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        reason.setTextColor(INK_SOFT);
        reason.setPadding(0, dp(4), 0, 0);

        View actionBtn = button(action, false, onAction);
        ((LinearLayout.LayoutParams) actionBtn.getLayoutParams()).topMargin = dp(10);

        row.addView(top);
        row.addView(reason);
        row.addView(actionBtn);
        return row;
    }

    private View bullet(String key, String text) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(5), 0, dp(5));

        TextView k = new TextView(this);
        k.setText(key);
        k.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        k.setTypeface(Typeface.DEFAULT_BOLD);
        k.setTextColor(TEAL);
        k.setGravity(Gravity.CENTER);
        k.setPadding(dp(8), dp(3), dp(8), dp(3));
        k.setBackground(rounded(0xFFEAF7F2, dp(6)));

        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setTextColor(INK_SOFT);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tl.leftMargin = dp(10);
        t.setLayoutParams(tl);

        row.addView(k);
        row.addView(t);
        return row;
    }

    /** Filled for primary actions, outlined for secondary — no XML selectors needed. */
    private View button(String text, boolean primary, View.OnClickListener onClick) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(16), dp(12), dp(16), dp(12));
        b.setClickable(true);
        b.setFocusable(true);
        b.setOnClickListener(onClick);

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        if (primary) {
            bg.setColor(TEAL);
            b.setTextColor(Color.WHITE);
        } else {
            bg.setColor(SURFACE);
            bg.setStroke(dp(1), MINT);
            b.setTextColor(TEAL);
        }
        b.setBackground(bg);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        b.setLayoutParams(lp);
        return b;
    }

    private View divider() {
        View v = new View(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2));
        lp.topMargin = dp(14);
        lp.bottomMargin = dp(14);
        v.setLayoutParams(lp);
        v.setBackgroundColor(HAIRLINE);
        return v;
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
