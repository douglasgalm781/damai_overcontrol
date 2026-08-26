package io.mtop.overcontrol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The one concert being tracked: the one Damai shows as 已预约 (already reserved), found by
 * {@link CountdownAccessibilityService} and displayed by {@link OverlayService}.
 *
 * <p>Earlier this held every on-sale time seen this session and displayed whichever was
 * soonest. That guessed at intent — the soonest show on screen is rarely the one you care
 * about. A 已预约 badge is the user saying which concert matters, so exactly one show is
 * tracked and everything else on screen is ignored.
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
        final long lastSeen;

        Show(long target, String title, List<String> details, long lastSeen) {
            this.target = target;
            this.title = title;
            this.details = Collections.unmodifiableList(new ArrayList<>(details));
            this.lastSeen = lastSeen;
        }
    }

    /** Drop a reservation not re-seen in this long, so a cancelled one can't linger. */
    private static final long TTL_MS = 24L * 3600_000L;

    private static volatile Show reserved;
    // Whether the screen scanned most recently was this concert's own page. Not what
    // drives the pill's colour — it gates the click, which can only work on that page.
    private static volatile boolean onItsPage = false;
    private static volatile boolean damaiForeground = false;

    private CountdownState() {}

    /**
     * Record the 已预约 concert seen on screen just now. Title and details are merged
     * rather than overwritten: a page may show the reservation and the on-sale time
     * without repeating the venue or price, and dropping them would make the expanded
     * pill flicker between full and empty.
     */
    static void observeReserved(long target, String title, List<String> details, long now) {
        Show existing = reserved;
        boolean sameShow = existing != null && existing.target == target;

        String keptTitle = title != null ? title : (sameShow ? existing.title : null);
        List<String> keptDetails = !details.isEmpty()
                ? details
                : (sameShow ? existing.details : Collections.emptyList());

        reserved = new Show(target, keptTitle, keptDetails, now);
    }

    /** The tracked concert, or null if none is being tracked (or it aged out). */
    static Show reserved(long now) {
        Show show = reserved;
        if (show == null) return null;
        if (now - show.lastSeen > TTL_MS) {
            reserved = null;
            return null;
        }
        return show;
    }

    /** True once a 已预约 concert is being tracked — this is what turns the pill green. */
    static boolean isTracking(long now) {
        return reserved(now) != null;
    }

    /** Forget the tracked concert entirely. */
    static void clear() {
        reserved = null;
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
