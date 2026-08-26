package io.mtop.overcontrol;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Read-only accessibility service scoped to cn.damai (see
 * res/xml/accessibility_service_config.xml). On every window/content change in Damai it
 * walks the currently visible node tree looking for a concert marked as reserved (see
 * {@link #RESERVED_MARKERS}) — the one the user has already reserved. When it finds one it reads
 * that page's "开抢" on-sale time (see {@link CountdownParser}) plus the title, date, venue
 * and price, and hands them to {@link CountdownState} for {@link OverlayService} to
 * display and to click at T-0.
 *
 * <p>Pages with no reserved marker on them are ignored outright, however many on-sale
 * times they show; what they did expose is recorded to {@link ScreenLog} so a page that
 * should have matched can be inspected in {@link MainActivity}.
 *
 * The scan itself never performs an action on a node (no click/focus/input) — only
 * getText()/getChild()/getParent(). Pressing things is a separate, explicitly-triggered
 * concern: see {@link NodeActions}, which runs only when a feature asks it to, using the
 * service handle published by {@link #peek()}.
 */
public class CountdownAccessibilityService extends AccessibilityService {

    private static final String TARGET_PACKAGE = "cn.damai";
    /**
     * What Damai renders once a concert has been reserved, and so the marker for "this is
     * the concert the user cares about". Before reserving, the same control reads 预约抢票.
     *
     * <p>Several spellings, because the state surfaces differently depending on where it
     * is drawn, and the one place we cannot read is the bottom action button — it puts no
     * node in the tree at all (verified by dump: only 帮助 and 想看 are exposed down there).
     * Each of these is unambiguous on its own: an unreserved page never says 取消预约, and
     * the bare 预约 badge on a tour-city tab deliberately does not match any of them.
     */
    private static final String[] RESERVED_MARKERS = {"已预约", "预约成功", "取消预约"};
    private static final int MAX_NODES_PER_SCAN = 4000;
    private static final long MIN_SCAN_INTERVAL_MS = 800L;

    // "2026.10.10-10.11" — the show's own date, as printed under its name.
    private static final Pattern FULL_DATE = Pattern.compile(
            "\\d{4}[.\\-/]\\d{1,2}[.\\-/]\\d{1,2}");
    // "10月10日" — the fallback when the year isn't spelled out.
    private static final Pattern DATE_LIKE = Pattern.compile(
            "\\d{4}[.\\-/]\\d{1,2}[.\\-/]\\d{1,2}|\\d{1,2}月\\d{1,2}日");
    // A price with an actual number in it. Damai often splits the ¥ into its own node, so
    // a bare "¥" is not a price — see the rejoin in pickDetails.
    private static final Pattern PRICE_LIKE = Pattern.compile("¥\\s*\\d");
    private static final Pattern VENUE_LIKE = Pattern.compile(
            "体育场|体育馆|中心|剧院|剧场|大剧院|文化宫|广场|馆$");

    // The connected service instance, so code outside the accessibility callback (the
    // overlay pill's long-press today) can reach performAction/dispatchGesture through
    // NodeActions. Null whenever the service isn't connected — always null-check.
    private static volatile CountdownAccessibilityService instance;

    private long lastScanAt = 0L;

    /** The running service, or null if accessibility access isn't currently granted. */
    static CountdownAccessibilityService peek() {
        return instance;
    }

    /**
     * Turns this accessibility service off from inside the app, so "exit" can actually
     * stop the screen reading rather than just hiding the overlay and leaving a service
     * running that the user believes they closed. Re-enabling needs the system
     * accessibility screen — an app cannot grant itself the permission back.
     *
     * @return false if the service wasn't running to begin with
     */
    static boolean disableService() {
        CountdownAccessibilityService svc = instance;
        if (svc == null) return false;
        CountdownState.clear();
        svc.disableSelf(); // API 24, and minSdk is 24
        instance = null;
        return true;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        CrashLog.install(this);
        instance = this;
        Intent intent = new Intent(this, OverlayService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        CharSequence pkg = event.getPackageName();
        boolean isDamai = pkg != null && TARGET_PACKAGE.contentEquals(pkg);

        // A window-state-changed event names whichever app/window just took focus. If
        // that's not Damai, the user has left it — drop everything tracked so the overlay
        // goes back to "waiting" rather than keep counting down a show Damai isn't even
        // showing anymore. (This is also the only reason this service sees events for
        // packages other than cn.damai at all — their content is never inspected below.)
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (!isDamai) {
                // Keep the reserved concert — the countdown has to keep running while the
                // user is elsewhere, since the point is to be back here at T-0. Only the
                // "on its page" flag, which gates clicking, is dropped.
                CountdownState.setOnItsPage(false);
                CountdownState.setDamaiForeground(false);
                return;
            }
        }
        if (!isDamai) return;
        CountdownState.setDamaiForeground(true);

        long now = System.currentTimeMillis();
        if (now - lastScanAt < MIN_SCAN_INTERVAL_MS) return;
        lastScanAt = now;
        // The scan walks another app's node tree, whose shape and lifetime are not ours to
        // control — a node can be recycled out from under us mid-walk. On API 30 and below
        // that surfaces as IllegalStateException from the node pool, and letting it out of
        // this callback kills the whole process (taking the overlay with it) on every
        // content change. Drop the scan instead; the next event will try again.
        try {
            scan(now);
        } catch (Throwable t) {
            Log.e(NodeActions.TAG, "scan failed", t);
        }
    }

    private void scan(long now) {
        AccessibilityNodeInfo root;
        try {
            root = getRootInActiveWindow();
        } catch (Exception e) {
            return;
        }
        if (root == null) return;

        // One pass, collecting everything the decisions below need. Text is gathered in
        // document order, which is roughly reading order, so the title tends to come
        // before the date and venue that belong to it.
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        boolean reservedHere = false;
        Long onSaleAt = null;
        ArrayList<String> texts = new ArrayList<>();

        while (!queue.isEmpty() && visited < MAX_NODES_PER_SCAN) {
            AccessibilityNodeInfo node = queue.poll();
            if (node == null) continue;
            visited++;

            String text = textOf(node);
            if (text != null) {
                String trimmed = text.trim();
                if (!trimmed.isEmpty()) texts.add(trimmed);
                if (!reservedHere && isReservedMarker(trimmed)) reservedHere = true;
                if (trimmed.length() >= 6 && onSaleAt == null) {
                    onSaleAt = CountdownParser.parseFutureMillis(trimmed, now);
                }
            }

            int childCount = node.getChildCount();
            for (int i = 0; i < childCount; i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.add(child);
            }
            node.recycle();
        }

        if (!reservedHere) {
            // Record what this page *did* expose. If a concert is reserved and the pill
            // still says "waiting", this is the evidence needed to find the right marker:
            // the bottom action button contributes no node at all on the detail page, so
            // 已预约 may simply never reach us. Shown in MainActivity.
            ScreenLog.record(this, now, describeScreen(texts, onSaleAt));
            // Not the reserved concert's page. Leave whatever is already tracked alone —
            // it is still counting down — but nothing here can be clicked.
            CountdownState.setOnItsPage(false);
            return;
        }

        CountdownState.Show tracked = CountdownState.reserved(now);
        if (onSaleAt == null) {
            // Reserved, but this screen doesn't spell out the on-sale time (a list row, or
            // the detail page after the countdown block has scrolled away). Keep tracking.
            CountdownState.setOnItsPage(tracked != null);
            return;
        }

        CountdownState.observeReserved(onSaleAt, pickTitle(texts), pickDetails(texts), now);
        CountdownState.setOnItsPage(true);
    }

    /** A compact, human-readable snapshot of a screen for {@link ScreenLog}. */
    private static String describeScreen(List<String> texts, Long onSaleAt) {
        StringBuilder sb = new StringBuilder();
        sb.append("找不到标记 no reserved marker among: ")
                .append(java.util.Arrays.toString(RESERVED_MARKERS)).append('\n');
        sb.append("开抢时间 on-sale parsed: ").append(onSaleAt == null ? "no" : "yes").append('\n');
        sb.append("页面文字 texts on screen (").append(texts.size()).append("):\n");
        for (String t : texts) {
            if (t.length() <= 30) sb.append("  · ").append(t).append('\n');
        }
        return sb.toString();
    }

    private static boolean isReservedMarker(String text) {
        for (String marker : RESERVED_MARKERS) {
            if (text.contains(marker)) return true;
        }
        return false;
    }

    /** A node's visible text, falling back to its content-description. */
    private static String textOf(AccessibilityNodeInfo node) {
        CharSequence text = node.getText();
        if (text != null) return text.toString();
        CharSequence desc = node.getContentDescription();
        return desc != null ? desc.toString() : null;
    }

    /** The longest title-shaped string on the page — Damai puts the show name first. */
    private static String pickTitle(List<String> texts) {
        String best = null;
        for (String t : texts) {
            if (!isPlausibleTitle(t, null)) continue;
            if (best == null || t.length() > best.length()) best = t;
        }
        return best;
    }

    /**
     * Date, venue and price for the expanded pill, in that order and at most one of each.
     * Matched by shape rather than by view id: the ids differ between Damai's list rows
     * and its detail page, but these three always look the same wherever they appear.
     */
    private static List<String> pickDetails(List<String> texts) {
        String date = null, venue = null, price = null;

        // The show's own date first, and never an on-sale line: "08月31日 11:50开抢" also
        // looks like a date, but it is the countdown the pill already displays, so
        // showing it as the concert's date would be both wrong and redundant.
        for (String t : texts) {
            if (t.length() > 40 || isOnSaleLine(t)) continue;
            if (FULL_DATE.matcher(t).find()) { date = t; break; }
        }

        for (int i = 0; i < texts.size(); i++) {
            String t = texts.get(i);
            if (t.length() > 40) continue;
            if (date == null && !isOnSaleLine(t) && DATE_LIKE.matcher(t).find()) date = t;
            if (venue == null && VENUE_LIKE.matcher(t).find()) venue = t;
            if (price == null) price = priceAt(texts, i);
        }

        ArrayList<String> details = new ArrayList<>(3);
        if (date != null) details.add(date);
        if (venue != null) details.add(venue);
        if (price != null) details.add(price);
        return details;
    }

    /**
     * The price at this position, rejoining the currency symbol with its number when Damai
     * renders them as two nodes — which it does on the detail page, where the price reads
     * "¥380－980" on screen but reaches us as "¥" followed by "380－980".
     */
    private static String priceAt(List<String> texts, int i) {
        String t = texts.get(i);
        if (PRICE_LIKE.matcher(t).find()) return t;
        if (!t.equals("¥") || i + 1 >= texts.size()) return null;
        String next = texts.get(i + 1);
        return !next.isEmpty() && Character.isDigit(next.charAt(0)) ? t + next : null;
    }

    /** True for Damai's "…开抢/开票/开售" lines, which announce the sale, not the show. */
    private static boolean isOnSaleLine(String t) {
        return t.contains("开抢") || t.contains("开票") || t.contains("开售");
    }


    private static boolean isPlausibleTitle(String s, String excludeText) {
        if (s.isEmpty() || s.equals(excludeText)) return false;
        if (s.length() < 4 || s.length() > 60) return false;
        if (s.contains("¥") || s.contains("元起") || s.contains("满减")) return false;
        if (s.matches(".*\\d{1,2}:\\d{2}.*")) return false; // looks like a time, not a title
        switch (s) {
            case "开抢":
            case "预约抢票":
            case "购票":
            case "立即购票":
            case "想看":
            case "看过":
                return false;
            default:
                return true;
        }
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    @Override
    public void onInterrupt() {}
}
