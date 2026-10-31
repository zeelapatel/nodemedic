package io.nodemedic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class DurationsTest {

    @Test
    void parsesGoStyleDurations() {
        assertThat(Durations.parse("10m")).contains(Duration.ofMinutes(10));
        assertThat(Durations.parse("15m")).contains(Duration.ofMinutes(15));
        assertThat(Durations.parse("30s")).contains(Duration.ofSeconds(30));
        assertThat(Durations.parse("2h")).contains(Duration.ofHours(2));
    }

    @Test
    void parsesCombinedUnits() {
        assertThat(Durations.parse("1h30m")).contains(Duration.ofMinutes(90));
    }

    @Test
    void ignoresSurroundingWhitespace() {
        assertThat(Durations.parse(" 10m ")).contains(Duration.ofMinutes(10));
    }

    @Test
    void javaIso8601FormIsNotWhatTheCrdUses() {
        // documents why Durations exists: java.time rejects "10m"
        org.junit.jupiter.api.Assertions.assertThrows(java.time.format.DateTimeParseException.class,
                () -> Duration.parse("10m"));
    }

    @Test
    void badInputGivesEmpty() {
        assertThat(Durations.parse(null)).isEmpty();
        assertThat(Durations.parse("")).isEmpty();
        assertThat(Durations.parse("   ")).isEmpty();
        assertThat(Durations.parse("soon")).isEmpty();
        assertThat(Durations.parse("10x")).isEmpty();
    }
}
