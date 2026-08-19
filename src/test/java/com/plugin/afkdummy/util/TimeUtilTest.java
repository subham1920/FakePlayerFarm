package com.plugin.afkdummy.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TimeUtil Tests")
class TimeUtilTest {

    @Test
    @DisplayName("Constructor throws UnsupportedOperationException")
    void testConstructorThrows() throws Exception {
        Constructor<TimeUtil> constructor = TimeUtil.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        InvocationTargetException ex = assertThrows(InvocationTargetException.class, constructor::newInstance);
        assertTrue(ex.getCause() instanceof UnsupportedOperationException);
    }

    @Nested
    @DisplayName("formatDuration(long millis)")
    class FormatDurationTests {

        static Stream<Arguments> provideComprehensiveDurations() {
            List<Arguments> args = new ArrayList<>();
            // Negative & zero
            args.add(Arguments.of(-100000L, "00:00:00"));
            args.add(Arguments.of(-1L, "00:00:00"));
            args.add(Arguments.of(0L, "00:00:00"));
            args.add(Arguments.of(500L, "00:00:00"));
            args.add(Arguments.of(999L, "00:00:00"));

            // 0 to 120 seconds
            for (int s = 1; s <= 120; s++) {
                int h = s / 3600;
                int m = (s % 3600) / 60;
                int sec = s % 60;
                args.add(Arguments.of((long) s * 1000L, String.format("%02d:%02d:%02d", h, m, sec)));
            }

            // 1 to 100 minutes
            for (int m = 1; m <= 100; m++) {
                int h = m / 60;
                int min = m % 60;
                args.add(Arguments.of((long) m * 60 * 1000L, String.format("%02d:%02d:00", h, min)));
            }

            // 1 to 100 hours
            for (int h = 1; h <= 100; h++) {
                args.add(Arguments.of((long) h * 3600 * 1000L, String.format("%02d:00:00", h)));
            }

            // Complex combined (hours + minutes + seconds)
            for (int h = 1; h <= 5; h++) {
                for (int m = 1; m <= 10; m++) {
                    for (int s = 1; s <= 5; s++) {
                        long totalSec = h * 3600L + m * 60L + s;
                        args.add(Arguments.of(totalSec * 1000L, String.format("%02d:%02d:%02d", h, m, s)));
                    }
                }
            }
            return args.stream();
        }

        @ParameterizedTest(name = "[{index}] millis={0} -> expected={1}")
        @MethodSource("provideComprehensiveDurations")
        void testFormatDuration(long millis, String expected) {
            assertEquals(expected, TimeUtil.formatDuration(millis));
        }
    }

    @Nested
    @DisplayName("formatDurationLong(long millis)")
    class FormatDurationLongTests {

        static Stream<Arguments> provideLongDurations() {
            List<Arguments> args = new ArrayList<>();
            args.add(Arguments.of(0L, "0 seconds"));
            args.add(Arguments.of(-500L, "0 seconds"));
            args.add(Arguments.of(-10000L, "0 seconds"));

            // 1 to 59 seconds
            for (int s = 1; s < 60; s++) {
                args.add(Arguments.of((long) s * 1000L, s + (s == 1 ? " second" : " seconds")));
            }

            // 1 to 59 minutes exact
            for (int m = 1; m < 60; m++) {
                args.add(Arguments.of((long) m * 60 * 1000L, m + (m == 1 ? " minute" : " minutes")));
            }

            // 1 to 50 hours exact
            for (int h = 1; h <= 50; h++) {
                args.add(Arguments.of((long) h * 3600 * 1000L, h + (h == 1 ? " hour" : " hours")));
            }

            // Combined minutes and seconds (without hours)
            for (int m = 1; m <= 10; m++) {
                for (int s = 1; s <= 5; s++) {
                    long ms = (m * 60L + s) * 1000L;
                    String mStr = m + (m == 1 ? " minute" : " minutes");
                    String sStr = s + (s == 1 ? " second" : " seconds");
                    args.add(Arguments.of(ms, mStr + ", " + sStr));
                }
            }

            // Combined hours and minutes (without seconds in long format when hours > 0)
            for (int h = 1; h <= 10; h++) {
                for (int m = 1; m <= 5; m++) {
                    long ms = (h * 3600L + m * 60L) * 1000L;
                    String hStr = h + (h == 1 ? " hour" : " hours");
                    String mStr = m + (m == 1 ? " minute" : " minutes");
                    args.add(Arguments.of(ms, hStr + ", " + mStr));
                }
            }

            return args.stream();
        }

        @ParameterizedTest(name = "[{index}] millis={0} -> expected={1}")
        @MethodSource("provideLongDurations")
        void testFormatDurationLong(long millis, String expected) {
            assertEquals(expected, TimeUtil.formatDurationLong(millis));
        }
    }

    @Nested
    @DisplayName("Conversion Methods")
    class ConversionTests {

        static Stream<Arguments> provideHoursConversions() {
            List<Arguments> list = new ArrayList<>();
            for (int h = 0; h <= 100; h++) {
                list.add(Arguments.of(h, (long) h * 3600_000L));
            }
            return list.stream();
        }

        @ParameterizedTest(name = "hours={0} -> ms={1}")
        @MethodSource("provideHoursConversions")
        void testHoursToMillis(int hours, long expectedMs) {
            assertEquals(expectedMs, TimeUtil.hoursToMillis(hours));
        }

        static Stream<Arguments> provideTicksConversions() {
            List<Arguments> list = new ArrayList<>();
            for (int t = 0; t <= 100; t++) {
                list.add(Arguments.of((long) t, (long) t * 50L));
            }
            return list.stream();
        }

        @ParameterizedTest(name = "ticks={0} -> ms={1}")
        @MethodSource("provideTicksConversions")
        void testTicksToMillis(long ticks, long expectedMs) {
            assertEquals(expectedMs, TimeUtil.ticksToMillis(ticks));
            assertEquals(ticks, TimeUtil.millisToTicks(expectedMs));
        }

        @ParameterizedTest
        @ValueSource(longs = {0L, 25L, 49L, 50L, 51L, 99L, 100L, 1000L, 50000L, 3600000L})
        void testMillisToTicksBoundaries(long ms) {
            assertEquals(ms / 50L, TimeUtil.millisToTicks(ms));
        }
    }
}
