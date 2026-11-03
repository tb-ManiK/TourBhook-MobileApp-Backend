package com.tourbhook.api.service.impl;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberType;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;
import com.tourbhook.api.config.PhoneRulesProperties;
import com.tourbhook.api.config.PhoneRulesProperties.Mode;
import com.tourbhook.api.repository.BlockedPhoneNumberRepository;
import com.tourbhook.api.repository.UserRepository;
import com.tourbhook.api.repository.exception.BadRequestException;
import com.tourbhook.api.service.PhoneNumberPolicyService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class PhoneNumberPolicyServiceImpl implements PhoneNumberPolicyService {

    static final String INVALID_MESSAGE = "Please enter a valid mobile number.";
    static final String NOT_ALLOWED_MESSAGE =
            "Company, landline or toll-free numbers can't be used. Please use your own personal mobile number.";
    static final String ALREADY_USED_MESSAGE = "This mobile number is already registered with another account.";

    enum Violation { INVALID_FORMAT, NOT_PERSONAL_MOBILE, BLOCKED_NUMBER, ALREADY_REGISTERED }

    private record Outcome(Violation violation, String e164) { }

    private final BlockedPhoneNumberRepository blockedPhoneNumberRepository;
    private final UserRepository userRepository;
    private final PhoneRulesProperties properties;

    @PostConstruct
    void validateConfiguration() {
        if (properties.getMode() == Mode.OFF) {
            return; // never let a mistake in unused settings stop the app from starting
        }
        PhoneNumberUtil util = PhoneNumberUtil.getInstance();
        if (properties.getDefaultRegion() == null
                || !util.getSupportedRegions().contains(properties.getDefaultRegion().toUpperCase(Locale.ROOT))) {
            throw new IllegalStateException("phone-rules.default-region is not a supported region code: "
                    + properties.getDefaultRegion());
        }
        allowedTypes(); // throws IllegalStateException on an unknown type name
    }

    @Override
    @Transactional(readOnly = true)
    public String checkForSignup(String rawPhone) {
        Mode mode = properties.getMode();
        if (mode == null || mode == Mode.OFF) {
            return rawPhone;
        }

        Outcome outcome = evaluate(rawPhone);
        if (outcome.violation() == null) {
            return mode == Mode.ENFORCE ? outcome.e164() : rawPhone;
        }

        if (mode == Mode.MONITOR) {
            log.warn("Phone rule would reject this signup (monitor mode): reason={}", outcome.violation());
            return rawPhone;
        }

        log.info("Signup rejected by phone rule: reason={}", outcome.violation());
        throw new BadRequestException(messageFor(outcome.violation()));
    }

    private Outcome evaluate(String rawPhone) {
        if (rawPhone == null || rawPhone.isBlank()) {
            return new Outcome(Violation.INVALID_FORMAT, null);
        }

        PhoneNumberUtil util = PhoneNumberUtil.getInstance();
        String region = properties.getDefaultRegion().toUpperCase(Locale.ROOT);

        PhoneNumber number;
        try {
            number = util.parse(rawPhone.trim(), region);
        } catch (NumberParseException ex) {
            return new Outcome(Violation.INVALID_FORMAT, null);
        }
        if (!util.isPossibleNumber(number)) {
            return new Outcome(Violation.INVALID_FORMAT, null);
        }

        PhoneNumberType type = util.getNumberType(number);
        if (type == PhoneNumberType.UNKNOWN) {
            return new Outcome(Violation.INVALID_FORMAT, null);
        }

        String e164 = util.format(number, PhoneNumberFormat.E164);
        if (!allowedTypes().contains(type)) {
            return new Outcome(Violation.NOT_PERSONAL_MOBILE, e164);
        }
        if (blockedPhoneNumberRepository.existsByPhoneE164(e164)) {
            return new Outcome(Violation.BLOCKED_NUMBER, e164);
        }
        if (properties.isCheckExistingAccounts()
                && userRepository.existsByPhoneIn(storedVariants(util, number, e164, region))) {
            return new Outcome(Violation.ALREADY_REGISTERED, e164);
        }
        return new Outcome(null, e164);
    }

    private Set<String> storedVariants(PhoneNumberUtil util, PhoneNumber number, String e164, String region) {
        Set<String> variants = new LinkedHashSet<>();
        variants.add(e164);
        variants.add(e164.substring(1));
        if (util.getCountryCodeForRegion(region) == number.getCountryCode()) {
            String national = String.valueOf(number.getNationalNumber());
            variants.add(national);
            variants.add("0" + national);
        }
        return variants;
    }

    private Set<PhoneNumberType> allowedTypes() {
        Set<PhoneNumberType> allowed = EnumSet.noneOf(PhoneNumberType.class);
        for (String name : properties.getAllowedTypes()) {
            try {
                allowed.add(PhoneNumberType.valueOf(name.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ex) {
                throw new IllegalStateException("phone-rules.allowed-types contains an unknown type: " + name);
            }
        }
        return allowed;
    }

    private String messageFor(Violation violation) {
        return switch (violation) {
            case INVALID_FORMAT -> INVALID_MESSAGE;
            case NOT_PERSONAL_MOBILE, BLOCKED_NUMBER -> NOT_ALLOWED_MESSAGE;
            case ALREADY_REGISTERED -> ALREADY_USED_MESSAGE;
        };
    }
}