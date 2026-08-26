package io.mtop.overcontrol;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.HashSet;

/**
 * Read-only accessibility service scoped to cn.damai (see
 * res/xml/accessibility_service_config.xml). On every window/content change in Damai it
 * walks the currently visible node tree, looks at each TextView's rendered text for one
 * of Damai's own "开抢" on-sale time formats (see {@link CountdownParser}), and records
 * every future on-sale time found — plus a best-effort nearby title — into
 * {@link CountdownState} for {@link OverlayService} to display.
 *
 * The scan itself never performs an action on a node (no click/focus/input) — only
 * getText()/getChild()/getParent(). Pressing things is a separate, explicitly-triggered
 * concern: see {@link NodeActions}, which runs only when a feature asks it to, using the
 * service handle published by {@link #peek()}.
 */
public class CountdownAccessibilityService extends AccessibilityService {

    private static final String TARGET_PACKAGE = "cn.damai";
    private static final int MAX_NODES_PER_SCAN = 4000;
    private static final int MAX_LABEL_SEARCH_NODES = 80;
    private static final int LABEL_SEARCH_ANCESTOR_HOPS = 4;
    private static final long MIN_SCAN_INTERVAL_MS = 800L;

    // The connected service instance, so code outside the accessibility callback (the
    // overlay pill's long-press today) can reach performAction/dispatchGesture through
    // NodeActions. Null whenever the service isn't connected — always null-check.
    private static volatile CountdownAccessibilityService instance;

    private long lastScanAt = 0L;
    // Class name from the most recent window-state-changed event for cn.damai — Damai's
    // single-show detail page is a distinct Activity from any list/channel page, and its
    // bottom "预约抢票"/"立即购票" action bar doesn't reliably expose its text to
    // accessibility (likely a custom-drawn view), so the Activity itself is the signal.
    private String currentActivityClassName;

    /** The running service, or null if accessibility access isn't currently granted. */
    static CountdownAccessibilityService peek() {
        return instance;
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
                CountdownState.clear();
                return;
            }
            CharSequence cls = event.getClassName();
            currentActivityClassName = cls != null ? cls.toString() : null;
        }
        if (!isDamai) return;

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

        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        HashSet<Long> foundThisScan = new HashSet<>();
        HashSet<String> titlesThisScan = new HashSet<>();

        while (!queue.isEmpty() && visited < MAX_NODES_PER_SCAN) {
            AccessibilityNodeInfo node = queue.poll();
            if (node == null) continue;
            visited++;

            CharSequence text = node.getText();
            if (text != null) {
                String s = text.toString();
                if (s.length() >= 6) {
                    Long t = CountdownParser.parseFutureMillis(s, now);
                    if (t != null) {
                        String label = extractLabel(node, s);
                        CountdownState.observe(t, label, now);
                        foundThisScan.add(t);
                    }
                }
                String trimmed = s.trim();
                // Collected screen-wide (not just near a date match): a show's detail page
                // often renders its date as a range ("2026.09.04-09.06") that the parser
                // above never matches, so the title is the only reliable link back to
                // whichever show is being tracked — see the label match below.
                if (isPlausibleTitle(trimmed, null)) titlesThisScan.add(trimmed);
            }

            int childCount = node.getChildCount();
            for (int i = 0; i < childCount; i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.add(child);
            }
            node.recycle();
        }

        CountdownState.Show nearest = CountdownState.nearest(now);
        boolean showConfirmed = nearest != null && (foundThisScan.contains(nearest.target)
                || (nearest.label != null && titlesThisScan.stream()
                        .anyMatch(t -> t.contains(nearest.label) || nearest.label.contains(t))));
        boolean onOwnDetailPage = currentActivityClassName != null
                && currentActivityClassName.contains("projectdetail");
        CountdownState.setActive(showConfirmed && onOwnDetailPage);
    }

    /**
     * Best-effort scrape of the show's title: climbs a few ancestors up from the matched
     * date/time node (to roughly the enclosing card) and returns the longest plausible
     * title-like text found in that subtree. Heuristic, not exact — Damai's card layouts
     * vary, but the title is consistently the longest CJK text near the date in every
     * layout seen so far.
     */
    private static String extractLabel(AccessibilityNodeInfo dateNode, String dateText) {
        AccessibilityNodeInfo cursor = dateNode;
        for (int hop = 0; hop < LABEL_SEARCH_ANCESTOR_HOPS; hop++) {
            AccessibilityNodeInfo parent = cursor.getParent();
            // Only let go of the current node once there is a valid parent to move to.
            // Recycling before this check left `cursor` pointing at a recycled node
            // whenever the climb reached the window root early (getParent() == null), and
            // it was then both read by findBestLabel and recycled a second time below. On
            // API 31+ recycle() is a no-op so that was invisible; on API 28 the pool is
            // real and both the use and the double-recycle throw IllegalStateException —
            // once per scan, i.e. on every window content change.
            if (parent == null) break;
            if (cursor != dateNode) cursor.recycle();
            cursor = parent;
        }
        if (cursor == dateNode) return null; // couldn't climb at all

        String label = findBestLabel(cursor, dateText);
        cursor.recycle();
        return label;
    }

    private static String findBestLabel(AccessibilityNodeInfo root, String excludeText) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        String best = null;
        int visited = 0;

        while (!queue.isEmpty() && visited < MAX_LABEL_SEARCH_NODES) {
            AccessibilityNodeInfo node = queue.poll();
            if (node == null) continue;
            visited++;

            CharSequence text = node.getText();
            if (text != null) {
                String s = text.toString().trim();
                if (isPlausibleTitle(s, excludeText) && (best == null || s.length() > best.length())) {
                    best = s;
                }
            }

            int childCount = node.getChildCount();
            for (int i = 0; i < childCount; i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.add(child);
            }
            if (node != root) node.recycle();
        }
        return best;
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
