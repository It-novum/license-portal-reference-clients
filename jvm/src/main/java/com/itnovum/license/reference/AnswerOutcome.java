package com.itnovum.license.reference;

/**
 * What an answer from the license portal means. Only a verified answer can be {@link #VALID}, {@link #REFUSED}, {@link #WRONG_PRODUCT} or {@link #ERROR}; anything else is a
 * {@link #NETWORK_FAILURE}, so a forged or swapped answer can never lock a customer out. Log the outcome, because it tells support what went wrong.
 */
public enum AnswerOutcome {
    /**
     * A verified answer says the license is valid for this product.
     */
    VALID,
    /**
     * A verified answer says the license isn't valid, with a code that says why. Treat a code you don't know as a plain refusal.
     */
    REFUSED,
    /**
     * A verified answer carries a license for another of the vendor's products.
     */
    WRONG_PRODUCT,
    /**
     * A verified answer refuses the request itself, with a {@code 4xx} other than {@code 429}. Fix the request rather than retrying it. No license state changes.
     */
    ERROR,
    /**
     * No answer that verifies: no connection, a timeout, an unsigned or tampered answer, an answer to another request, a {@code 429} or a {@code 5xx}. No license state changes: keep any file
     * you hold, and retry later with backoff, honouring {@code Retry-After}.
     */
    NETWORK_FAILURE
}
