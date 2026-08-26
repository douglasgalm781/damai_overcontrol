package io.mtop.overcontrol;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Keeps the last Damai screen that the scanner could not identify, so it can be read back
 * in {@link MainActivity}.
 *
 * <p>Exists because the detection marker lives in text that Damai may or may not expose,
 * and the only way to know which is to look at the real page on the real account. logcat
 * is not an option — the phone holding the reservation is rarely the one with adb
 * attached — so the app records it and shows it to the user instead.
 */
final class ScreenLog {

    private static final String FILE_NAME = "last_screen.txt";
    /** Rewriting on every content change would thrash the disk; Damai ticks every second. */
    private static final long MIN_INTERVAL_MS = 3000L;

    private static long lastWriteAt = 0L;

    private ScreenLog() {}

    /** Records a screen, at most once every {@link #MIN_INTERVAL_MS}. */
    static void record(Context context, long now, String body) {
        if (now - lastWriteAt < MIN_INTERVAL_MS) return;
        lastWriteAt = now;
        File file = new File(context.getFilesDir(), FILE_NAME);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(NodeActions.TAG, "could not record screen", e);
        }
    }

    /** The last recorded screen, or null if nothing has been recorded yet. */
    static String read(Context context) {
        File file = new File(context.getFilesDir(), FILE_NAME);
        if (!file.isFile()) return null;
        try (FileInputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = in.read(chunk)) != -1) buf.write(chunk, 0, read);
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    static void clear(Context context) {
        new File(context.getFilesDir(), FILE_NAME).delete();
        lastWriteAt = 0L;
    }
}
