package io.mtop.overcontrol;

import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Every future on-sale time {@link CountdownAccessibilityService} has ever scraped off
 * Damai's screen this session, keyed by target time -> the show (plus when it was last
 * (re)seen). Shared with {@link OverlayService} (reader, ticks the overlay). Concurrent
 * map is enough for correctness across the two services' threads without extra locking.
 *
 * Remembering every show seen (not just the latest screen's nearest) is what lets the
 * overlay fall through to the next-soonest show once the current one's sale time passes,
 * even if the user has since navigated away from the screen that showed it.
 */
final class CountdownState {

    /** A tracked show: its on-sale time, a best-effort scraped title, and last-seen time. */
    static final class Show {
        final long target;
        final String label; // nullable — no title text could be found nearby
        final long lastSeen;

        Show(long target, String label, long lastSeen) {
            this.target = target;
            this.label = label;
            this.lastSeen = lastSeen;
        }
    }

    private static final int MAX_TRACKED = 64;
    // Drop a show we haven't re-seen on screen in this long, even if its on-sale time is
    // still in the future — guards against showing a stale time forever after Damai
    // reschedules or cancels it and the user never happens to revisit that exact card.
    private static final long CONFIRM_TTL_MS = 24L * 3600_000L;

    private static final ConcurrentSkipListMap<Long, Show> seen = new ConcurrentSkipListMap<>();

    // Whether the screen scanned just now was actually the tracked show's own page —
    // i.e. the currently-open Damai page re-confirmed the nearest show, as opposed to the
    // countdown just being remembered from a screen the user has since left.
    private static volatile boolean active = false;

    private CountdownState() {}

    /** Record a future on-sale time observed on screen just now, with its scraped title. */
    static void observe(long target, String label, long now) {
        Show existing = seen.get(target);
        String keptLabel = (label != null) ? label : (existing != null ? existing.label : null);
        seen.put(target, new Show(target, keptLabel, now));
        while (seen.size() > MAX_TRACKED) seen.pollLastEntry();
    }

    /** The soonest still-relevant show, or null if none are tracked. */
    static Show nearest(long now) {
        seen.headMap(now).clear(); // already on sale, no longer useful
        seen.entrySet().removeIf(e -> now - e.getValue().lastSeen > CONFIRM_TTL_MS);
        Map.Entry<Long, Show> first = seen.firstEntry();
        return first == null ? null : first.getValue();
    }

    /** Drop everything tracked — called once Damai leaves the foreground. */
    static void clear() {
        seen.clear();
        active = false;
    }

    /**
     * Record whether the show currently being displayed was itself re-confirmed by the
     * screen just scanned (see {@link CountdownAccessibilityService#scan}).
     */
    static void setActive(boolean isActive) {
        active = isActive;
    }

    /** True if the on-screen page just scanned was the tracked show's own page. */
    static boolean isActive() {
        return active;
    }
}
