package io.mtop.overcontrol;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Setup/status screen: no root, no network calls. Walks the user through granting the
 * two permissions the overlay needs (accessibility read access to cn.damai's on-screen
 * text, and draw-overlay), then shows whether both are on.
 */
public class MainActivity extends Activity {

    private TextView status;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        CrashLog.install(this);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(48), dp(24), dp(24));

        TextView title = new TextView(this);
        title.setText("大麦倒计时 Overcontrol");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTextColor(Color.parseColor("#0B6B6B"));

        TextView desc = new TextView(this);
        desc.setText("在大麦界面上显示可拖动的开票倒计时悬浮窗。倒计时直接读取大麦自己显示的开抢时间文字，不联网。"
                + "轻触悬浮窗可展开查看演出名称，再次轻触收起；拖动可移动位置；"
                + "长按可立即点击当前页面上的购票按钮；悬浮窗持续显示🟢满 5 秒时，会自动点击“预约抢票”。\n\n"
                + "Shows a draggable on-sale countdown pill over Damai. The time is read straight "
                + "off Damai's own on-screen text — no network calls. "
                + "Tap the pill to expand it and see the show's name, tap again to collapse; drag to move it; "
                + "long-press it to press the buy button on the page you're on. After the pill has "
                + "shown 🟢 for 5 seconds straight, it presses 预约抢票 on that page by itself.");
        desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        desc.setPadding(0, dp(8), 0, dp(20));

        Button a11yBtn = new Button(this);
        a11yBtn.setText("1. 开启读屏权限 Enable accessibility access");
        a11yBtn.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        Button overlayBtn = new Button(this);
        overlayBtn.setText("2. 开启悬浮窗权限 Enable overlay permission");
        overlayBtn.setOnClickListener(v -> startActivity(new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))));

        Button refreshBtn = new Button(this);
        refreshBtn.setText("刷新状态 Refresh status");
        refreshBtn.setOnClickListener(v -> refresh());

        Button clearCrashBtn = new Button(this);
        clearCrashBtn.setText("清除崩溃记录 Clear crash report");
        clearCrashBtn.setOnClickListener(v -> {
            CrashLog.clear(this);
            refresh();
        });

        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        status.setTextIsSelectable(true); // so a trace can be copied out, not just read
        status.setPadding(0, dp(16), 0, 0);
        status.setGravity(Gravity.START);

        ScrollView sv = new ScrollView(this);
        sv.addView(status);

        root.addView(title);
        root.addView(desc);
        root.addView(a11yBtn);
        root.addView(overlayBtn);
        root.addView(refreshBtn);
        root.addView(clearCrashBtn);
        root.addView(sv);
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        boolean overlayOk = Settings.canDrawOverlays(this);
        boolean a11yOk = isAccessibilityServiceEnabled();

        StringBuilder sb = new StringBuilder();
        sb.append(a11yOk ? "✔ 读屏权限已开启 Accessibility: ON\n" : "✘ 读屏权限未开启 Accessibility: OFF\n");
        sb.append(overlayOk ? "✔ 悬浮窗权限已开启 Overlay: ON\n" : "✘ 悬浮窗权限未开启 Overlay: OFF\n");
        if (a11yOk && overlayOk) {
            sb.append("\n就绪：打开大麦，浏览含开抢时间的页面，悬浮窗会自动出现并倒计时。\n"
                    + "🟢满 5 秒会自动点击“预约抢票”，长按悬浮窗可立即点击购票按钮。\n"
                    + "Ready — open Damai and browse to a page showing an on-sale time; "
                    + "the pill appears automatically and starts counting down. "
                    + "5s of 🟢 auto-presses 预约抢票; long-press presses the buy button now.");
        } else {
            sb.append("\n请先完成以上两步授权。\nGrant both permissions above first.");
        }
        // Surfaced here because the phone that shows "Overcontrol keeps stopping" usually
        // isn't the one with adb attached, and logcat is long gone by the time anyone looks.
        String crash = CrashLog.read(this);
        if (crash != null) {
            sb.append("\n\n⚠ 上次崩溃 Last crash:\n").append(crash);
        }
        status.setText(sb.toString());
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

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
