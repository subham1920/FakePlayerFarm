package com.plugin.afkdummy.util;

/**
 * Utility class for time formatting and conversion operations.
 * All methods are static; this class cannot be instantiated.
 */
public final class TimeUtil {

    private TimeUtil() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    /**
     * Formats a duration in milliseconds to HH:mm:ss format.
     *
     * @param millis duration in milliseconds
     * @return formatted string in HH:mm:ss format
     */
    public static String formatDuration(long millis) {
        if (millis <= 0) return "00:00:00";
        long totalSeconds = millis / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }

    /**
     * Formats a duration in milliseconds to a human-readable long format.
     * Example: "2 hours, 30 minutes, 15 seconds"
     *
     * @param millis duration in milliseconds
     * @return human-readable duration string
     */
    public static String formatDurationLong(long millis) {
        if (millis <= 0) return "0 seconds";
        long totalSeconds = millis / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        StringBuilder sb = new StringBuilder();
        if (hours > 0) {
            sb.append(hours).append(hours == 1 ? " hour" : " hours");
        }
        if (minutes > 0) {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(minutes).append(minutes == 1 ? " minute" : " minutes");
        }
        if (seconds > 0 && hours == 0) {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(seconds).append(seconds == 1 ? " second" : " seconds");
        }
        return sb.isEmpty() ? "0 seconds" : sb.toString();
    }

    /**
     * Converts hours to milliseconds.
     *
     * @param hours number of hours
     * @return equivalent milliseconds
     */
    public static long hoursToMillis(int hours) {
        return (long) hours * 3600L * 1000L;
    }

    /**
     * Converts milliseconds to server ticks (1 tick = 50ms).
     *
     * @param ms milliseconds
     * @return equivalent server ticks
     */
    public static long millisToTicks(long ms) {
        return ms / 50L;
    }

    /**
     * Converts server ticks to milliseconds (1 tick = 50ms).
     *
     * @param ticks server ticks
     * @return equivalent milliseconds
     */
    public static long ticksToMillis(long ticks) {
        return ticks * 50L;
    }
}
