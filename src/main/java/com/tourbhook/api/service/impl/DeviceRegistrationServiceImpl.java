package com.tourbhook.api.service.impl;

import com.tourbhook.api.config.DeviceLimitProperties;
import com.tourbhook.api.entity.User;
import com.tourbhook.api.entity.UserDevice;
import com.tourbhook.api.repository.UserDeviceRepository;
import com.tourbhook.api.repository.exception.BadRequestException;
import com.tourbhook.api.repository.exception.ForbiddenOperationException;
import com.tourbhook.api.service.DeviceRegistrationService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceRegistrationServiceImpl implements DeviceRegistrationService {

    static final String DEVICE_BLOCKED_MESSAGE =
            "We couldn't create a new account on this device. "
                    + "Please sign in to your existing account or contact support.";

    static final String DEVICE_REQUIRED_MESSAGE =
            "Device verification failed. Please update the app and try again.";

    private static final Set<String> UNUSABLE_IDS = Set.of(
            "9774d56d682e549c",
            "unknown",
            "null",
            "undefined",
            "none",
            "0000000000000000",
            "00000000-0000-0000-0000-000000000000"
    );

    private final UserDeviceRepository userDeviceRepository;
    private final DeviceLimitProperties properties;

    @PostConstruct
    void validateConfiguration() {
        if (properties.isEnabled()
                && (properties.getHashPepper() == null || properties.getHashPepper().isBlank())) {
            throw new IllegalStateException(
                    "device-limit.hash-pepper (env DEVICE_ID_PEPPER) must be set when device-limit.enabled=true");
        }
        if (properties.isEnabled() && properties.getMaxAccountsPerDevice() < 1) {
            throw new IllegalStateException("device-limit.max-accounts-per-device must be at least 1");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public void assertSignupAllowed(String deviceId) {
        if (!properties.isEnabled()) {
            return;
        }

        Optional<String> hash = hashOf(deviceId);
        if (hash.isEmpty()) {
            if (properties.isRequireDeviceId()) {
                throw new BadRequestException(DEVICE_REQUIRED_MESSAGE);
            }
            log.info("Signup without a usable device id; device limit not applied");
            return;
        }

        long existingAccounts = userDeviceRepository.countByDeviceHash(hash.get());
        if (existingAccounts >= properties.getMaxAccountsPerDevice()) {
            log.warn("Signup blocked: device already linked to {} account(s)", existingAccounts);
            throw new ForbiddenOperationException(DEVICE_BLOCKED_MESSAGE);
        }
    }

    @Override
    @Transactional
    public void linkDevice(User user, String deviceId) {
        if (!properties.isEnabled() || user == null || user.getId() == null) {
            return;
        }

        hashOf(deviceId).ifPresent(hash -> {
            if (userDeviceRepository.existsByDeviceHashAndUserId(hash, user.getId())) {
                return;
            }
            LocalDateTime now = LocalDateTime.now();
            userDeviceRepository.save(UserDevice.builder()
                    .userId(user.getId())
                    .deviceHash(hash)
                    .firstSeenAt(now)
                    .lastSeenAt(now)
                    .build());
            log.info("Device linked to new account userId={}", user.getId());
        });
    }

    private Optional<String> hashOf(String deviceId) {
        if (deviceId == null) {
            return Optional.empty();
        }
        String normalized = deviceId.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() < properties.getMinIdLength()
                || normalized.length() > properties.getMaxIdLength()
                || UNUSABLE_IDS.contains(normalized)) {
            return Optional.empty();
        }
        return Optional.of(hmacSha256Hex(normalized, properties.getHashPepper()));
    }

    private String hmacSha256Hex(String value, String pepper) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to hash device id", ex);
        }
    }
}