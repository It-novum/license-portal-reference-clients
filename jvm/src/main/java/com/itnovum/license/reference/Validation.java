package com.itnovum.license.reference;

import java.util.Optional;

/**
 * The result of validating a license key online.
 *
 * @param code the answer's code, for every verified answer: {@code VALID}, a refusal's reason such as {@code EXPIRED}, or an error's code
 * @param license the license, when the outcome is {@link AnswerOutcome#VALID}, or {@link AnswerOutcome#REFUSED} and the answer carries one
 */
public record Validation(AnswerOutcome outcome, Optional<String> code, Optional<OnlineLicense> license) {

    static final Validation NETWORK_FAILURE = new Validation(AnswerOutcome.NETWORK_FAILURE, Optional.empty(), Optional.empty());
}
