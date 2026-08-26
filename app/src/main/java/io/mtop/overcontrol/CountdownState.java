package io.mtop.overcontrol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The one concert being tracked: whichever concert page the user is looking at that has a
 * future on-sale time, found by {@link CountdownAccessibilityService} and displayed by
 * {@link OverlayService}.
 *
 * <p>Earlier this held every on-sale time seen this session and displayed whichever was
 * soonest. That guessed at intent — the soonest show anywhere is rarely the one you care
 * about — so exactly one show is tracked: the one on screen, with a reserved one
 * outranking it so that browsing elsewhere can't steal tracking from it.
 *
 * <p>Deliberately survives leaving Damai (unlike the old behaviour, which dropped
 * everything the moment Damai lost focus): the countdown has to keep running while the
 * user is in another app, because the whole point is to be back on the page at T-0. A
 * stale reservation ages out via {@link #TTL_MS} instead.
 */
final class CountdownState {

    /** The tracked concert. Immutable; replaced wholesale on each confirmed sighting. */
    static final class Show {
        final long target;          // on-sale (开抢) time, epoch millis
        final String title;         // nullable — no title could be scraped
        final List<String> details; // date / venue / price lines for the expanded pill
        final boolean reserved;     // Damai showed this concert as 已预约 — turns the pill green
        final long lastSeen;

        Show(long target, String title, List<String> details, boolean reserved, long lastSeen) {
            this.target = target;
            this.title = title;
            this.details = Collections.unmodifiableList(new ArrayList<>(details));
            this.reserved = reserved;
            this.lastSeen = lastSeen;
        }
    }

    /** Drop a reservation not re-seen in this long, so a cancelled one can't linger. */
    private static final long TTL_MS = 24L * 3600_000L;

    private static volatile Show reservedShow;
    // Whether the screen scanned most recently was this concert's own page. Not what
    // drives the pill's colour — it gates the click, which can only work on that page.
    private static volatile boolean onItsPage = false;
    private static volatile boolean damaiForeground = false;

    private CountdownState() {}

    /**
     * Record the concert on screen just now, reserved or not. An unreserved one still
     * counts down — that is the whole point before you have reserved it — it simply
     * doesn't turn the pill green.
     *
     * <p>A reserved concert outranks an unreserved one: browsing another show must not
     * steal tracking from the one actually reserved. Title and details are merged rather
     * than overwritten, since a page may show the countdown without repeating the venue,
     * and dropping them would make the panel flicker between full and empty.
     */
    static void observe(long target, String title, List<String> details, boolean reserved, long now) {
        Show existing = reservedShow;
        boolean sameShow = existing != null && existing.target == target;
        if (existing != null && existing.reserved && !reserved && !sameShow) return;

        String keptTitle = title != null ? title : (sameShow ? existing.title : null);
        List<String> keptDetails = !details.isEmpty()
                ? details
                : (sameShow ? existing.details : Collections.<String>emptyList());
        // Once seen as reserved, stay reserved for this show: the marker is only drawn on
        // some parts of the page, so scrolling away from it is not a cancellation.
        boolean keptReserved = reserved || (sameShow && existing.reserved);

        reservedShow = new Show(target, keptTitle, keptDetails, keptReserved, now);
    }

    /** The tracked concert, or null if none is being tracked (or it aged out). */
    static Show reserved(long now) {
        Show show = reservedShow;
        if (show == null) return null;
        if (now - show.lastSeen > TTL_MS) {
            reservedShow = null;
            return null;
        }
        return show;
    }

    /** True when the tracked concert is reserved — this is what turns the pill green. */
    static boolean isReserved(long now) {
        Show show = reserved(now);
        return show != null && show.reserved;
    }

    /** Forget the tracked concert entirely. */
    static void clear() {
        reservedShow = null;
        onItsPage = false;
        damaiForeground = false;
    }

    static void setOnItsPage(boolean value) {
        onItsPage = value;
    }

    /**
     * Whether Damai is the app in the foreground. Gates the booking press: at T-0 the
     * 已预约 marker is gone (Damai has relabelled the button), so "is this the concert's
     * page" can no longer be answered from the marker, and without this a tap could be
     * dispatched into whatever app the user happens to be in.
     */
    static void setDamaiForeground(boolean value) {
        damaiForeground = value;
    }

    static boolean isDamaiForeground() {
        return damaiForeground;
    }

    /** True if the page scanned most recently was the tracked concert's own page. */
    static boolean isOnItsPage() {
        return onItsPage;
    }
}
