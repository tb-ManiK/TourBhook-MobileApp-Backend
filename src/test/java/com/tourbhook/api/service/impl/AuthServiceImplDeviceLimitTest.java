package com.tourbhook.api.service.impl;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.tourbhook.api.dto.auth.AuthResponse;
import com.tourbhook.api.dto.auth.GoogleAuthRequest;
import com.tourbhook.api.dto.auth.RegisterRequest;
import com.tourbhook.api.entity.User;
import com.tourbhook.api.repository.OtpCodeRepository;
import com.tourbhook.api.repository.UserRepository;
import com.tourbhook.api.repository.exception.BadRequestException;
import com.tourbhook.api.repository.exception.ForbiddenOperationException;
import com.tourbhook.api.security.GoogleTokenVerifier;
import com.tourbhook.api.security.JwtService;
import com.tourbhook.api.service.AnalyticsService;
import com.tourbhook.api.service.DeviceRegistrationService;
import com.tourbhook.api.service.EmailService;
import com.tourbhook.api.service.PhoneNumberPolicyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplDeviceLimitTest {

    private static final String DEVICE = "a1b2c3d4e5f60718";

    @Mock private UserRepository userRepository;
    @Mock private OtpCodeRepository otpCodeRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private AnalyticsService analyticsService;
    @Mock private JwtService jwtService;
    @Mock private EmailService emailService;
    @Mock private GoogleTokenVerifier googleTokenVerifier;
    @Mock private DeviceRegistrationService deviceRegistrationService;
    @Mock private PhoneNumberPolicyService phoneNumberPolicyService;

    private AuthServiceImpl authService;

    @BeforeEach
    void setUp() {
        authService = new AuthServiceImpl(userRepository, otpCodeRepository, passwordEncoder,
                analyticsService, jwtService, emailService, googleTokenVerifier, deviceRegistrationService,
                phoneNumberPolicyService);
    }

    // ---------------------------------------------------------------- register

    @Test
    void registerChecksDeviceBeforeCreatingUserAndLinksItAfter() {
        RegisterRequest request = registerRequest();
        when(userRepository.existsByEmailIgnoreCase("new@example.com")).thenReturn(false);
        when(passwordEncoder.encode("secret1")).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> withId(inv.getArgument(0)));
        when(userRepository.findByEmailIgnoreCase("new@example.com"))
                .thenReturn(Optional.of(User.builder().id("user-1").name("New User").email("new@example.com").build()));

        AuthResponse response = authService.register(request, DEVICE);

        assertThat(response.user().email()).isEqualTo("new@example.com");
        InOrder order = inOrder(deviceRegistrationService, userRepository);
        order.verify(deviceRegistrationService).assertSignupAllowed(DEVICE);
        order.verify(userRepository).save(any(User.class));
        order.verify(deviceRegistrationService).linkDevice(any(User.class), org.mockito.ArgumentMatchers.eq(DEVICE));
    }

    @Test
    void registerBlockedDeviceCreatesNothingAndSendsNoEmail() {
        when(userRepository.existsByEmailIgnoreCase("new@example.com")).thenReturn(false);
        doThrow(new ForbiddenOperationException("blocked"))
                .when(deviceRegistrationService).assertSignupAllowed(DEVICE);

        assertThatThrownBy(() -> authService.register(registerRequest(), DEVICE))
                .isInstanceOf(ForbiddenOperationException.class);

        verify(userRepository, never()).save(any(User.class));
        verify(deviceRegistrationService, never()).linkDevice(any(), any());
        verifyNoInteractions(otpCodeRepository, emailService, passwordEncoder);
    }

    @Test
    void registerWithExistingEmailStillFailsFirstWithTheOldMessage() {
        when(userRepository.existsByEmailIgnoreCase("new@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(registerRequest(), DEVICE))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Email already registered");

        verifyNoInteractions(deviceRegistrationService, phoneNumberPolicyService);
    }

    @Test
    void registerFromOldAppWithoutDeviceHeaderStillWorks() {
        when(userRepository.existsByEmailIgnoreCase("new@example.com")).thenReturn(false);
        when(passwordEncoder.encode("secret1")).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> withId(inv.getArgument(0)));
        when(userRepository.findByEmailIgnoreCase("new@example.com"))
                .thenReturn(Optional.of(User.builder().id("user-1").name("New User").email("new@example.com").build()));

        AuthResponse response = authService.register(registerRequest(), null);

        assertThat(response).isNotNull();
        verify(deviceRegistrationService).assertSignupAllowed(null);
        verify(deviceRegistrationService).linkDevice(any(User.class), org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void registerStoresThePhoneReturnedByThePhonePolicy() {
        when(userRepository.existsByEmailIgnoreCase("new@example.com")).thenReturn(false);
        when(phoneNumberPolicyService.checkForSignup("9876543210")).thenReturn("+919876543210");
        when(passwordEncoder.encode("secret1")).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> withId(inv.getArgument(0)));
        when(userRepository.findByEmailIgnoreCase("new@example.com"))
                .thenReturn(Optional.of(User.builder().id("user-1").name("New User").email("new@example.com").build()));

        authService.register(registerRequest(), DEVICE);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getPhone()).isEqualTo("+919876543210");
    }

    @Test
    void registerWithARejectedPhoneStopsBeforeTheDeviceCheckAndCreatesNothing() {
        when(userRepository.existsByEmailIgnoreCase("new@example.com")).thenReturn(false);
        when(phoneNumberPolicyService.checkForSignup("9876543210"))
                .thenThrow(new BadRequestException("Company, landline or toll-free numbers can't be used."));

        assertThatThrownBy(() -> authService.register(registerRequest(), DEVICE))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Company");

        verifyNoInteractions(deviceRegistrationService, otpCodeRepository, emailService, passwordEncoder);
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void googleSignInDoesNotCollectOrCheckAPhone() {
        when(googleTokenVerifier.verify("token")).thenReturn(googlePayload());
        when(userRepository.findByEmailIgnoreCase("g@example.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode(any())).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> withId(inv.getArgument(0)));

        authService.googleLogin(new GoogleAuthRequest("token"), DEVICE);

        verifyNoInteractions(phoneNumberPolicyService);
    }

    // ---------------------------------------------------------------- google sign-in

    @Test
    void googleNewUserOnBlockedDeviceIsRejectedBeforeAnythingIsSaved() {
        when(googleTokenVerifier.verify("token")).thenReturn(googlePayload());
        when(userRepository.findByEmailIgnoreCase("g@example.com")).thenReturn(Optional.empty());
        doThrow(new ForbiddenOperationException("blocked"))
                .when(deviceRegistrationService).assertSignupAllowed(DEVICE);

        assertThatThrownBy(() -> authService.googleLogin(new GoogleAuthRequest("token"), DEVICE))
                .isInstanceOf(ForbiddenOperationException.class);

        verify(userRepository, never()).save(any(User.class));
        verify(deviceRegistrationService, never()).linkDevice(any(), any());
    }

    @Test
    void googleNewUserOnFreshDeviceIsCreatedAndLinked() {
        when(googleTokenVerifier.verify("token")).thenReturn(googlePayload());
        when(userRepository.findByEmailIgnoreCase("g@example.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode(any())).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> withId(inv.getArgument(0)));

        AuthResponse response = authService.googleLogin(new GoogleAuthRequest("token"), DEVICE);

        assertThat(response.user().email()).isEqualTo("g@example.com");
        InOrder order = inOrder(deviceRegistrationService, userRepository);
        order.verify(deviceRegistrationService).assertSignupAllowed(DEVICE);
        order.verify(userRepository).save(any(User.class));
        order.verify(deviceRegistrationService).linkDevice(any(User.class), org.mockito.ArgumentMatchers.eq(DEVICE));
    }

    @Test
    void googleSignInOfAnExistingUserNeverTouchesTheDeviceLimit() {
        User existing = User.builder().id("user-9").name("Old").email("g@example.com").deleted(false).build();
        when(googleTokenVerifier.verify("token")).thenReturn(googlePayload());
        when(userRepository.findByEmailIgnoreCase("g@example.com")).thenReturn(Optional.of(existing));

        AuthResponse response = authService.googleLogin(new GoogleAuthRequest("token"), DEVICE);

        assertThat(response.user().id()).isEqualTo("user-9");
        verifyNoInteractions(deviceRegistrationService);
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void googleDeletedAccountStillSaysUnavailableAndSkipsDeviceLimit() {
        User deleted = User.builder().id("user-9").email("g@example.com").deleted(true).build();
        when(googleTokenVerifier.verify("token")).thenReturn(googlePayload());
        when(userRepository.findByEmailIgnoreCase("g@example.com")).thenReturn(Optional.of(deleted));

        assertThatThrownBy(() -> authService.googleLogin(new GoogleAuthRequest("token"), DEVICE))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("User account is unavailable");

        verifyNoInteractions(deviceRegistrationService);
    }

    // ---------------------------------------------------------------- helpers

    private static RegisterRequest registerRequest() {
        return new RegisterRequest("New User", "new@example.com", "9876543210", "secret1");
    }

    private static GoogleIdToken.Payload googlePayload() {
        GoogleIdToken.Payload payload = new GoogleIdToken.Payload();
        payload.setSubject("google-sub-1");
        payload.setEmail("g@example.com");
        payload.setEmailVerified(true);
        payload.set("name", "Google User");
        return payload;
    }

    private static User withId(User user) {
        user.setId("user-1");
        return user;
    }
}