package com.tourbhook.api.service.impl;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlaceSearchSupportTest {

    // Mumbai centre and two real-world points used in the bug report.
    private static final double MUMBAI_LAT = 19.0760;
    private static final double MUMBAI_LNG = 72.8777;

    @Test
    void tokenize_splitsOnPunctuationAndLowerCases() {
        assertThat(PlaceSearchSupport.tokenize("ISKCON, Kharghar!  Navi-Mumbai"))
                .containsExactly("iskcon", "kharghar", "navi", "mumbai");
    }

    @Test
    void significantTokens_dropsTheCityWords() {
        assertThat(PlaceSearchSupport.significantTokens("iskcon mumbai", "Mumbai"))
                .containsExactly("iskcon");
    }

    @Test
    void significantTokens_keepsEverythingWhenOnlyTheCityWasTyped() {
        assertThat(PlaceSearchSupport.significantTokens("Mumbai", "Mumbai"))
                .containsExactly("mumbai");
    }

    @Test
    void matchesAllTokens_acceptsPartialWordsAcrossFields() {
        List<String> tokens = PlaceSearchSupport.tokenize("khar iskcon");
        assertThat(PlaceSearchSupport.matchesAllTokens(tokens, "ISKCON Temple", "Sector 7, Kharghar", null)).isTrue();
        assertThat(PlaceSearchSupport.matchesAllTokens(tokens, "ISKCON Temple", "Juhu", null)).isFalse();
    }

    @Test
    void matchesAllTokens_emptyTokensNeverMatch() {
        assertThat(PlaceSearchSupport.matchesAllTokens(List.of(), "anything")).isFalse();
    }

    @Test
    void relevance_ranksNameMatchesAboveAddressMatches() {
        List<String> tokens = PlaceSearchSupport.tokenize("iskcon kharghar");
        assertThat(PlaceSearchSupport.relevance(tokens, "ISKCON Kharghar", null, null)).isEqualTo(3);
        assertThat(PlaceSearchSupport.relevance(tokens, "ISKCON Temple", "Kharghar Sector 7", null)).isEqualTo(2);
        assertThat(PlaceSearchSupport.relevance(tokens, "ISKCON Juhu", "Juhu, Mumbai", null)).isEqualTo(1);
        assertThat(PlaceSearchSupport.relevance(tokens, "Gateway of India", "Colaba", null)).isEqualTo(0);
    }

    @Test
    void distanceKm_isRoughlyRightForKnownPoints() {
        // Mumbai centre to Kharghar is about 20 km; to Delhi about 1,150 km.
        assertThat(PlaceSearchSupport.distanceKm(MUMBAI_LAT, MUMBAI_LNG, 19.0436, 73.0668)).isBetween(15.0, 25.0);
        assertThat(PlaceSearchSupport.distanceKm(MUMBAI_LAT, MUMBAI_LNG, 28.5586, 77.2420)).isBetween(1100.0, 1200.0);
        assertThat(PlaceSearchSupport.distanceKm(MUMBAI_LAT, MUMBAI_LNG, MUMBAI_LAT, MUMBAI_LNG)).isZero();
    }

    @Test
    void belongsToCity_usesDistanceWhenCoordinatesAreKnown() {
        // Kharghar (Navi Mumbai) counts as Mumbai; an ISKCON temple in Vrindavan does not.
        assertThat(PlaceSearchSupport.belongsToCity(
                19.0436, 73.0668, MUMBAI_LAT, MUMBAI_LNG, 40, "Mumbai", "Unknown", null)).isTrue();
        assertThat(PlaceSearchSupport.belongsToCity(
                27.5806, 77.7010, MUMBAI_LAT, MUMBAI_LNG, 40, "Mumbai", "Mumbai", "Mumbai Road")).isFalse();
    }

    @Test
    void belongsToCity_withoutCoordinatesFallsBackToCityOrAddress() {
        assertThat(PlaceSearchSupport.belongsToCity(
                null, null, null, null, 40, "Mumbai", "mumbai", null)).isTrue();
        assertThat(PlaceSearchSupport.belongsToCity(
                null, null, null, null, 40, "Mumbai", "Pune", "Camp, Pune")).isFalse();
        assertThat(PlaceSearchSupport.belongsToCity(
                null, null, null, null, 40, "Mumbai", "Unknown", "Juhu, Mumbai")).isTrue();
    }

    @Test
    void buildGoogleQuery_addsTheCityOnlyWhenMissing() {
        assertThat(PlaceSearchSupport.buildGoogleQuery("iskcon", "Mumbai")).isEqualTo("iskcon in Mumbai");
        assertThat(PlaceSearchSupport.buildGoogleQuery("iskcon mumbai", "Mumbai")).isEqualTo("iskcon mumbai");
    }

    @Test
    void boundingBoxDeltas_coverTheRadius() {
        double latDelta = PlaceSearchSupport.latitudeDelta(40);
        double lngDelta = PlaceSearchSupport.longitudeDelta(40, MUMBAI_LAT);
        assertThat(latDelta).isBetween(0.3, 0.4);
        assertThat(lngDelta).isGreaterThan(latDelta);
    }

    @Test
    void isAreaOnly_recognisesSuburbsAndDistrictsButNotAttractions() {
        assertThat(PlaceSearchSupport.isAreaOnly("sublocality_level_1")).isTrue();
        assertThat(PlaceSearchSupport.isAreaOnly("sublocality level 1")).isTrue();
        assertThat(PlaceSearchSupport.isAreaOnly("locality")).isTrue();
        assertThat(PlaceSearchSupport.isAreaOnly("hindu temple")).isFalse();
        assertThat(PlaceSearchSupport.isAreaOnly("tourist_attraction")).isFalse();
        assertThat(PlaceSearchSupport.isAreaOnly(null)).isFalse();
    }


    @Test
    void relevance_isZeroForUnrelatedPlaces() {
        List<String> tokens = PlaceSearchSupport.tokenize("zzzzqx");
        assertThat(PlaceSearchSupport.relevance(tokens, "Z Bliss Suites", "Kurla West, Mumbai", "hotel")).isZero();
        assertThat(PlaceSearchSupport.relevance(tokens, "XYXX Store", "Phoenix Market City", "clothing store")).isZero();
    }

    @Test
    void relevance_toleratesOneTypo() {
        List<String> tokens = PlaceSearchSupport.tokenize("iskon");
        assertThat(PlaceSearchSupport.relevance(tokens, "ISKCON Temple, Juhu", "Juhu, Mumbai", "hindu temple")).isEqualTo(3);
    }

    @Test
    void relevance_shortWordsMustMatchExactly() {
        List<String> tokens = PlaceSearchSupport.tokenize("kha");
        assertThat(PlaceSearchSupport.relevance(tokens, "Kapil Hotel", null, null)).isZero();
    }

    @Test
    void editDistanceAtMostOne_coversInsertDeleteAndChange() {
        assertThat(PlaceSearchSupport.editDistanceAtMostOne("iskcon", "iskon")).isTrue();
        assertThat(PlaceSearchSupport.editDistanceAtMostOne("temple", "tempel")).isFalse();
        assertThat(PlaceSearchSupport.editDistanceAtMostOne("temple", "tample")).isTrue();
        assertThat(PlaceSearchSupport.editDistanceAtMostOne("temple", "temples")).isTrue();
        assertThat(PlaceSearchSupport.editDistanceAtMostOne("temple", "tmpl")).isFalse();
        assertThat(PlaceSearchSupport.editDistanceAtMostOne("abc", "abc")).isTrue();
    }

}