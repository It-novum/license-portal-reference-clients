package com.itnovum.license.reference;

/**
 * The result of verifying a file: either {@link Verified}, the only result that carries a license, or {@link Refused} with the reason.
 */
public sealed interface Verification {

    Outcome outcome();

    record Verified(VerifiedLicense license) implements Verification {

        @Override
        public Outcome outcome() {
            return Outcome.VALID;
        }
    }

    record Refused(Outcome outcome) implements Verification {
    }
}
