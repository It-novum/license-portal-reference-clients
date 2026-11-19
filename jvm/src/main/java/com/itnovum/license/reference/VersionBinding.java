package com.itnovum.license.reference;

import java.util.regex.Pattern;

/**
 * Binds a license to the releases it covers through {@code maxVersion} in its metadata, which the vendor sets when issuing the license. The file is signed, so the value can't be changed by the
 * customer. Check it at start-up with the application's own version.
 *
 * <p>
 * Versions are compared part by part as numbers, over as many parts as {@code maxVersion} names: {@code 3} allows every {@code 3.x}, and {@code 3.2} allows {@code 3.2.x} but not {@code 3.3}. A
 * license without {@code maxVersion} allows every release.
 */
public final class VersionBinding {

    public static final String MAX_VERSION = "maxVersion";

    private static final Pattern VERSION = Pattern.compile("\\d+(\\.\\d+)*");

    private VersionBinding() {
    }

    /**
     * Whether the license covers this release of the application.
     *
     * @param buildVersion the application's own version, such as {@code 3.9.1}
     * @throws IllegalArgumentException when {@code buildVersion} isn't dotted numbers, which is a mistake in the application, not in the license
     */
    public static boolean allows(VerifiedLicense license, String buildVersion) {
        if (!VERSION.matcher(buildVersion).matches()) {
            throw new IllegalArgumentException("A build version is dotted numbers, such as 3.9.1");
        }

        if (license.metadata().get(MAX_VERSION) == null) {
            return true;
        }

        // Text only, because a JSON number can't tell 3.1 from 3.10. A maxVersion of another type, or text that isn't dotted numbers, allows nothing, so a mistyped value fails closed.
        var max = license.text(MAX_VERSION).orElse("");
        if (!VERSION.matcher(max).matches()) {
            return false;
        }

        var allowed = max.split("\\.", -1);
        var build = buildVersion.split("\\.", -1);
        for (int part = 0; part < allowed.length; part++) {
            var buildPart = part < build.length ? Long.parseLong(build[part]) : 0L;
            var allowedPart = Long.parseLong(allowed[part]);
            if (buildPart != allowedPart) {
                return buildPart < allowedPart;
            }
        }

        return true;
    }
}
