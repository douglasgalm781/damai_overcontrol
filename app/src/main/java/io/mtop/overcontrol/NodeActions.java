package io.mtop.overcontrol;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.function.Predicate;

/**
 * The click layer: everything needed to actually press something on Damai's screen.
 *
 * <p>Nothing in here runs on its own — the scanner in {@link CountdownAccessibilityService}
 * stays read-only. These are primitives a feature calls when it decides to press something
 * (today: a long-press on the overlay pill, see {@link OverlayService}).
 *
 * <p>Two ways to press, tried in that order by {@link #clickNode}:
 * <ol>
 *   <li><b>{@code ACTION_CLICK} on the node</b> — the real click, routed through the view
 *       itself. Only works if the view (or an ancestor) reports {@code isClickable()}.</li>
 *   <li><b>A synthesized tap at the node's centre</b> via {@code dispatchGesture} — for
 *       Damai's custom-drawn buttons, which are frequently not clickable in the
 *       accessibility tree at all (its bottom 预约抢票/立即购票 bar is the known case). This
 *       is why {@code android:canPerformGestures="true"} is set on the service config; it
 *       is a real touch to the system, so it works on anything visible on screen.</li>
 * </ol>
 *
 * <p>Both require the node to be on screen right now — matching is always done against a
 * fresh {@code getRootInActiveWindow()}, never against nodes cached from an earlier scan
 * (those are recycled, and stale bounds would tap the wrong place).
 */
final class NodeActions {

    static final String TAG = "Overcontrol";

    private static final int MAX_NODES = 4000;
    /** How far up from a matched text node to look for something actually clickable. */
    private static final int CLICKABLE_ANCESTOR_HOPS = 6;
    private static final long TAP_DURATION_MS = 40L;
    /** How much longer than the label itself a matching text may be. */
    private static final int LABEL_SLACK = 3;

    private NodeActions() {}

    /**
     * Presses the first on-screen node whose text or content-description contains any of
     * {@code candidates}, in the order given — so pass the most specific label first.
     *
     * @return true if something was pressed
     */
    static boolean clickByText(AccessibilityService svc, String... candidates) {
        AccessibilityNodeInfo root = rootOf(svc);
        if (root == null) return false;
        try {
            for (String candidate : candidates) {
                if (candidate == null || candidate.isEmpty()) continue;
                AccessibilityNodeInfo hit = find(root, n -> containsText(n, candidate));
                if (hit == null) continue;
                try {
                    Log.i(TAG, "clickByText: matched \"" + candidate + "\"");
                    return clickNode(svc, hit);
                } finally {
                    // `finally` below recycles root; recycling it twice throws on API 30-.
                    if (hit != root) hit.recycle();
                }
            }
            Log.i(TAG, "clickByText: no match on screen");
            return false;
        } finally {
            root.recycle();
        }
    }

    /**
     * Presses the first node with this resource id, e.g. {@code "cn.damai:id/btn_buy"}.
     * More reliable than text when the id is known — get ids from {@link #dumpScreen}.
     */
    static boolean clickByViewId(AccessibilityService svc, String viewId) {
        AccessibilityNodeInfo root = rootOf(svc);
        if (root == null) return false;
        try {
            // Ids are populated because the service config sets flagReportViewIds.
            AccessibilityNodeInfo hit = find(root, n -> {
                CharSequence id = n.getViewIdResourceName();
                return id != null && viewId.contentEquals(id);
            });
            if (hit == null) {
                Log.i(TAG, "clickByViewId: " + viewId + " not on screen");
                return false;
            }
            try {
                return clickNode(svc, hit);
            } finally {
                if (hit != root) hit.recycle(); // root is recycled by the finally below
            }
        } finally {
            root.recycle();
        }
    }

    /**
     * Presses {@code node}: {@code ACTION_CLICK} on it or the nearest clickable ancestor,
     * falling back to a synthesized tap at its centre when neither is clickable (or when
     * the click is accepted by the tree but does nothing).
     */
    static boolean clickNode(AccessibilityService svc, AccessibilityNodeInfo node) {
        if (node == null) return false;

        AccessibilityNodeInfo cursor = node;
        for (int hop = 0; hop <= CLICKABLE_ANCESTOR_HOPS; hop++) {
            if (cursor.isClickable() && cursor.isEnabled()
                    && cursor.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Log.i(TAG, "clickNode: ACTION_CLICK at ancestor hop " + hop);
                if (cursor != node) cursor.recycle();
                return true;
            }
            AccessibilityNodeInfo parent = cursor.getParent();
            if (cursor != node) cursor.recycle();
            if (parent == null) break;
            cursor = parent;
        }

        // Nothing in the chain took a click — Damai draws its own buttons. Tap the pixels.
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.isEmpty()) {
            Log.w(TAG, "clickNode: node has empty bounds, cannot tap");
            return false;
        }
        return tapAt(svc, bounds.exactCenterX(), bounds.exactCenterY());
    }

    /**
     * Taps the unexposed remainder of a bar, to the right of whatever siblings in it *are*
     * exposed. For the case this exists for — Damai's bottom action bar — the 预约抢票 /
     * 立即购票 button contributes no node to the accessibility tree at all (verified on
     * ProjectDetailActivity: the only node overlapping it is the root FrameLayout), so
     * there is nothing to match on. Its neighbours 帮助 and 想看 do expose ids, and the
     * button is simply the rest of the bar beside them.
     *
     * <p>So: union the bounds of every node whose id contains {@code idPrefix}, then tap
     * the midpoint between that union's right edge and the right edge of the screen, at
     * the union's vertical centre. Nothing is hardcoded in pixels — it is all read off the
     * live tree, so it survives a different screen size, density or bar layout.
     *
     * @param minGapFraction how wide the untouched remainder must be, as a fraction of
     *     screen width, before it's believed to be a button — a sanity check so an
     *     unexpected layout makes this fail instead of tapping something arbitrary
     */
    static boolean tapBesideAnchors(AccessibilityService svc, String idPrefix, float minGapFraction) {
        AccessibilityNodeInfo root = rootOf(svc);
        if (root == null) return false;
        try {
            Rect screen = new Rect();
            root.getBoundsInScreen(screen);
            if (screen.isEmpty()) return false;

            Rect union = new Rect();
            collectBounds(root, idPrefix, union);
            if (union.isEmpty()) {
                Log.i(TAG, "tapBesideAnchors: no anchor matching " + idPrefix);
                return false;
            }

            int gap = screen.right - union.right;
            if (gap < screen.width() * minGapFraction) {
                Log.w(TAG, "tapBesideAnchors: gap beside anchors is only " + gap
                        + "px, refusing to tap — layout isn't what's expected");
                return false;
            }
            Log.i(TAG, "tapBesideAnchors: anchors=" + union.toShortString()
                    + " screen=" + screen.toShortString());
            return tapAt(svc, (union.right + screen.right) / 2f, union.exactCenterY());
        } finally {
            root.recycle();
        }
    }

    /** Grows {@code union} to cover every node whose id contains {@code idPrefix}. */
    private static void collectBounds(AccessibilityNodeInfo root, String idPrefix, Rect union) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        while (!queue.isEmpty() && visited < MAX_NODES) {
            AccessibilityNodeInfo node = queue.poll();
            if (node == null) continue;
            visited++;

            CharSequence id = node.getViewIdResourceName();
            if (id != null && id.toString().contains(idPrefix) && node.isVisibleToUser()) {
                Rect b = new Rect();
                node.getBoundsInScreen(b);
                if (!b.isEmpty()) union.union(b);
            }

            int childCount = node.getChildCount();
            for (int i = 0; i < childCount; i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.add(child);
            }
            if (node != root) node.recycle();
        }
    }

    /** Synthesizes a short tap at screen coordinates. Requires canPerformGestures. */
    static boolean tapAt(AccessibilityService svc, float x, float y) {
        // Android 9 and older reject a gesture whose coordinates fall outside the display
        // with IllegalArgumentException rather than just refusing it, and they throw
        // SecurityException when the service was enabled before its config declared
        // canPerformGestures — which is what happens on an in-place upgrade, until
        // accessibility access is toggled off and on again. Neither should take the app
        // down, so both are caught and reported.
        if (!hasGestureCapability(svc)) {
            Log.w(TAG, "tapAt: service has no CAPABILITY_CAN_PERFORM_GESTURES — accessibility "
                    + "access needs to be toggled off and back on after this app was updated");
            return false;
        }
        if (x < 0 || y < 0 || Float.isNaN(x) || Float.isNaN(y)) {
            Log.w(TAG, "tapAt: refusing off-screen coordinates (" + x + ", " + y + ")");
            return false;
        }
        try {
            Path path = new Path();
            path.moveTo(x, y);
            // Drag one pixel, rather than leaving a move-only path. Android 9 and older
            // decide a stroke path is empty from its *bounds*, and a lone moveTo has
            // bounds (x,y,x,y) — zero width and height, so RectF.isEmpty() is true and
            // StrokeDescription throws IllegalArgumentException("Path is empty"). Newer
            // releases test Path.isEmpty() instead, where the moveTo counts as a verb, so
            // the very same path is accepted — which is why a move-only tap works on
            // API 33 and blows up on API 28. One pixel still registers as a tap.
            path.lineTo(x >= 1f ? x - 1f : x + 1f, y >= 1f ? y - 1f : y + 1f);
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MS))
                    .build();
            boolean dispatched = svc.dispatchGesture(gesture, null, null);
            Log.i(TAG, "tapAt(" + x + ", " + y + ") dispatched=" + dispatched);
            return dispatched;
        } catch (IllegalArgumentException | SecurityException | IllegalStateException e) {
            Log.w(TAG, "tapAt(" + x + ", " + y + ") failed", e);
            return false;
        }
    }

    /**
     * Whether the connected service may actually synthesize taps. Declaring
     * canPerformGestures in the config isn't enough on its own: the capability is bound
     * when the user enables the service, so a service that was already enabled under an
     * older config keeps the old, gesture-less capability set until it is re-enabled.
     */
    static boolean hasGestureCapability(AccessibilityService svc) {
        try {
            AccessibilityServiceInfo info = svc.getServiceInfo();
            return info != null && (info.getCapabilities()
                    & AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES) != 0;
        } catch (Exception e) {
            Log.w(TAG, "getServiceInfo failed", e);
            return false;
        }
    }

    /**
     * Dumps every node carrying text, a content-description or an id, with its bounds and
     * whether it is clickable — to logcat under the {@value #TAG} tag:
     * {@code adb logcat -s Overcontrol}. This is the tool for finding out what a Damai page
     * actually exposes before writing a click against it.
     */
    static String dumpScreen(AccessibilityService svc) {
        AccessibilityNodeInfo root = rootOf(svc);
        if (root == null) return "no active window";

        StringBuilder sb = new StringBuilder();
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        while (!queue.isEmpty() && visited < MAX_NODES) {
            AccessibilityNodeInfo node = queue.poll();
            if (node == null) continue;
            visited++;

            // Everything, including anonymous nodes — a custom-drawn button often carries
            // no text, no id and no description, and the gap it leaves in the tree is
            // exactly what you're looking for when a click by text finds nothing.
            Rect b = new Rect();
            node.getBoundsInScreen(b);
            sb.append(node.isClickable() ? "[click] " : "[     ] ")
                    .append(b.toShortString())
                    .append(" cls=").append(node.getClassName())
                    .append(" text=").append(node.getText())
                    .append(" desc=").append(node.getContentDescription())
                    .append(" id=").append(node.getViewIdResourceName())
                    .append('\n');

            int childCount = node.getChildCount();
            for (int i = 0; i < childCount; i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.add(child);
            }
            node.recycle();
        }
        Log.i(TAG, "dumpScreen (" + visited + " nodes):\n" + sb);
        return sb.toString();
    }

    /** BFS over the live window for the first node matching {@code test}. Caller recycles. */
    private static AccessibilityNodeInfo find(AccessibilityNodeInfo root, Predicate<AccessibilityNodeInfo> test) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        while (!queue.isEmpty() && visited < MAX_NODES) {
            AccessibilityNodeInfo node = queue.poll();
            if (node == null) continue;
            visited++;

            if (node.isVisibleToUser() && test.test(node)) return node; // caller recycles

            int childCount = node.getChildCount();
            for (int i = 0; i < childCount; i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) queue.add(child);
            }
            if (node != root) node.recycle();
        }
        return null;
    }

    private static boolean containsText(AccessibilityNodeInfo node, String needle) {
        return isLabel(node.getText(), needle) || isLabel(node.getContentDescription(), needle);
    }

    /**
     * Whether this text is the button label {@code needle} — an exact match, or a
     * containing string barely longer than the label itself.
     *
     * <p>Plain "contains" is not safe here. Damai's detail page carries the sentence
     * 实名制购票和入场 in its terms row, which contains 购票; a contains-match pressed that
     * row and opened the 服务说明 sheet instead of the buy button. A real button's label is
     * essentially just the label, so length is what separates the two.
     */
    private static boolean isLabel(CharSequence cs, String needle) {
        if (cs == null) return false;
        String s = cs.toString().trim();
        return s.equals(needle)
                || (s.contains(needle) && s.length() <= needle.length() + LABEL_SLACK);
    }

    private static AccessibilityNodeInfo rootOf(AccessibilityService svc) {
        try {
            return svc.getRootInActiveWindow();
        } catch (Exception e) {
            Log.w(TAG, "getRootInActiveWindow failed", e);
            return null;
        }
    }
}
