package com.itnovum.license.reference;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;

/**
 * The latest time this application has seen, kept so that setting the machine's clock back doesn't bring an expired file back to life. It is a defence, not a guarantee: whoever
 * controls the machine can reset it. Keep it somewhere a user doesn't casually edit; the operating system's keystore is best where there is one.
 *
 * <p>
 * A mark must never stop the application. A store that can't be read starts from nothing, and one that can't be written keeps the mark in memory.
 */
public interface ClockMark {

    /**
     * The latest time seen so far, or {@link Instant#EPOCH} before the first.
     */
    Instant mark();

    /**
     * Moves the mark to {@code seen} if that is later. It never moves back.
     */
    void raise(Instant seen);

    /**
     * A mark that lasts as long as the process, for tests and short-lived tools.
     */
    final class InMemory implements ClockMark {

        private Instant mark = Instant.EPOCH;

        @Override
        public synchronized Instant mark() {
            return mark;
        }

        @Override
        public synchronized void raise(Instant seen) {
            if (seen.isAfter(mark)) {
                mark = seen;
            }
        }
    }

    /**
     * A mark kept in a file as an ISO-8601 instant, so that it survives restarts. A raise writes a temporary file and moves it into place, so a crash never leaves half a mark.
     */
    final class InFile implements ClockMark {

        private final Path file;
        private final InMemory current = new InMemory();

        public InFile(Path file) {
            this.file = file;
            try {
                if (Files.exists(file)) {
                    current.raise(Instant.parse(Files.readString(file, StandardCharsets.UTF_8).strip()));
                }
            } catch (IOException | RuntimeException unreadable) {
                // Start from nothing, as the interface describes.
            }
        }

        @Override
        public Instant mark() {
            return current.mark();
        }

        @Override
        public synchronized void raise(Instant seen) {
            if (!seen.isAfter(current.mark())) {
                return;
            }

            current.raise(seen);
            try {
                var written = Files.writeString(file.resolveSibling(file.getFileName() + ".tmp"), seen.toString(), StandardCharsets.UTF_8);
                Files.move(written, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException unwritable) {
                // Keep the mark in memory, as the interface describes.
            }
        }
    }
}
