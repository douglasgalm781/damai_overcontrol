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
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;
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
 * {@link NodeActions}. On top of that manual press, {@link #maybeBookNow} presses
 * {@value #BOOK_NOW_TARGET} on its own the moment the tracked concert's countdown reaches
 * zero and Damai swaps its button over to it.
 */
public class OverlayService extends Service {

    static final String ACTION_STOP = "io.mtop.overcontrol.action.STOP";

    /** Whether the overlay service is up (and so the pill is on screen). */
    static boolean isRunning() {
        return running;
    }

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
            "立即预订",
            "立即购票",
            "预约抢票",
            "特惠购票",
    };

    /**
     * What {@link #maybeBookNow} presses by itself at T-0. Damai relabels the reserved
     * concert's button from 已预约 to this the instant tickets go on sale.
     */
    private static final String BOOK_NOW_TARGET = "立即预订";
    /**
     * Fallback for {@link #BOOK_NOW_TARGET}, because on ProjectDetailActivity that button
     * puts no node in the accessibility tree at all — verified by dump: the only node
     * overlapping it is the root FrameLayout. Its neighbours in the bottom bar (帮助, 想看)
     * share this id prefix and are exposed, so the button is located as the rest of the bar
     * beside them. See {@link NodeActions#tapBesideAnchors}.
     */
    private static final String AUTO_CLICK_ANCHOR_ID_PREFIX = "cn.damai:id/project_item_bottom_";
    /** The button should occupy most of the bar's width; well under this means a different layout. */
    private static final float AUTO_CLICK_MIN_GAP_FRACTION = 0.4f;
    /** Gap between attempts while the button hasn't appeared yet. */
    private static final long BOOK_RETRY_MS = 1500L;
    /**
     * How long after T-0 to keep trying. Damai doesn't always flip the button the very
     * second the clock runs out, and the user may still be navigating back to the page —
     * but retrying forever would keep tapping a page that has clearly moved on.
     */
    private static final long BOOK_WINDOW_MS = 120_000L;

    /** Side margin of the pill's resting position. */
    private static final int EDGE_MARGIN_DP = 16;
    /**
     * How far above the bottom of the screen the pill starts. Enough to clear Damai's own
     * bottom action bar (~63dp on the devices measured), so the pill's resting place never
     * sits on top of the very button the auto-click is aiming for.
     */
    private static final int BOTTOM_MARGIN_DP = 96;
    /**
     * The pill is hidden around a synthesized tap. It is an overlay window, so it sits
     * above Damai and would receive an injected tap that landed inside it — the click
     * would silently press our own pill instead of the button. Hiding it is what makes the
     * click work no matter where the pill has been dragged.
     */
    private static final long TAP_HIDE_LEAD_MS = 100L;  // let the window relayout first
    private static final long TAP_HIDE_TOTAL_MS = 600L; // then restore well after the tap
    /**
     * On the first failed auto-click of a 🟢 streak, log the whole node tree via
     * {@link NodeActions#dumpScreen} ({@code adb logcat -s Overcontrol}). This is the only
     * way to see what a Damai page exposes — {@code uiautomator dump} never completes on
     * the detail page, whose own on-sale countdown ticks every second so the UI is never
     * idle. Once per streak, so a page that simply lacks the button doesn't spam.
     */
    private static final boolean DUMP_ON_MISS = true;

    // Lets MainActivity show whether the pill is up, and offer to start it again — the
    // only other thing that ever starts this service is the accessibility service
    // connecting, so without this, hiding the pill would be a one-way door.
    private static volatile boolean running = false;

    private WindowManager wm;
    private TextView pill;
    private WindowManager.LayoutParams lp;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final float[] drag = new float[4]; // touchStartX, touchStartY, pillStartX, pillStartY
    private boolean expanded = false; // tapped open to show the show's scraped title
    private boolean longPressFired = false; // so the following ACTION_UP isn't also a tap
    private final Runnable longPress = this::performTargetClick;

    // Booking bookkeeping, keyed by the tracked concert's on-sale time so that a different
    // reservation (or a rescheduled one) starts over with a clean slate.
    private long bookedTarget = 0L;      // on-sale time we have already pressed for
    private long lastBookAttemptAt = 0L;
    private boolean dumpedThisTarget = false;
    private int lastPillHeight = -1;
    private int lastPillWidth = -1;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            // Anything thrown here would otherwise kill the process — this runs on the
            // main thread once a second — and take the countdown down with it. The click
            // path reaches into another app's node tree, whose shape is not ours to
            // predict, so it is treated as untrusted: log and keep ticking.
            try {
                long now = System.currentTimeMillis();
                maybeBookNow(now);
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
        running = true;
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
        running = false;
        handler.removeCallbacks(ticker);
        handler.removeCallbacks(longPress);
        handler.removeCallbacks(showPill);
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
        // TOP|START keeps the drag maths natural (y grows downward, matching getRawY);
        // the resting place near the bottom is applied once the pill has a measured height.
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = dp(EDGE_MARGIN_DP);
        lp.y = dp(BOTTOM_MARGIN_DP);

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
                    clampToScreen();
                    applyLayout();
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
        // WRAP_CONTENT means the height isn't known until the first layout pass.
        pill.post(this::restAtBottom);
    }

    /** Parks the pill near the bottom-left, above Damai's action bar. */
    private void restAtBottom() {
        if (pill == null || lp == null) return;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        lp.x = dp(EDGE_MARGIN_DP);
        lp.y = dm.heightPixels - pill.getHeight() - dp(BOTTOM_MARGIN_DP);
        clampToScreen();
        applyLayout();
    }

    /** Keeps the pill fully on screen, so it can't be dragged out of reach. */
    private void clampToScreen() {
        if (pill == null || lp == null) return;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int maxX = Math.max(0, dm.widthPixels - pill.getWidth());
        int maxY = Math.max(0, dm.heightPixels - pill.getHeight());
        lp.x = Math.min(Math.max(0, lp.x), maxX);
        lp.y = Math.min(Math.max(0, lp.y), maxY);
    }

    private void applyLayout() {
        try {
            wm.updateViewLayout(pill, lp);
        } catch (Exception ignored) {}
    }

    /**
     * Runs a click with the pill hidden, so an injected tap can never land on our own
     * overlay instead of Damai. The lead delay gives the window manager a chance to drop
     * the pill out of hit-testing before the gesture is dispatched.
     */
    private void clickWithPillHidden(Runnable click) {
        if (pill != null) pill.setVisibility(View.GONE);
        handler.removeCallbacks(showPill);
        handler.postDelayed(() -> {
            try {
                click.run();
            } catch (Throwable t) {
                Log.e(NodeActions.TAG, "click failed", t);
            } finally {
                handler.postDelayed(showPill, TAP_HIDE_TOTAL_MS - TAP_HIDE_LEAD_MS);
            }
        }, TAP_HIDE_LEAD_MS);
    }

    private final Runnable showPill = () -> {
        if (pill != null) pill.setVisibility(View.VISIBLE);
    };

    /**
     * Presses {@value #BOOK_NOW_TARGET} once the tracked concert's on-sale time has
     * arrived. Called once a second from the ticker.
     *
     * <p>Unlike the dwell this replaces, there is nothing to wait for: T-0 is the moment,
     * and being a second late costs a ticket. So it fires on the first tick at or after
     * the target and then retries every {@link #BOOK_RETRY_MS} for {@link
     * #BOOK_WINDOW_MS}, because Damai does not always relabel the button the instant the
     * clock runs out. One success per concert; a different reservation starts over.
     */
    private void maybeBookNow(long now) {
        CountdownState.Show show = CountdownState.reserved(now);
        if (show == null) return;

        if (show.target != bookedTarget) dumpedThisTarget = false; // new concert, new slate
        if (now < show.target) return;                             // not yet
        if (bookedTarget == show.target) return;                   // already pressed
        if (now - show.target > BOOK_WINDOW_MS) return;             // window closed
        if (now - lastBookAttemptAt < BOOK_RETRY_MS) return;
        // Never dispatch a tap into another app: at T-0 the 已预约 marker is gone, so this
        // is what keeps the press inside Damai.
        if (!CountdownState.isDamaiForeground()) return;

        CountdownAccessibilityService svc = CountdownAccessibilityService.peek();
        if (svc == null) return; // accessibility access revoked; nothing to click through
        lastBookAttemptAt = now;
        if (!NodeActions.hasGestureCapability(svc)) {
            if (!dumpedThisTarget) {
                dumpedThisTarget = true;
                toast("请关闭再重新开启读屏权限 Turn accessibility access off and on again");
            }
            return;
        }

        long target = show.target;
        clickWithPillHidden(() -> {
            // By name first — that survives a Damai redesign. The coordinate fallback is
            // only reached on the detail page, whose button exposes no node at all.
            boolean clicked = NodeActions.clickByText(svc, BOOK_NOW_TARGET)
                    || NodeActions.tapBesideAnchors(
                            svc, AUTO_CLICK_ANCHOR_ID_PREFIX, AUTO_CLICK_MIN_GAP_FRACTION);
            if (clicked) {
                bookedTarget = target;
                showClickEffect();
                toast("已点击 " + BOOK_NOW_TARGET);
            } else if (DUMP_ON_MISS && !dumpedThisTarget) {
                dumpedThisTarget = true;
                NodeActions.dumpScreen(svc);
            }
        });
    }

    /** Plays the burst around the pill, once it is back on screen after the tap. */
    private void showClickEffect() {
        handler.postDelayed(() -> {
            if (pill == null || wm == null) return;
            int[] loc = new int[2];
            pill.getLocationOnScreen(loc);
            ClickEffectView.burst(this, wm,
                    loc[0] + pill.getWidth() / 2, loc[1] + pill.getHeight() / 2);
        }, TAP_HIDE_TOTAL_MS - TAP_HIDE_LEAD_MS);
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
        clickWithPillHidden(() -> {
            boolean clicked = NodeActions.clickByText(svc, CLICK_TARGETS)
                    || NodeActions.tapBesideAnchors(
                            svc, AUTO_CLICK_ANCHOR_ID_PREFIX, AUTO_CLICK_MIN_GAP_FRACTION);
            if (clicked) showClickEffect();
            toast(clicked
                    ? "已点击 Clicked"
                    : "未找到可点击目标 No target found on screen");
        });
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
        CountdownState.Show show = CountdownState.reserved(now);

        String text;
        if (show == null) {
            // Nothing reserved has been seen yet. Opening the reserved concert's page in
            // Damai is what starts the tracking.
            text = "⚪ 等待已预约演出\nWaiting for a reserved show";
        } else {
            long remaining = show.target - now;
            // 🟢 means: locked onto the concert you reserved. It stays green while the
            // user is off in another app, because the countdown does too.
            String head = remaining > 0 ? "🟢 开票倒计时 Starts in\n" + fmt(remaining) : onSaleLine(now, show);
            text = expanded ? head + "\n" + describe(show) : head;
        }
        pill.setText(text);
        // Expanding to show the title makes the pill taller, and it grows downward from
        // lp.y — parked near the bottom that would run it off the screen. Re-clamp, but
        // only when the size actually changed, so the once-a-second tick doesn't churn
        // through a window relayout every time the digits change.
        pill.post(() -> {
            if (pill == null) return;
            int h = pill.getHeight(), w = pill.getWidth();
            if (h == lastPillHeight && w == lastPillWidth) return;
            lastPillHeight = h;
            lastPillWidth = w;
            clampToScreen();
            applyLayout();
        });
    }

    /** What the pill says from T-0 onward: pressing, pressed, or the window has closed. */
    private String onSaleLine(long now, CountdownState.Show show) {
        if (bookedTarget == show.target) return "✅ 已抢 Booked";
        if (now - show.target > BOOK_WINDOW_MS) return "🔴 已开售 On sale";
        return "🎯 抢票中… Booking…";
    }

    /** The expanded pill: which concert this is, and everything scraped about it. */
    private static String describe(CountdownState.Show show) {
        StringBuilder sb = new StringBuilder();
        sb.append("— — —\n");
        sb.append(show.title != null ? show.title : "演出名称暂缺 No title captured");
        for (String detail : show.details) sb.append('\n').append(detail);
        return sb.toString();
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
