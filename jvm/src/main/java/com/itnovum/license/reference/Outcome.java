package com.itnovum.license.reference;

/**
 * Why a license file was accepted or refused. Act on {@link #VALID} only: every other outcome means the file gives the application nothing, and the application keeps running without what it would
 * have unlocked. Log the outcome, because it tells support what went wrong.
 */
public enum Outcome {
    /**
     * The file verified, and neither it nor its license has expired.
     */
    VALID,
    /**
     * The text isn't a well-formed license file, or its payload contradicts its envelope.
     */
    MALFORMED,
    /**
     * The file names another algorithm than the one this build expects. Never fall back to it.
     */
    UNEXPECTED_ALGORITHM,
    /**
     * None of the keys this build compiles in has the file's {@code kid}. A file for another product ends here, because only your product's keys are compiled in.
     */
    KEY_UNKNOWN,
    /**
     * The signature doesn't verify: the file was changed, or wasn't signed for a license file.
     */
    BAD_SIGNATURE,
    /**
     * The file is signed correctly but encrypted, and this build's license key doesn't open it: the license key is wrong or missing, or was regenerated after the file was issued. Ask for the
     * current key and a new file.
     */
    DECRYPTION_FAILED,
    /**
     * The file is a newer version of the format than this build understands. Keep any file you already hold.
     */
    UNSUPPORTED_VERSION,
    /**
     * The file was issued later than the current time allows, so the machine's clock is behind: check the clock and try again. Or the license it carries starts later: keep the file and check
     * it again then, when it becomes valid.
     */
    NOT_YET_VALID,
    /**
     * The file's {@code expiry} has passed, so ask for a new file; or the license it carries has ended.
     */
    EXPIRED,
    /**
     * The current time is behind a time this application has already seen, so the clock was probably set back.
     */
    CLOCK_BEHIND
}
