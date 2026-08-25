package io.mtop.overcontrol;

import java.util.Calendar;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the on-sale ("开抢") timestamps Damai already renders as plain text
 * (search results, home feed cards, artist pages, project detail) into absolute
 * epoch millis. Formats below are reverse engineered from Damai's own
 * DateUtil/ReservationBean date formatting (see reference/decompiled) — they are
 * exactly the strings the app puts on screen, so no network access or view-id
 * knowledge is required, only the rendered text.
 */
final class CountdownParser {
    private static final TimeZone TZ = TimeZone.getTimeZone("Asia/Shanghai");

    // "8月20日10:00开抢" / "8月20日 10:00 开抢"
    private static final Pattern MONTH_DAY = Pattern.compile("(\\d{1,2})月(\\d{1,2})日\\s*(\\d{1,2}):(\\d{2})\\s*开抢");
    // "今天 10:00 开抢"
    private static final Pattern TODAY = Pattern.compile("今天\\s*(\\d{1,2}):(\\d{2})\\s*开抢");
    // "明天 10:00 开抢"
    private static final Pattern TOMORROW = Pattern.compile("明天\\s*(\\d{1,2}):(\\d{2})\\s*开抢");
    // "后天 10:00 开抢"
    private static final Pattern DAY_AFTER_TOMORROW = Pattern.compile("后天\\s*(\\d{1,2}):(\\d{2})\\s*开抢");
    // "08-20 10:00 开抢"
    private static final Pattern DASHED = Pattern.compile("(\\d{2})-(\\d{2})\\s+(\\d{1,2}):(\\d{2})\\s*开抢");
    // Bare "08月21日13:00" with no "开抢" suffix in the same node — some cards (e.g. the
    // home-feed grab card) render the on-sale label as a separate sibling node, so the
    // date/time TextView's own text is just Damai's DateUtil.homGrabTimeFormat ("M月d日HH:mm")
    // on its own. Anchored to the whole (trimmed) node text to avoid matching a date
    // embedded inside unrelated prose.
    private static final Pattern BARE_MONTH_DAY =
            Pattern.compile("^\\s*(\\d{1,2})月(\\d{1,2})日\\s*(\\d{1,2}):(\\d{2})\\s*$");

    private CountdownParser() {}

    /**
     * Returns the absolute epoch millis encoded in {@code text} if it matches one of
     * Damai's on-sale time formats and is still in the future (with a little slack for
     * clock skew), else null.
     */
    static Long parseFutureMillis(String text, long nowMillis) {
        if (text == null) return null;

        Long t = tryDayOffset(text, TODAY, 0, nowMillis);
        if (t != null) return t;
        t = tryDayOffset(text, TOMORROW, 1, nowMillis);
        if (t != null) return t;
        t = tryDayOffset(text, DAY_AFTER_TOMORROW, 2, nowMillis);
        if (t != null) return t;
        t = tryMonthDay(nowMillis, MONTH_DAY.matcher(text));
        if (t != null) return t;
        t = tryMonthDay(nowMillis, DASHED.matcher(text));
        if (t != null) return t;
        return tryMonthDay(nowMillis, BARE_MONTH_DAY.matcher(text));
    }

    private static Long tryDayOffset(String text, Pattern p, int dayOffset, long now) {
        Matcher m = p.matcher(text);
        if (!m.find()) return null;
        int h = Integer.parseInt(m.group(1));
        int min = Integer.parseInt(m.group(2));
        if (h > 23 || min > 59) return null;
        Calendar c = Calendar.getInstance(TZ);
        c.setTimeInMillis(now);
        c.add(Calendar.DAY_OF_YEAR, dayOffset);
        c.set(Calendar.HOUR_OF_DAY, h);
        c.set(Calendar.MINUTE, min);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return accept(c.getTimeInMillis(), now);
    }

    private static Long tryMonthDay(long now, Matcher m) {
        if (!m.find()) return null;
        int month = Integer.parseInt(m.group(1));
        int day = Integer.parseInt(m.group(2));
        int h = Integer.parseInt(m.group(3));
        int min = Integer.parseInt(m.group(4));
        if (month < 1 || month > 12 || day < 1 || day > 31 || h > 23 || min > 59) return null;

        Calendar c = Calendar.getInstance(TZ);
        c.setTimeInMillis(now);
        int year = c.get(Calendar.YEAR);
        c.clear();
        c.setTimeZone(TZ);
        c.set(year, month - 1, day, h, min, 0);
        long t = c.getTimeInMillis();
        // Damai never renders a date more than ~12h in the past as an upcoming on-sale
        // time, so if it lands in the past the show must roll over into next year.
        if (t < now - 12L * 3600_000L) {
            c.set(Calendar.YEAR, year + 1);
            t = c.getTimeInMillis();
        }
        return accept(t, now);
    }

    private static Long accept(long t, long now) {
        return t >= now - 60_000L ? t : null;
    }
}
