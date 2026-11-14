package io.github.janaskarol.miev;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Zapisuje ostatni nieobsluzony wyjatek do pliku w pamieci aplikacji.
 * Dzieki temu blad da sie odczytac w zakladce INNE, bez podlaczania telefonu
 * do komputera. Plik jest nadpisywany przy kolejnym bledzie.
 */
final class CrashLog {

    private static final String FILE_NAME = "last_crash.txt";

    private CrashLog() {
    }

    static void install(Context context) {
        final Context app = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            write(app, error);
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    static void write(Context context, Throwable error) {
        try {
            StringWriter sw = new StringWriter();
            error.printStackTrace(new PrintWriter(sw));
            String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
            String text = stamp + "\n" + sw;
            try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), FILE_NAME))) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
            // nie ma gdzie zglosic - nie wolno zepsuc obslugi bledu
        }
    }

    /** Tresc ostatniego bledu (skrocona) albo null. */
    static String last(Context context) {
        File file = new File(context.getFilesDir(), FILE_NAME);
        if (!file.exists()) return null;
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            byte[] buf = new byte[Math.min(1200, (int) file.length())];
            int n = in.read(buf);
            return n > 0 ? new String(buf, 0, n, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            return null;
        }
    }

    static void clear(Context context) {
        new File(context.getFilesDir(), FILE_NAME).delete();
    }
}
