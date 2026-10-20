package de.bsi.secvisogram.csafmigrator;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ProgressTest {

    private static final long SECOND = 1_000_000_000L;

    private final AtomicLong clock = new AtomicLong();
    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(captured, true, StandardCharsets.UTF_8);

    private String output() {
        return captured.toString(StandardCharsets.UTF_8);
    }

    @Test
    void printsNothingBeforeTheIntervalHasPassed() {
        Progress progress = new Progress("Validated", 1000, true, 10 * SECOND, out, clock::get);
        progress.advance(0);

        clock.set(9 * SECOND);
        progress.advance(200);

        assertEquals("", output());
    }

    @Test
    void printsCountShareRateAndTimeLeftOnceTheIntervalHasPassed() {
        Progress progress = new Progress("Validated", 1000, true, 10 * SECOND, out, clock::get);
        progress.advance(0);

        clock.set(10 * SECOND);
        progress.advance(250);

        // 250 of 1,000 in 10 s is 25 per second, so the remaining 750 take 30 s.
        assertEquals("  Validated: 250 of 1,000 (25.0%), 25/s, about 30 s left\n", output().replace("\r\n", "\n"));
    }

    @Test
    void theClockStartsWithTheFirstAdvanceNotWithTheObject() {
        Progress progress = new Progress("Validated", 1000, true, 10 * SECOND, out, clock::get);

        // A long preparation before the first document must not count towards the rate.
        clock.set(500 * SECOND);
        progress.advance(1);
        clock.set(510 * SECOND);
        progress.advance(100);

        // 100 documents in the 10 s since the first one: 10 per second, 899 left, about 90 s.
        assertEquals("  Validated: 101 of 1,000 (10.1%), 10/s, about 1 min 30 s left\n", output().replace("\r\n", "\n"));
    }

    @Test
    void printsAtMostOncePerInterval() {
        Progress progress = new Progress("Validated", 1000, true, 10 * SECOND, out, clock::get);
        progress.advance(0);

        clock.set(10 * SECOND);
        progress.advance(100);
        clock.set(12 * SECOND);
        progress.advance(100);
        clock.set(15 * SECOND);
        progress.advance(100);
        clock.set(21 * SECOND);
        progress.advance(100);

        assertEquals(2, output().lines().count());
    }

    @Test
    void printsNothingWhenSwitchedOff() {
        Progress progress = new Progress("Validated", 1000, false, 10 * SECOND, out, clock::get);
        progress.advance(0);

        clock.set(60 * SECOND);
        progress.advance(500);

        assertEquals("", output());
    }

    @Test
    void leavesOutThePercentageWhenTheTotalIsUnknown() {
        Progress progress = new Progress("Validated", 0, true, 10 * SECOND, out, clock::get);
        progress.advance(0);

        clock.set(10 * SECOND);
        progress.advance(250);

        String line = output().strip();
        assertEquals("Validated: 250, 25/s", line.strip());
        assertFalse(line.contains("%"));
        assertFalse(line.contains("left"));
    }

    @Test
    void formatsDurationsAndCounts() {
        assertEquals("45 s", Progress.duration(45));
        assertEquals("1 min 30 s", Progress.duration(90));
        assertEquals("3 min", Progress.duration(180));
        assertEquals("25 min", Progress.duration(1500));
        assertEquals("1 h 5 min", Progress.duration(3900));
        assertEquals("481,746", Progress.count(481_746));
        assertEquals("1 min", Progress.duration(59.6));
    }
}
