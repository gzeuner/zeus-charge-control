package de.zeus.power.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class LogFilter {

    private static final Logger logger = LoggerFactory.getLogger(LogFilter.class);

    // Log level constants
    public static final String LOG_LEVEL_INFO = "INFO";
    public static final String LOG_LEVEL_WARN = "WARN";
    public static final String LOG_LEVEL_ERROR = "ERROR";
    public static final String LOG_LEVEL_DEBUG = "DEBUG";

    // Cache for log messages
    private static final ConcurrentHashMap<String, Long> logCache = new ConcurrentHashMap<>();
    private static final long CACHE_DURATION_MS = TimeUnit.MINUTES.toMillis(5); // Cache TTL of 5 minutes

    /**
     * Logs a message if it has not been logged recently.
     *
     * @param level The log level (INFO, WARN, ERROR, DEBUG).
     * @param message The message to be logged.
     */
    public static void log(String level, String message) {
        long now = System.currentTimeMillis();

        // Remove old entries from the cache
        logCache.entrySet().removeIf(entry -> now - entry.getValue() > CACHE_DURATION_MS);

        // Check whether the message has already been logged
        if (!logCache.containsKey(message)) {
            logCache.put(message, now);

            // Log the message based on the level
            switch (level.toUpperCase()) {
                case LOG_LEVEL_INFO:
                    logger.info(message);
                    break;
                case LOG_LEVEL_WARN:
                    logger.warn(message);
                    break;
                case LOG_LEVEL_ERROR:
                    logger.error(message);
                    break;
                case LOG_LEVEL_DEBUG:
                default:
                    logger.debug(message);
                    break;
            }
        } else {
            logger.debug("Filtered duplicate log: {}", message);
        }
    }

    /**
     * Logs an INFO message with formatted arguments.
     *
     * @param message The message template with placeholders.
     * @param args    The arguments to format the message.
     */
    public static void logInfo(String message, Object... args) {
        log(LOG_LEVEL_INFO, String.format(message, args));
    }

    /**
     * Logs a WARN message with formatted arguments.
     *
     * @param message The message template with placeholders.
     * @param args    The arguments to format the message.
     */
    public static void logWarn(String message, Object... args) {
        log(LOG_LEVEL_WARN, String.format(message, args));
    }

    /**
     * Logs an ERROR message with formatted arguments.
     *
     * @param message The message template with placeholders.
     * @param args    The arguments to format the message.
     */
    public static void logError(String message, Object... args) {
        log(LOG_LEVEL_ERROR, String.format(message, args));
    }

    /**
     * Logs a DEBUG message with formatted arguments.
     *
     * @param message The message template with placeholders.
     * @param args    The arguments to format the message.
     */
    public static void logDebug(String message, Object... args) {
        log(LOG_LEVEL_DEBUG, String.format(message, args));
    }
}
