package de.zeus.power.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class LogFilter {

    // Log level constants
    public static final String LOG_LEVEL_INFO = "INFO";
    public static final String LOG_LEVEL_WARN = "WARN";
    public static final String LOG_LEVEL_ERROR = "ERROR";
    public static final String LOG_LEVEL_DEBUG = "DEBUG";

    // Cache for log messages with TTL (5 minutes)
    private static final ConcurrentHashMap<String, Long> logCache = new ConcurrentHashMap<>();
    private static final long CACHE_DURATION_MS = TimeUnit.MINUTES.toMillis(5);

    /**
     * Logs a message with the specified level, using the logger of the calling class.
     * Prevents duplicate logs within the cache duration.
     *
     * @param callingClass The class from which the log originates.
     * @param level        The log level (INFO, WARN, ERROR, DEBUG).
     * @param message      The message template with {} placeholders.
     * @param args         The arguments to replace the placeholders.
     */
    public static void log(Class<?> callingClass, String level, String message, Object... args) {
        Logger logger = LoggerFactory.getLogger(callingClass);
        long now = System.currentTimeMillis();

        // Remove expired cache entries
        logCache.entrySet().removeIf(entry -> now - entry.getValue() > CACHE_DURATION_MS);

        // Generate a unique cache key based on class, message, and arguments
        String cacheKey = generateCacheKey(callingClass, message, args);
        if (!logCache.containsKey(cacheKey)) {
            logCache.put(cacheKey, now);

            // Log based on level
            switch (level.toUpperCase()) {
                case LOG_LEVEL_INFO:
                    logger.info(message, args);
                    break;
                case LOG_LEVEL_WARN:
                    logger.warn(message, args);
                    break;
                case LOG_LEVEL_ERROR:
                    logger.error(message, args);
                    break;
                case LOG_LEVEL_DEBUG:
                default:
                    logger.debug(message, args);
                    break;
            }
        } else {
            logger.debug("Filtered duplicate log: {}", message);
        }
    }

    // Convenience methods for specific log levels
    public static void logInfo(Class<?> callingClass, String message, Object... args) {
        log(callingClass, LOG_LEVEL_INFO, message, args);
    }

    public static void logWarn(Class<?> callingClass, String message, Object... args) {
        log(callingClass, LOG_LEVEL_WARN, message, args);
    }

    public static void logError(Class<?> callingClass, String message, Object... args) {
        log(callingClass, LOG_LEVEL_ERROR, message, args);
    }

    public static void logDebug(Class<?> callingClass, String message, Object... args) {
        log(callingClass, LOG_LEVEL_DEBUG, message, args);
    }

    /**
     * Generates a unique cache key based on class, message, and arguments.
     *
     * @param callingClass The class requesting the log.
     * @param message      The log message.
     * @param args         The arguments for the message.
     * @return A unique string key for caching.
     */
    private static String generateCacheKey(Class<?> callingClass, String message, Object... args) {
        StringBuilder keyBuilder = new StringBuilder(callingClass.getName()).append(":").append(message);
        if (args != null && args.length > 0) {
            for (Object arg : args) {
                keyBuilder.append(":").append(arg != null ? arg.hashCode() : "null");
            }
        }
        return keyBuilder.toString();
    }

    /**
     * Clears the log cache (for testing or manual reset purposes).
     */
    public static void clearCache() {
        logCache.clear();
        LoggerFactory.getLogger(LogFilter.class).info("Log cache cleared.");
    }
}