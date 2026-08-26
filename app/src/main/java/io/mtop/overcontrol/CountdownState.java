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
 * <p>Follows the screen: it is whatever concert you are looking at, and it stops being
 * that concert as soon as you leave. Leaving Damai clears it outright; navigating to
 * another concert replaces it; a page that stops confirming the concert at all lets it go
 * stale within {@link #STALE_MS}. An earlier version kept the show for 24h so the
 * countdown would survive switching apps, but that left the pill advertising a concert
 * the user had moved on from, which is worse than showing nothing.
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

    /**
     * How long the tracked concert survives without being re-confirmed on screen. Short,
     * because the pill is meant to describe the page in front of you — but not so short
     * that it flickers between scans, which arrive only as fast as Damai changes its own
     * content (its detail page ticks a countdown every second, so this is comfortable).
     */
    private static final long STALE_MS = 5000L;

    private static volatile Show reservedShow;
    private static volatile boolean damaiForeground = false;

    private CountdownState() {}

    /**
     * Record the concert on screen just now, reserved or not. An unreserved one still
     * counts down — that is the whole point before you have reserved it — it simply
     * doesn't turn the pill green.
     *
     * <p>A different concert always replaces the tracked one: the pill describes the page
     * you are on, so opening another show must switch to it immediately. Within the *same*
     * concert, title and details are merged rather than overwritten, since a page may show
     * the countdown without repeating the venue, and dropping them would make the panel
     * flicker between full and empty.
     */
    static void observe(long target, String title, List<String> details, boolean reserved, long now) {
        Show existing = reservedShow;
        boolean sameShow = existing != null && existing.target == target;

        String keptTitle = title != null ? title : (sameShow ? existing.title : null);
        List<String> keptDetails = !details.isEmpty()
                ? details
                : (sameShow ? existing.details : Collections.<String>emptyList());
        // Once seen as reserved, stay reserved for this show: the marker is only drawn on
        // some parts of the page, so scrolling away from it is not a cancellation.
        boolean keptReserved = reserved || (sameShow && existing.reserved);

        reservedShow = new Show(target, keptTitle, keptDetails, keptReserved, now);
    }

    /**
     * Confirm the tracked concert is still on screen without re-reading its on-sale time —
     * for when the page has scrolled past the countdown block but is still the same show.
     * Without this the pill would go blank while merely scrolling.
     */
    static void touch(long now) {
        Show show = reservedShow;
        if (show == null) return;
        reservedShow = new Show(show.target, show.title, show.details, show.reserved, now);
    }

    /** The title of the tracked concert, or null if nothing is tracked. */
    static String trackedTitle(long now) {
        Show show = reserved(now);
        return show == null ? null : show.title;
    }

    /** The tracked concert, or null if none is being tracked (or it went stale). */
    static Show reserved(long now) {
        Show show = reservedShow;
        if (show == null) return null;
        if (now - show.lastSeen > STALE_MS) {
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
        damaiForeground = false;
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

}
