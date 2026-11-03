package com.tourbhook.api.service;

import com.tourbhook.api.config.ModerationProperties;
import com.tourbhook.api.entity.User;
import com.tourbhook.api.repository.exception.ContentModerationException;
import com.tourbhook.api.service.impl.ModerationServiceImpl;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import com.tourbhook.api.repository.exception.BadRequestException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContentModerationEnforcerTest {

    private final RecordingEnforcement enforcement = new RecordingEnforcement();
    private final ContentModerationEnforcer enforcer =
            new ContentModerationEnforcer(new ModerationServiceImpl(properties()), enforcement);
    private final User author = User.builder().id("user-1").build();

    private static ModerationProperties properties() {
        ModerationProperties properties = new ModerationProperties();
        properties.setBannedTerms(List.of("scam", "spam-link"));
        properties.setBlockedDomains(List.of("malicious-example.test"));
        return properties;
    }

    @Test
    void enforce_cleanText_blocksNobody() {
        assertThatCode(() -> enforcer.enforce(author, "Anyone up for a sunrise trek at Nandi Hills on 12 Nov?"))
                .doesNotThrowAnyException();
        assertThat(enforcement.blocked).isEmpty();
    }

    @Test
    void enforce_bannedTerm_blocksPermanentlyAndThrowsWithoutWarning() {
        assertThatThrownBy(() -> enforcer.enforce(author, "this tour is a SCAM"))
                .isInstanceOf(ContentModerationException.class)
                .hasMessageContaining("permanently blocked");

        assertThat(enforcement.blocked).containsExactly(author);
    }

    @Test
    void enforce_linkToBlockedDomain_isTreatedExactlyLikeBannedText() {
        assertThatThrownBy(() -> enforcer.enforce(author, "great deals here https://malicious-example.test/offer"))
                .isInstanceOf(ContentModerationException.class);
        assertThat(enforcement.blocked).containsExactly(author);
    }

    @Test
    void enforce_blockedDomainWithoutSchemeOrWithSubdomain_doesNotSlipThrough() {
        for (String sneaky : List.of(
                "malicious-example.test",
                "www.malicious-example.test/a",
                "SUB.Malicious-Example.TEST",
                "http://nice.example.com@malicious-example.test/x")) {
            assertThatThrownBy(() -> enforcer.enforce(author, "look: " + sneaky))
                    .as(sneaky)
                    .isInstanceOf(ContentModerationException.class);
        }
    }

    @Test
    void enforce_harmlessLinksAndUnusualCharacters_areNotMistakenForViolations() {
        // A '|' or '[' makes a full URL "malformed" for java.net.URI. That must never get
        // someone permanently blocked, so only the host is ever checked.
        String message = "Map: https://maps.example.com/place?q=a|b[1] and notes at www.trip-notes.org, e.g. 4.5 stars";

        assertThatCode(() -> enforcer.enforce(author, message)).doesNotThrowAnyException();
        assertThat(enforcement.blocked).isEmpty();
    }

    @Test
    void enforce_checksEveryTextAndIgnoresNullOrBlank() {
        assertThatThrownBy(() -> enforcer.enforce(author, "Weekend Trek", null, "  ", "pure scam"))
                .isInstanceOf(ContentModerationException.class);
        assertThat(enforcement.blocked).hasSize(1);
    }

    // ------------------------------------------------------------------ the softer check used for shared links

    @Test
    void rejectIfViolation_aBannedTerm_isA400_andBlocksNobody() {
        assertThatThrownBy(() -> enforcer.rejectIfViolation("Weekend trip", "this is a scam"))
                .isInstanceOf(BadRequestException.class);
        assertThat(enforcement.blocked).isEmpty();
    }

    @Test
    void rejectIfViolation_aBlockedDomain_isA400_andBlocksNobody() {
        assertThatThrownBy(() -> enforcer.rejectIfViolation("deals at www.malicious-example.test/a"))
                .isInstanceOf(BadRequestException.class);
        assertThat(enforcement.blocked).isEmpty();
    }

    @Test
    void rejectIfViolation_cleanTextAndNullOrBlank_passes() {
        assertThatCode(() -> enforcer.rejectIfViolation("Sunrise at the lake", null, "  "))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectBlockedLink_aBlockedDomain_isA400_andAnOrdinaryLinkPasses() {
        assertThatThrownBy(() -> enforcer.rejectBlockedLink("https://malicious-example.test/x"))
                .isInstanceOf(BadRequestException.class);
        assertThatCode(() -> enforcer.rejectBlockedLink("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
                .doesNotThrowAnyException();
        assertThat(enforcement.blocked).isEmpty();
    }

    private static final class RecordingEnforcement implements AccountEnforcementService {
        private final List<User> blocked = new ArrayList<>();

        @Override
        public void blockPermanently(User user, String reason) {
            blocked.add(user);
        }
    }

    // ------------------------------------------------------------------ the dedicated link check

    @Test
    void enforceLink_aLinkToABlockedDomain_blocksPermanentlyAndThrows() {
        assertThatThrownBy(() -> enforcer.enforceLink(author, "https://malicious-example.test/watch?v=dQw4w9WgXcQ"))
                .isInstanceOf(ContentModerationException.class)
                .hasMessageContaining("permanently blocked");

        assertThat(enforcement.blocked).containsExactly(author);
    }

    @Test
    void enforceLink_aSubdomainOfABlockedDomain_isBlockedToo() {
        assertThatThrownBy(() -> enforcer.enforceLink(author, "https://videos.Malicious-Example.TEST/x"))
                .isInstanceOf(ContentModerationException.class);
        assertThat(enforcement.blocked).containsExactly(author);
    }

    @Test
    void enforceLink_anOrdinaryLink_blocksNobody() {
        assertThatCode(() -> enforcer.enforceLink(author, "https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
                .doesNotThrowAnyException();
        assertThat(enforcement.blocked).isEmpty();
    }
}