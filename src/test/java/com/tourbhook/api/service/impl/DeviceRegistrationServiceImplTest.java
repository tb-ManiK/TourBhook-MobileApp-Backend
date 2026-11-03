package com.tourbhook.api.service.impl;

import com.tourbhook.api.config.DeviceLimitProperties;
import com.tourbhook.api.entity.User;
import com.tourbhook.api.entity.UserDevice;
import com.tourbhook.api.repository.UserDeviceRepository;
import com.tourbhook.api.repository.exception.BadRequestException;
import com.tourbhook.api.repository.exception.ForbiddenOperationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeviceRegistrationServiceImplTest {

    private static final String PEPPER = "test-pepper-value";
    private static final String DEVICE = "a1b2c3d4e5f60718";

    @Mock
    private UserDeviceRepository userDeviceRepository;

    private DeviceLimitProperties properties;
    private DeviceRegistrationServiceImpl service;
    private User user;

    @BeforeEach
    void setUp() {
        properties = new DeviceLimitProperties();
        properties.setEnabled(true);
        properties.setHashPepper(PEPPER);
        properties.setMaxAccountsPerDevice(1);
        service = new DeviceRegistrationServiceImpl(userDeviceRepository, properties);
        user = User.builder().id("user-1").build();
    }

    // ---------------------------------------------------------------- feature switch

    @Test
    void doesNothingAtAllWhenFeatureIsOff() {
        properties.setEnabled(false);
        properties.setRequireDeviceId(true);

        assertThatCode(() -> service.assertSignupAllowed(DEVICE)).doesNotThrowAnyException();
        assertThatCode(() -> service.assertSignupAllowed(null)).doesNotThrowAnyException();
        service.linkDevice(user, DEVICE);

        verifyNoInteractions(userDeviceRepository);
    }

    // ---------------------------------------------------------------- signup check

    @Test
    void allowsFirstAccountOnADevice() {
        when(userDeviceRepository.countByDeviceHash(anyString())).thenReturn(0L);

        assertThatCode(() -> service.assertSignupAllowed(DEVICE)).doesNotThrowAnyException();
    }

    @Test
    void blocksNewAccountWhenDeviceAlreadyHasOne() {
        when(userDeviceRepository.countByDeviceHash(anyString())).thenReturn(1L);

        assertThatThrownBy(() -> service.assertSignupAllowed(DEVICE))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessage(DeviceRegistrationServiceImpl.DEVICE_BLOCKED_MESSAGE);
    }

    @Test
    void respectsConfiguredLimitOfTwoAccounts() {
        properties.setMaxAccountsPerDevice(2);
        when(userDeviceRepository.countByDeviceHash(anyString())).thenReturn(1L, 2L);

        assertThatCode(() -> service.assertSignupAllowed(DEVICE)).doesNotThrowAnyException();
        assertThatThrownBy(() -> service.assertSignupAllowed(DEVICE))
                .isInstanceOf(ForbiddenOperationException.class);
    }

    static Stream<String> unusableDeviceIds() {
        return Stream.of(null, "", "   ", "short", "9774d56d682e549c", "UNKNOWN", "null", "0000000000000000");
    }

    @ParameterizedTest
    @MethodSource("unusableDeviceIds")
    void unusableDeviceIdIsIgnoredWhenNotRequired(String badId) {
        assertThatCode(() -> service.assertSignupAllowed(badId)).doesNotThrowAnyException();
        service.linkDevice(user, badId);

        verifyNoInteractions(userDeviceRepository);
    }

    @Test
    void tooLongDeviceIdIsIgnored() {
        String huge = "a".repeat(properties.getMaxIdLength() + 1);

        assertThatCode(() -> service.assertSignupAllowed(huge)).doesNotThrowAnyException();

        verifyNoInteractions(userDeviceRepository);
    }

    @Test
    void missingDeviceIdIsRejectedWhenRequired() {
        properties.setRequireDeviceId(true);

        assertThatThrownBy(() -> service.assertSignupAllowed(null))
                .isInstanceOf(BadRequestException.class)
                .hasMessage(DeviceRegistrationServiceImpl.DEVICE_REQUIRED_MESSAGE);
        verifyNoInteractions(userDeviceRepository);
    }

    // ---------------------------------------------------------------- hashing

    @Test
    void storesOnlyAStableNormalisedHashNeverTheRawId() {
        when(userDeviceRepository.countByDeviceHash(anyString())).thenReturn(0L);

        service.assertSignupAllowed(DEVICE);
        service.assertSignupAllowed("  " + DEVICE.toUpperCase() + "  ");

        ArgumentCaptor<String> hashes = ArgumentCaptor.forClass(String.class);
        verify(userDeviceRepository, org.mockito.Mockito.times(2)).countByDeviceHash(hashes.capture());

        assertThat(hashes.getAllValues().get(0)).isEqualTo(hashes.getAllValues().get(1));
        assertThat(hashes.getAllValues().get(0)).matches("[0-9a-f]{64}");
        assertThat(hashes.getAllValues().get(0)).doesNotContain(DEVICE);
    }

    @Test
    void differentPepperGivesDifferentHash() {
        when(userDeviceRepository.countByDeviceHash(anyString())).thenReturn(0L);
        DeviceLimitProperties otherProps = new DeviceLimitProperties();
        otherProps.setEnabled(true);
        otherProps.setHashPepper("another-pepper");
        DeviceRegistrationServiceImpl otherService = new DeviceRegistrationServiceImpl(userDeviceRepository, otherProps);

        service.assertSignupAllowed(DEVICE);
        otherService.assertSignupAllowed(DEVICE);

        ArgumentCaptor<String> hashes = ArgumentCaptor.forClass(String.class);
        verify(userDeviceRepository, org.mockito.Mockito.times(2)).countByDeviceHash(hashes.capture());
        assertThat(hashes.getAllValues().get(0)).isNotEqualTo(hashes.getAllValues().get(1));
    }

    // ---------------------------------------------------------------- linking

    @Test
    void linkSavesOneHashedRowForTheNewUser() {
        when(userDeviceRepository.existsByDeviceHashAndUserId(anyString(), anyString())).thenReturn(false);

        service.linkDevice(user, DEVICE);

        ArgumentCaptor<UserDevice> saved = ArgumentCaptor.forClass(UserDevice.class);
        verify(userDeviceRepository).save(saved.capture());
        UserDevice row = saved.getValue();
        assertThat(row.getUserId()).isEqualTo("user-1");
        assertThat(row.getDeviceHash()).matches("[0-9a-f]{64}").doesNotContain(DEVICE);
        assertThat(row.getFirstSeenAt()).isNotNull();
        assertThat(row.getLastSeenAt()).isNotNull();
    }

    @Test
    void linkDoesNotCreateDuplicateRows() {
        when(userDeviceRepository.existsByDeviceHashAndUserId(anyString(), anyString())).thenReturn(true);

        service.linkDevice(user, DEVICE);

        verify(userDeviceRepository, never()).save(any(UserDevice.class));
    }

    @Test
    void linkIgnoresMissingUser() {
        service.linkDevice(null, DEVICE);
        service.linkDevice(User.builder().build(), DEVICE);

        verifyNoInteractions(userDeviceRepository);
    }

    // ---------------------------------------------------------------- startup validation

    @Test
    void startupFailsWhenEnabledWithoutPepper() {
        properties.setHashPepper("  ");

        assertThatThrownBy(service::validateConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DEVICE_ID_PEPPER");
    }

    @Test
    void startupFailsWhenLimitIsBelowOne() {
        properties.setMaxAccountsPerDevice(0);

        assertThatThrownBy(service::validateConfiguration)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void startupIsFineWhenDisabledEvenWithoutPepper() {
        properties.setEnabled(false);
        properties.setHashPepper("");

        assertThatCode(service::validateConfiguration).doesNotThrowAnyException();
    }
}