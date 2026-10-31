package io.nodemedic.engine;

import java.text.ParseException;
import java.time.Duration;
import java.util.Optional;

public final class Durations {

    private Durations() {
    }

    /**
     * Parses Go-style durations such as "10m" or "30s" (what kubectl uses), which
     * java.time.Duration.parse rejects. Returns empty for null, blank or unparsable input,
     * so the caller decides what to do with a bad policy instead of crashing.
     */
    public static Optional<Duration> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(io.fabric8.kubernetes.api.model.Duration.parse(value.trim()).getDuration());
        } catch (ParseException | RuntimeException e) {
            return Optional.empty();
        }
    }
}
