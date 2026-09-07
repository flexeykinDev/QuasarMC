package dev.quasar.util;

import java.io.PrintStream;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Minimal, allocation-light console logger.
 *
 * <p>Deliberately not SLF4J/Log4j: the tick engine logs from many threads at once and the only
 * thing that actually matters here is that the thread name is visible, so you can see which
 * region produced a line.
 */
public final class Log {

    public enum Level { TRACE, DEBUG, INFO, WARN, ERROR }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final PrintStream OUT = System.out;
    private static final boolean COLOR = !Boolean.getBoolean("quasar.noColor");

    private static volatile Level threshold = Level.INFO;

    private Log() {}

    public static void setLevel(Level level) {
        threshold = level;
    }

    public static Level level() {
        return threshold;
    }

    public static void trace(String fmt, Object... args) { log(Level.TRACE, fmt, args); }

    public static void debug(String fmt, Object... args) { log(Level.DEBUG, fmt, args); }

    public static void info(String fmt, Object... args) { log(Level.INFO, fmt, args); }

    public static void warn(String fmt, Object... args) { log(Level.WARN, fmt, args); }

    public static void error(String fmt, Object... args) { log(Level.ERROR, fmt, args); }

    public static void error(String msg, Throwable t) {
        log(Level.ERROR, "%s", msg);
        synchronized (OUT) {
            t.printStackTrace(OUT);
        }
    }

    private static void log(Level level, String fmt, Object... args) {
        if (level.ordinal() < threshold.ordinal()) {
            return;
        }
        // Locale.ROOT, not the default locale: on a machine set to a comma-decimal locale the
        // default turns "0.5" into "0,5", which makes coordinates and timings misread as lists.
        String message = args.length == 0 ? fmt : String.format(Locale.ROOT, fmt, args);
        StringBuilder sb = new StringBuilder(message.length() + 64);
        if (COLOR) {
            sb.append(color(level));
        }
        sb.append('[').append(TIME.format(LocalTime.now())).append(']');
        sb.append(" [").append(Thread.currentThread().getName()).append('/').append(level).append("] ");
        sb.append(message);
        if (COLOR) {
            sb.append("[0m");
        }
        synchronized (OUT) {
            OUT.println(sb);
        }
    }

    private static String color(Level level) {
        return switch (level) {
            case TRACE -> "[90m";
            case DEBUG -> "[36m";
            case INFO -> "[0m";
            case WARN -> "[33m";
            case ERROR -> "[31m";
        };
    }
}
