package com.itnovum.license.reference;

import java.util.Optional;

/**
 * The result of validating a license key online.
 *
 * @param code the response's code, for every verified response: {@code VALID}, a refusal's reason such as {@code EXPIRED}, or an error's code
 * @param license the license, when the outcome is {@link ResponseOutcome#VALID}, or {@link ResponseOutcome#REFUSED} and the response carries one
 */
public record Validation(ResponseOutcome outcome, Optional<String> code, Optional<OnlineLicense> license) {

    static final Validation NETWORK_FAILURE = new Validation(ResponseOutcome.NETWORK_FAILURE, Optional.empty(), Optional.empty());
}
