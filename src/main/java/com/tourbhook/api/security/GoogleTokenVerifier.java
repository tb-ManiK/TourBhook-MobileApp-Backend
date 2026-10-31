package com.tourbhook.api.security;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Collections;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.tourbhook.api.repository.exception.BadRequestException;

@Component
public class GoogleTokenVerifier {

    private final String webClientId;

    public GoogleTokenVerifier(
            @Value("${google.oauth.web-client-id:}") String webClientId) {
        this.webClientId = webClientId;
    }

    public GoogleIdToken.Payload verify(String idToken) {
        if (webClientId == null || webClientId.isBlank()) {
            throw new IllegalStateException(
                    "Google OAuth Web Client ID is not configured");
        }

        try {
            GoogleIdTokenVerifier verifier =
                    new GoogleIdTokenVerifier.Builder(
                            new NetHttpTransport(),
                            GsonFactory.getDefaultInstance())
                            .setAudience(Collections.singletonList(webClientId))
                            .build();

            GoogleIdToken token = verifier.verify(idToken);

            if (token == null) {
                throw new BadRequestException(
                        "Invalid Google ID token");
            }

            GoogleIdToken.Payload payload = token.getPayload();

            if (payload.getEmail() == null
                    || !Boolean.TRUE.equals(payload.getEmailVerified())) {
                throw new BadRequestException(
                        "Google account email is not verified");
            }

            return payload;

        } catch (GeneralSecurityException | IOException e) {
            throw new BadRequestException(
                    "Unable to verify Google ID token");
        }
    }
}
