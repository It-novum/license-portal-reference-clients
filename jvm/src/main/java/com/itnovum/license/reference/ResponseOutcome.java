package com.itnovum.license.reference;

/**
 * What a response from the license portal means. Only a verified response can be {@link #VALID}, {@link #REFUSED}, {@link #WRONG_PRODUCT} or {@link #ERROR}; anything else is a
 * {@link #NETWORK_FAILURE}, so a forged or swapped response can never lock a customer out. Log the outcome, because it tells support what went wrong.
 */
public enum ResponseOutcome {
    /**
     * A verified response says the license is valid for this product.
     */
    VALID,
    /**
     * A verified response says the license isn't valid, with a code that says why. Treat a code you don't know as a plain refusal.
     */
    REFUSED,
    /**
     * A verified response carries a license for another of the vendor's products.
     */
    WRONG_PRODUCT,
    /**
     * A verified response refuses the request itself, with a {@code 4xx} other than {@code 429}. Fix the request rather than retrying it. No license state changes.
     */
    ERROR,
    /**
     * No response that verifies: no connection, a timeout, an unsigned or tampered response, a response to another request, a {@code 429} or a {@code 5xx}. No license state changes: keep any file
     * you hold, and retry later with backoff, honouring {@code Retry-After}.
     */
    NETWORK_FAILURE
}
