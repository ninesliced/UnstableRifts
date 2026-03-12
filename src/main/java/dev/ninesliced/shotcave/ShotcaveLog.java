package dev.ninesliced.shotcave;

import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Central logging infrastructure for the Shotcave plugin.
 *
 * <p>
 * Provides hierarchical module loggers that integrate with Hytale's
 * {@link HytaleLogger} backend. All loggers are children of the root
 * {@code "Shotcave"} logger, producing output like:
 *
 * <pre>
 *   [Shotcave]           — root logger
 *   [Shotcave][Dungeon]  — module logger
 *   [Shotcave][Pickup]   — module logger
 * </pre>
 *
 * <h3>Structured logging</h3>
 * <p>
 * The {@link #log} family of methods appends key-value pairs to the
 * message in a consistent format:
 *
 * <pre>
 * ShotcaveLog.log(LOG, Level.INFO, "Player joined", "uuid", uuid, "world", worldName);
 * // output: "Player joined | uuid=550e8400-... world=overworld"
 * </pre>
 *
 * <h3>Usage</h3>
 *
 * <pre>
 * // In a class:
 * private static final HytaleLogger LOG = ShotcaveLog.forClass(MyClass.class);
 *
 * // Simple log via the logger:
 * LOG.at(Level.INFO).log("Generation started");
 *
 * // Structured log via helpers:
 * ShotcaveLog.info(LOG, "Connected", "uuid", uuid);
 * ShotcaveLog.warn(LOG, e, "Failed to parse", "path", path);
 * </pre>
 */
public final class ShotcaveLog {

    private static final String ROOT_NAME = "Shotcave";
    private static final String FIELD_SEPARATOR = " | ";
    private static final HytaleLogger ROOT = HytaleLogger.get(ROOT_NAME);
    private static final ConcurrentHashMap<String, HytaleLogger> MODULE_CACHE = new ConcurrentHashMap<>();

    private ShotcaveLog() {
    }

    /**
     * Returns the root {@code "Shotcave"} logger.
     */
    @Nonnull
    public static HytaleLogger root() {
        return ROOT;
    }

    /**
     * Returns a hierarchical sub-logger for the given module name.
     * The resulting logger name will be {@code "Shotcave][<module>"} which
     * renders as {@code [Shotcave][<module>]} in the console.
     *
     * <p>
     * Loggers are cached — calling this repeatedly with the same name
     * returns the same instance.
     *
     * @param module short module name, e.g. {@code "Dungeon"}, {@code "Pickup"}
     * @return a cached {@link HytaleLogger} for the module
     */
    @Nonnull
    public static HytaleLogger forModule(@Nonnull String module) {
        return MODULE_CACHE.computeIfAbsent(module, ROOT::getSubLogger);
    }

    /**
     * Returns a two-level sub-logger: {@code [Shotcave][parent][child]}.
     *
     * @param parent top-level module name
     * @param child  sub-module name
     * @return a cached {@link HytaleLogger} for the sub-module
     */
    @Nonnull
    public static HytaleLogger forModule(@Nonnull String parent, @Nonnull String child) {
        String key = parent + "][" + child;
        return MODULE_CACHE.computeIfAbsent(key, k -> forModule(parent).getSubLogger(child));
    }

    /**
     * Returns a logger named after the provided class' simple name, scoped under
     * root.
     * Convenience replacement for repeatedly calling {@link #forModule(String)}
     * with
     * class simple names.
     */
    @Nonnull
    public static HytaleLogger forClass(@Nonnull Class<?> cls) {
        return forModule(cls.getSimpleName());
    }

    /**
     * Returns whether the provided logger will accept messages for the given level.
     * This is a tiny wrapper for convenience and to make the call site clearer.
     */
    public static boolean isEnabled(@Nonnull HytaleLogger logger, @Nonnull Level level) {
        return logger.at(level).isEnabled();
    }

    // Convenience level helpers that use the structured logging formatter.
    // These simply delegate to the more generic `log(...)` methods.

    public static void info(@Nonnull HytaleLogger logger, @Nullable String message, @Nonnull Object... keyValues) {
        log(logger, Level.INFO, message, keyValues);
    }

    public static void info(@Nonnull HytaleLogger logger, @Nonnull Throwable cause, @Nullable String message,
            @Nonnull Object... keyValues) {
        log(logger, Level.INFO, cause, message, keyValues);
    }

    public static void warn(@Nonnull HytaleLogger logger, @Nullable String message, @Nonnull Object... keyValues) {
        log(logger, Level.WARNING, message, keyValues);
    }

    public static void warn(@Nonnull HytaleLogger logger, @Nonnull Throwable cause, @Nullable String message,
            @Nonnull Object... keyValues) {
        log(logger, Level.WARNING, cause, message, keyValues);
    }

    public static void error(@Nonnull HytaleLogger logger, @Nullable String message, @Nonnull Object... keyValues) {
        log(logger, Level.SEVERE, message, keyValues);
    }

    public static void error(@Nonnull HytaleLogger logger, @Nonnull Throwable cause, @Nullable String message,
            @Nonnull Object... keyValues) {
        log(logger, Level.SEVERE, cause, message, keyValues);
    }

    public static void fine(@Nonnull HytaleLogger logger, @Nullable String message, @Nonnull Object... keyValues) {
        log(logger, Level.FINE, message, keyValues);
    }

    public static void fine(@Nonnull HytaleLogger logger, @Nonnull Throwable cause, @Nullable String message,
            @Nonnull Object... keyValues) {
        log(logger, Level.FINE, cause, message, keyValues);
    }

    /**
     * Logs a structured message with key-value context fields.
     *
     * <p>
     * Fields are appended after the human-readable message separated by
     * {@code " | "}. Keys and values alternate; an odd trailing key is
     * paired with {@code "null"}. Values containing whitespace are quoted.
     *
     * @param logger    logger to write to
     * @param level     log level
     * @param message   human-readable message (may be {@code null})
     * @param keyValues alternating key/value pairs
     */
    public static void log(@Nonnull HytaleLogger logger,
            @Nonnull Level level,
            @Nullable String message,
            @Nonnull Object... keyValues) {
        if (!isEnabled(logger, level)) {
            return;
        }
        logger.at(level).log("%s", formatStructured(message, keyValues));
    }

    /**
     * Logs a structured message with an attached exception.
     *
     * @param logger    logger to write to
     * @param level     log level
     * @param cause     exception to attach
     * @param message   human-readable message
     * @param keyValues alternating key/value pairs
     */
    public static void log(@Nonnull HytaleLogger logger,
            @Nonnull Level level,
            @Nonnull Throwable cause,
            @Nullable String message,
            @Nonnull Object... keyValues) {
        if (!isEnabled(logger, level)) {
            return;
        }
        logger.at(level).withCause(cause).log("%s", formatStructured(message, keyValues));
    }

    public static void log(@Nonnull HytaleLogger logger,
            @Nonnull Level level,
            @Nullable String message,
            @Nonnull Map<String, ?> fields) {
        if (!isEnabled(logger, level)) {
            return;
        }
        logger.at(level).log("%s", formatStructured(message, fields));
    }

    @Nonnull
    public static String formatStructured(@Nullable String message, @Nonnull Object... keyValues) {
        if (keyValues.length == 0) {
            return message != null ? message : "";
        }

        StringBuilder sb = new StringBuilder(64);
        if (message != null && !message.isEmpty()) {
            sb.append(message);
            sb.append(FIELD_SEPARATOR);
        }

        for (int i = 0; i < keyValues.length; i += 2) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(String.valueOf(keyValues[i]));
            sb.append('=');
            String value = (i + 1 < keyValues.length)
                    ? String.valueOf(keyValues[i + 1])
                    : "null";
            appendValue(sb, value);
        }

        return sb.toString();
    }

    @Nonnull
    public static String formatStructured(@Nullable String message, @Nonnull Map<String, ?> fields) {
        if (fields.isEmpty()) {
            return message != null ? message : "";
        }

        StringBuilder sb = new StringBuilder(64);
        if (message != null && !message.isEmpty()) {
            sb.append(message);
            sb.append(FIELD_SEPARATOR);
        }

        boolean first = true;
        for (Map.Entry<String, ?> entry : fields.entrySet()) {
            if (!first) {
                sb.append(' ');
            }
            first = false;
            sb.append(entry.getKey());
            sb.append('=');
            appendValue(sb, String.valueOf(entry.getValue()));
        }

        return sb.toString();
    }

    private static void appendValue(@Nonnull StringBuilder sb, @Nonnull String value) {
        if (value.isEmpty()) {
            sb.append("\"\"");
        } else if (needsQuoting(value)) {
            sb.append('"');
            sb.append(value);
            sb.append('"');
        } else {
            sb.append(value);
        }
    }

    private static boolean needsQuoting(@Nonnull String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c <= ' ' || c == '"') {
                return true;
            }
        }
        return false;
    }
}
