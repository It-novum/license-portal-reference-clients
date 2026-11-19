package com.itnovum.license.reference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class VersionBindingTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static VerifiedLicense license(Map<String, ?> metadata) {
        var now = Instant.parse("2026-01-01T00:00:00Z");
        return new VerifiedLicense(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), now, Optional.empty(), List.of(), JSON.valueToTree(metadata), now,
                Optional.of(now.plusSeconds(60)));
    }

    @Test
    void aMajorVersionAllowsEachOfItsReleasesOnly() {
        var license = license(Map.of(VersionBinding.MAX_VERSION, "3"));

        assertThat(VersionBinding.allows(license, "3.9.1")).isTrue();
        assertThat(VersionBinding.allows(license, "3")).isTrue();
        assertThat(VersionBinding.allows(license, "2.0")).isTrue();
        assertThat(VersionBinding.allows(license, "4.0")).isFalse();
    }

    @Test
    void aMinorVersionAllowsItsPatchesButNotTheNextMinor() {
        var license = license(Map.of(VersionBinding.MAX_VERSION, "3.2"));

        assertThat(VersionBinding.allows(license, "3.2.7")).isTrue();
        assertThat(VersionBinding.allows(license, "3.10")).isFalse();
        assertThat(VersionBinding.allows(license, "3.3")).isFalse();
    }

    @Test
    void withoutMaxVersionEveryReleaseIsAllowed() {
        assertThat(VersionBinding.allows(license(Map.of()), "99.0")).isTrue();
    }

    @Test
    void aMistypedMaxVersionAllowsNothing() {
        assertThat(VersionBinding.allows(license(Map.of(VersionBinding.MAX_VERSION, "three")), "1.0")).isFalse();
    }

    @Test
    void aMaxVersionThatIsntTextAllowsNothing() {
        assertThat(VersionBinding.allows(license(Map.of(VersionBinding.MAX_VERSION, 3)), "1.0")).isFalse();
    }

    @Test
    void metadataIsReadAsTheTypeExpected() {
        var license = license(Map.of("seats", 25, "beta", true, "region", "EU"));

        assertThat(license.number("seats")).hasValueSatisfying(seats -> assertThat(seats).isEqualByComparingTo("25"));
        assertThat(license.text("seats")).isEmpty();
        assertThat(license.flag("beta")).contains(true);
        assertThat(license.text("region")).contains("EU");
        assertThat(license.flag("missing")).isEmpty();
    }

    @Test
    void aBuildVersionHasToBeNumbers() {
        assertThatThrownBy(() -> VersionBinding.allows(license(Map.of()), "3.x")).isInstanceOf(IllegalArgumentException.class);
    }
}
