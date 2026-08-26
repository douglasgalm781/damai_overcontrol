package io.mtop.overcontrol;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Records the last uncaught exception to a file so it can be read back in the app.
 *
 * <p>Exists because the useful diagnosis for "Overcontrol keeps stopping" is a stack
 * trace, and the phone showing that dialog is usually not the machine with adb attached —
 * logcat is gone by the time anyone looks. The trace is written to the app's private
 * files dir and shown by {@link MainActivity}, where it can simply be read off the screen.
 *
 * <p>The previously-installed handler is always chained, so the app still dies exactly as
 * it would have; this only leaves a note behind first.
 */
final class CrashLog {

    private static final String FILE_NAME = "last_crash.txt";
    private static boolean installed = false;

    private CrashLog() {}

    /** Idempotent — safe to call from every entry point. */
    static synchronized void install(Context context) {
        if (installed) return;
        installed = true;

        Context app = context.getApplicationContext();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                write(app, thread, throwable);
            } catch (Throwable ignored) {
                // Never let the recording of a crash get in the way of the crash itself.
            }
            if (previous != null) previous.uncaughtException(thread, throwable);
        });
    }

    private static void write(Context context, Thread thread, Throwable throwable) throws Exception {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
        pw.println("Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        pw.println(Build.MANUFACTURER + " " + Build.MODEL);
        pw.println("thread: " + thread.getName());
        pw.println();
        throwable.printStackTrace(pw);
        pw.flush();

        // java.io, not java.nio.file — Files/Path are API 26 and this app supports 24.
        File file = new File(context.getFilesDir(), FILE_NAME);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(sw.toString().getBytes(StandardCharsets.UTF_8));
        }
        Log.e(NodeActions.TAG, "uncaught exception recorded to " + file, throwable);
    }

    /** The last recorded crash, or null if the app hasn't crashed since it was cleared. */
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
    }
}
