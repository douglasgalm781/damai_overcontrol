package io.mtop.overcontrol;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Foreground service that owns the draggable overlay pill. Independent of Damai's
 * lifecycle: once started it keeps showing the last countdown {@link
 * CountdownAccessibilityService} scraped, ticking locally every second, so it survives
 * Damai being backgrounded or restarted.
 *
 * <p>The pill is also the trigger for the click feature: tap = expand/collapse, drag =
 * move, long-press = press {@link #CLICK_TARGETS} on whatever Damai page is on screen via
 * {@link NodeActions}. On top of that manual press, {@link #maybeAutoClick} fires
 * {@value #AUTO_CLICK_TARGET} on its own once the pill has been 🟢 for
 * {@value #ACTIVE_DWELL_MS}ms straight.
 */
public class OverlayService extends Service {

    static final String ACTION_STOP = "io.mtop.overcontrol.action.STOP";
    private static final String CHANNEL_ID = "overcontrol_status";
    private static final int NOTIF_ID = 1;
    private static final float TAP_SLOP_DP = 14f;

    /**
     * What a long-press on the pill presses, first match wins — so keep the most specific
     * label first. Matching is substring, against a node's text or content-description.
     *
     * <p>This is the list to edit when building a click feature on a different page. To
     * find out what a page actually exposes, long-press with logcat open:
     * {@code adb logcat -s Overcontrol} — and see {@link NodeActions#dumpScreen} for a
     * full listing of every node, id and bounds on screen.
     */
    private static final String[] CLICK_TARGETS = {
            "立即购票",
            "预约抢票",
            "立即预订",
            "特惠购票",
            "购票",
    };

    /** What {@link #maybeAutoClick} presses by itself once the dwell below has elapsed. */
    private static final String AUTO_CLICK_TARGET = "预约抢票";
    /**
     * Fallback for {@link #AUTO_CLICK_TARGET}, because on ProjectDetailActivity that button
     * puts no node in the accessibility tree at all — verified by dump: the only node
     * overlapping it is the root FrameLayout. Its neighbours in the bottom bar (帮助, 想看)
     * share this id prefix and are exposed, so the button is located as the rest of the bar
     * beside them. See {@link NodeActions#tapBesideAnchors}.
     */
    private static final String AUTO_CLICK_ANCHOR_ID_PREFIX = "cn.damai:id/project_item_bottom_";
    /** The button should occupy most of the bar's width; well under this means a different layout. */
    private static final float AUTO_CLICK_MIN_GAP_FRACTION = 0.4f;
    /**
     * How long the status has to stay 🟢 — i.e. the page on screen keeps re-confirming the
     * tracked show, see {@link CountdownState#isActive()} — before the auto-click fires.
     * The dwell is what keeps it off pages merely passed through while scrolling: a
     * single stray active scan isn't enough, the page has to still be there 5s later.
     */
    private static final long ACTIVE_DWELL_MS = 5000L;
    /** Gap between attempts while the target hasn't been found yet on an active page. */
    private static final long AUTO_CLICK_RETRY_MS = 2000L;
    /**
     * On the first failed auto-click of a 🟢 streak, log the whole node tree via
     * {@link NodeActions#dumpScreen} ({@code adb logcat -s Overcontrol}). This is the only
     * way to see what a Damai page exposes — {@code uiautomator dump} never completes on
     * the detail page, whose own on-sale countdown ticks every second so the UI is never
     * idle. Once per streak, so a page that simply lacks the button doesn't spam.
     */
    private static final boolean DUMP_ON_MISS = true;

    private WindowManager wm;
    private TextView pill;
    private WindowManager.LayoutParams lp;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final float[] drag = new float[4]; // touchStartX, touchStartY, pillStartX, pillStartY
    private boolean expanded = false; // tapped open to show the show's scraped title
    private boolean longPressFired = false; // so the following ACTION_UP isn't also a tap
    private final Runnable longPress = this::performTargetClick;

    // Auto-click bookkeeping. activeSince is when the current 🟢 streak was first seen by
    // the ticker (0 = not active right now); both reset the moment the status drops back
    // to ⚪, so leaving the page and coming back arms a fresh 5s dwell.
    private long activeSince = 0L;
    private long lastAutoAttemptAt = 0L;
    private boolean autoClicked = false; // already pressed during this 🟢 streak
    private boolean dumpedThisStreak = false;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            // Anything thrown here would otherwise kill the process — this runs on the
            // main thread once a second — and take the countdown down with it. The click
            // path reaches into another app's node tree, whose shape is not ours to
            // predict, so it is treated as untrusted: log and keep ticking.
            try {
                long now = System.currentTimeMillis();
                maybeAutoClick(now);
                render(now);
            } catch (Throwable t) {
                Log.e(NodeActions.TAG, "tick failed", t);
            } finally {
                handler.postDelayed(this, 1000);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        CrashLog.install(this);
        startForeground(NOTIF_ID, buildNotification());
        addOverlay();
        handler.post(ticker);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(ticker);
        handler.removeCallbacks(longPress);
        removeOverlay();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void addOverlay() {
        if (!Settings.canDrawOverlays(this)) return; // permission not granted yet; nothing to draw

        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        pill = new TextView(this);
        pill.setTextColor(Color.WHITE);
        pill.setTextSize(14f);
        pill.setPadding(dp(16), dp(10), dp(16), dp(10));
        pill.setGravity(Gravity.CENTER);
        pill.setLineSpacing(dp(2), 1f);
        pill.setMaxWidth(dp(260)); // long titles wrap instead of stretching the pill off-screen
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x9A0B0E14);
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), 0xFF3DD6C0);
        pill.setBackground(bg);
        pill.setText("⚪ 等待数据 Waiting…");

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = dp(24);
        lp.y = dp(120);

        pill.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    drag[0] = ev.getRawX();
                    drag[1] = ev.getRawY();
                    drag[2] = lp.x;
                    drag[3] = lp.y;
                    longPressFired = false;
                    handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                    return true;
                case MotionEvent.ACTION_MOVE:
                    lp.x = (int) (drag[2] + (ev.getRawX() - drag[0]));
                    lp.y = (int) (drag[3] + (ev.getRawY() - drag[1]));
                    if (movedBeyondSlop(ev)) handler.removeCallbacks(longPress); // it's a drag
                    try {
                        wm.updateViewLayout(pill, lp);
                    } catch (Exception ignored) {}
                    return true;
                case MotionEvent.ACTION_UP:
                    handler.removeCallbacks(longPress);
                    if (!longPressFired && !movedBeyondSlop(ev)) {
                        expanded = !expanded;
                        render();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    handler.removeCallbacks(longPress);
                    return true;
                default:
                    return false;
            }
        });

        try {
            wm.addView(pill, lp);
        } catch (Exception ignored) {}
    }

    /**
     * Presses {@value #AUTO_CLICK_TARGET} once the status has been 🟢 for
     * {@link #ACTIVE_DWELL_MS} without interruption. Called once a second from the ticker,
     * so the dwell is measured to ±1s — which is also why it can't fire on a page that was
     * only briefly active.
     *
     * <p>At most one successful press per 🟢 streak: after a hit it stays quiet until the
     * status drops to ⚪ (which it does as soon as the press navigates away from the
     * show's own page). A miss — target not on screen yet — is retried every
     * {@link #AUTO_CLICK_RETRY_MS} for as long as the streak lasts.
     */
    private void maybeAutoClick(long now) {
        if (!CountdownState.isActive()) { // streak over (or never started) — rearm
            activeSince = 0L;
            autoClicked = false;
            dumpedThisStreak = false;
            return;
        }
        if (activeSince == 0L) activeSince = now;
        if (autoClicked) return;
        if (now - activeSince < ACTIVE_DWELL_MS) return;
        if (now - lastAutoAttemptAt < AUTO_CLICK_RETRY_MS) return;

        CountdownAccessibilityService svc = CountdownAccessibilityService.peek();
        if (svc == null) return; // accessibility access revoked; nothing to click through
        lastAutoAttemptAt = now;
        if (!NodeActions.hasGestureCapability(svc)) {
            if (!dumpedThisStreak) { // once per streak, same as the miss dump
                dumpedThisStreak = true;
                toast("请关闭再重新开启读屏权限 Turn accessibility access off and on again");
            }
            return;
        }
        // Try the honest route first — it's the one that survives a Damai redesign, and
        // other pages (and older builds) do expose the button as a real node.
        boolean clicked = NodeActions.clickByText(svc, AUTO_CLICK_TARGET)
                || NodeActions.tapBesideAnchors(
                        svc, AUTO_CLICK_ANCHOR_ID_PREFIX, AUTO_CLICK_MIN_GAP_FRACTION);
        if (clicked) {
            autoClicked = true;
            toast("已自动点击 Auto-clicked " + AUTO_CLICK_TARGET);
        } else if (DUMP_ON_MISS && !dumpedThisStreak) {
            dumpedThisStreak = true;
            NodeActions.dumpScreen(svc);
        }
    }

    /** " 5s" / " ▶" appended to the status dot, so the pending auto-click is visible. */
    private String autoClickSuffix(long now) {
        if (activeSince == 0L) return "";
        if (autoClicked) return " ✔";
        long left = ACTIVE_DWELL_MS - (now - activeSince);
        return left > 0 ? " " + ((left + 999) / 1000) + "s" : " ▶";
    }

    private boolean movedBeyondSlop(MotionEvent ev) {
        return Math.hypot(ev.getRawX() - drag[0], ev.getRawY() - drag[1]) >= dp((int) TAP_SLOP_DP);
    }

    /**
     * Long-press handler: presses the first of {@link #CLICK_TARGETS} found on the page
     * currently on screen. Runs only from this user gesture — swap this call for a timer
     * or a countdown hit if you want it to fire on its own.
     */
    private void performTargetClick() {
        longPressFired = true;
        CountdownAccessibilityService svc = CountdownAccessibilityService.peek();
        if (svc == null) {
            toast("读屏服务未连接 Accessibility service not connected");
            return;
        }
        if (!NodeActions.hasGestureCapability(svc)) {
            // Almost always an in-place update: the capability set was bound when the
            // service was first enabled, before the config asked for gestures.
            toast("请关闭再重新开启读屏权限 Turn accessibility access off and on again");
            return;
        }
        boolean clicked;
        try {
            clicked = NodeActions.clickByText(svc, CLICK_TARGETS)
                    || NodeActions.tapBesideAnchors(
                            svc, AUTO_CLICK_ANCHOR_ID_PREFIX, AUTO_CLICK_MIN_GAP_FRACTION);
        } catch (Throwable t) {
            Log.e(NodeActions.TAG, "manual click failed", t);
            toast("点击出错 Click failed — see logcat");
            return;
        }
        toast(clicked
                ? "已点击 Clicked"
                : "未找到可点击目标 No target found on screen");
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private void removeOverlay() {
        if (wm != null && pill != null) {
            try {
                wm.removeView(pill);
            } catch (Exception ignored) {}
        }
    }

    private void render() {
        render(System.currentTimeMillis());
    }

    private void render(long now) {
        if (pill == null) return;
        CountdownState.Show show = CountdownState.nearest(now);
        // 🟢 = the page on screen right now is this show's own page (just reconfirmed it).
        // ⚪ = tracked from an earlier screen — still trusted, just not what's showing now.
        String status = CountdownState.isActive() ? "🟢" : "⚪";
        status += autoClickSuffix(now);

        String text;
        if (show == null) {
            text = status + " 等待数据 Waiting…";
        } else {
            long rem = show.target - now;
            if (rem <= 0) {
                text = "🔴 已开票 On sale";
            } else if (expanded) {
                String label = show.label != null ? show.label : "详情暂缺 No title captured";
                text = status + " " + label + "\n开票倒计时 Starts in\n" + fmt(rem);
            } else {
                text = status + " 开票倒计时 Starts in\n" + fmt(rem);
            }
        }
        pill.setText(text);
    }

    private static String fmt(long ms) {
        long t = ms / 1000;
        long d = t / 86400;
        t -= d * 86400;
        long h = t / 3600;
        t -= h * 3600;
        long m = t / 60;
        long s = t - m * 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append("天 ");
        sb.append(pad(h)).append(':').append(pad(m)).append(':').append(pad(s));
        return sb.toString();
    }

    private static String pad(long n) {
        return n < 10 ? "0" + n : String.valueOf(n);
    }

    private int dp(int v) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(v * density);
    }

    private Notification buildNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Overcontrol 倒计时", NotificationManager.IMPORTANCE_MIN);
            nm.createNotificationChannel(ch);
        }

        Intent stopIntent = new Intent(this, OverlayService.class).setAction(ACTION_STOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent stopPi = PendingIntent.getService(this, 0, stopIntent, piFlags);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle("大麦倒计时运行中 Overcontrol running")
                .setContentText("悬浮窗正在显示开票倒计时 Showing the on-sale countdown")
                .addAction(0, "停止 Stop", stopPi)
                .setOngoing(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            builder.setPriority(Notification.PRIORITY_MIN);
        }
        return builder.build();
    }
}
