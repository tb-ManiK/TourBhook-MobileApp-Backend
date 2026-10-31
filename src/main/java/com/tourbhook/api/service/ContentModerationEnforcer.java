package com.tourbhook.api.service;

import com.tourbhook.api.dto.moderation.ModerationResult;
import com.tourbhook.api.entity.User;
import com.tourbhook.api.repository.exception.ContentModerationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class ContentModerationEnforcer {

    static final String BLOCK_MESSAGE = "Your account has been permanently blocked for violating content guidelines.";

    private static final Pattern HOST_LIKE =
            Pattern.compile("(?i)(?<![a-z0-9-])((?:[a-z0-9-]+\\.)+[a-z]{2,})(?![a-z0-9-])");

    private final ModerationService moderationService;
    private final AccountEnforcementService accountEnforcementService;

    public void enforce(User author, String... texts) {
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                continue;
            }
            Optional<String> violation = findViolation(text);
            if (violation.isPresent()) {
                blockAndThrow(author, violation.get());
            }
        }
    }

    public void enforceLink(User author, String url) {
        ModerationResult result = moderationService.checkLink(url);
        if (result.flagged()) {
            blockAndThrow(author, result.reason());
        }
    }

    private void blockAndThrow(User author, String reason) {
        accountEnforcementService.blockPermanently(author, reason);
        throw new ContentModerationException(BLOCK_MESSAGE);
    }

    private Optional<String> findViolation(String text) {
        ModerationResult textResult = moderationService.checkText(text);
        if (textResult.flagged()) {
            return Optional.of(textResult.reason());
        }

        Matcher matcher = HOST_LIKE.matcher(text);
        while (matcher.find()) {
            // Only the host is passed on, as a well-formed URL. Handing over the raw text
            // could get a harmless link rejected as "Malformed URL" (a stray '|' is enough),
            // and that rejection would permanently block someone for nothing.
            ModerationResult linkResult = moderationService.checkLink("http://" + matcher.group(1));
            if (linkResult.flagged()) {
                return Optional.of(linkResult.reason());
            }
        }
        return Optional.empty();
    }
}