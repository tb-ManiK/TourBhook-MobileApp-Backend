package com.tourbhook.api.service.venue;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class GeoDistanceTest {

    @Test
    void meters_oneDegreeOfLongitudeAtTheEquator_isAboutOneHundredElevenKilometres() {
        assertThat(GeoDistance.meters(0, 0, 0, 1)).isCloseTo(111_195, within(5.0));
    }

    @Test
    void meters_samePoint_isZero() {
        assertThat(GeoDistance.meters(13.0827, 80.2707, 13.0827, 80.2707)).isZero();
    }

    @Test
    void meters_isSymmetric() {
        double there = GeoDistance.meters(13.0827, 80.2707, 12.9716, 77.5946);
        double back = GeoDistance.meters(12.9716, 77.5946, 13.0827, 80.2707);

        assertThat(there).isEqualTo(back);
        assertThat(there).isBetween(280_000.0, 300_000.0); // Chennai to Bengaluru, roughly 290 km as the crow flies
    }
}