package com.tourbhook.api.service.ugc;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class UgcDestinationsTest {

    @Test
    void key_isTheMainNameInLowerCase_soDifferentWaysOfWritingOnePlaceMeet() {
        assertThat(UgcDestinations.key("Mumbai")).isEqualTo("mumbai");
        assertThat(UgcDestinations.key("  MUMBAI ")).isEqualTo("mumbai");
        assertThat(UgcDestinations.key("Mumbai, Maharashtra")).isEqualTo("mumbai");
        assertThat(UgcDestinations.key("mumbai,India")).isEqualTo("mumbai");
    }

    @Test
    void key_ignoresAccentsPunctuationAndExtraSpaces_butKeepsSeparatePlacesApart() {
        assertThat(UgcDestinations.key("Pondichéry")).isEqualTo("pondichery");
        assertThat(UgcDestinations.key("St. Petersburg")).isEqualTo("st petersburg");
        assertThat(UgcDestinations.key("Navi   Mumbai")).isEqualTo("navi mumbai");
        assertThat(UgcDestinations.key("Navi Mumbai")).isNotEqualTo(UgcDestinations.key("Mumbai"));
    }

    @Test
    void key_keepsTheSignsOfOtherScripts() {
        assertThat(UgcDestinations.key("மதுரை, தமிழ்நாடு"))
                .isEqualTo("மதுரை");
    }

    @Test
    void key_neverBecomesEmptyForSomethingTypedInPunctuationOnly() {
        assertThat(UgcDestinations.key("...")).isEqualTo("...");
    }

    @Test
    void legacyKey_isTheOldWholeTextKey() {
        assertThat(UgcDestinations.legacyKey("  Mumbai,   Maharashtra")).isEqualTo("mumbai, maharashtra");
    }

    @Test
    void lookupKeys_includeTheOldKey_soEarlierLinksAreStillFound() {
        assertThat(UgcDestinations.lookupKeys("Mumbai")).isEqualTo(Set.of("mumbai"));
        assertThat(UgcDestinations.lookupKeys("Mumbai, Maharashtra"))
                .containsExactlyInAnyOrder("mumbai", "mumbai, maharashtra");
    }

    @Test
    void legacyPrefix_findsOldKeysThatStartWithTheMainName() {
        assertThat(UgcDestinations.legacyPrefix("Mumbai, Maharashtra")).isEqualTo("mumbai,%");
        assertThat(UgcDestinations.legacyPrefix("Mumbai")).isEqualTo("mumbai,%");
    }
}