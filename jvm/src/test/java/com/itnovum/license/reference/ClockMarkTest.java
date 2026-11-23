package com.itnovum.license.reference;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClockMarkTest {

    private static final Path VECTORS = Path.of("../vectors");
    private static final Duration SKEW = Duration.ofMinutes(5);
    private static final Instant NOW = Instant.parse("2026-01-15T00:00:00Z");

    private static String vector(String name) throws IOException {
        return Files.readString(VECTORS.resolve("license-files/" + name + ".lic"), StandardCharsets.UTF_8);
    }

    private static LicenseFileVerifier verifier(ClockMark mark) throws IOException {
        return new LicenseFileVerifier(ProductKeys.parse(Files.readString(VECTORS.resolve("keys.json"), StandardCharsets.UTF_8)), UUID.fromString("0192e4a0-0000-7000-8000-000000000001"), mark, SKEW);
    }

    @Test
    void aClockSetBackBehindTheMarkIsRefused() throws IOException {
        var verifier = verifier(new ClockMark.InMemory());

        assertThat(verifier.verify(vector("valid"), NOW).outcome()).isEqualTo(Outcome.VALID);
        assertThat(verifier.verify(vector("valid"), NOW.minus(SKEW)).outcome()).isEqualTo(Outcome.VALID);
        assertThat(verifier.verify(vector("valid"), NOW.minus(Duration.ofDays(2))).outcome()).isEqualTo(Outcome.CLOCK_BEHIND);
    }

    @Test
    void anExpiredFileStaysExpiredWhenTheClockIsSetBack() throws IOException {
        var verifier = verifier(new ClockMark.InMemory());

        assertThat(verifier.verify(vector("expired"), NOW).outcome()).isEqualTo(Outcome.EXPIRED);
        assertThat(verifier.verify(vector("expired"), Instant.parse("2025-11-15T00:00:00Z")).outcome()).isEqualTo(Outcome.CLOCK_BEHIND);
    }

    /**
     * The not-started vector's license starts ten days after its now.
     */
    @Test
    void aFileWaitingForItsLicenseBecomesValidAndAClockSetBackBeforeTheStartIsRefused() throws IOException {
        var verifier = verifier(new ClockMark.InMemory());
        var started = NOW.plus(Duration.ofDays(11));

        assertThat(verifier.verify(vector("not-started"), NOW).outcome()).isEqualTo(Outcome.NOT_YET_VALID);
        assertThat(verifier.verify(vector("not-started"), started).outcome()).isEqualTo(Outcome.VALID);
        assertThat(verifier.verify(vector("not-started"), NOW).outcome()).isEqualTo(Outcome.CLOCK_BEHIND);
    }

    @Test
    void aFileThatDidntVerifyRaisesTheMarkOnlyToTheTimeSeen() throws IOException {
        var mark = new ClockMark.InMemory();

        verifier(mark).verify(vector("issued-in-future"), NOW);

        assertThat(mark.mark()).isEqualTo(NOW);
    }

    @Test
    void theMarkNeverMovesBack() {
        var mark = new ClockMark.InMemory();

        mark.raise(NOW);
        mark.raise(NOW.minusSeconds(1));

        assertThat(mark.mark()).isEqualTo(NOW);
    }

    @Test
    void aMarkKeptInAFileSurvivesANewStore(@TempDir Path folder) {
        var file = folder.resolve("clock-mark");

        new ClockMark.InFile(file).raise(NOW);

        assertThat(new ClockMark.InFile(file).mark()).isEqualTo(NOW);
    }

    @Test
    void anUnreadableFileStartsFromNothing(@TempDir Path folder) throws IOException {
        var file = Files.writeString(folder.resolve("clock-mark"), "not a time", StandardCharsets.UTF_8);

        assertThat(new ClockMark.InFile(file).mark()).isEqualTo(Instant.EPOCH);
    }
}
