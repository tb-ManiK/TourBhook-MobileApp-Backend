package com.tourbhook.api.service.impl;

import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberType;
import com.tourbhook.api.config.PhoneRulesProperties;
import com.tourbhook.api.config.PhoneRulesProperties.Mode;
import com.tourbhook.api.repository.BlockedPhoneNumberRepository;
import com.tourbhook.api.repository.UserRepository;
import com.tourbhook.api.repository.exception.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PhoneNumberPolicyServiceImplTest {

    private static final PhoneNumberUtil UTIL = PhoneNumberUtil.getInstance();
    private static final String MOBILE_E164 = example("IN", PhoneNumberType.MOBILE);
    private static final String LANDLINE_E164 = "+914423456789";
    private static final String TOLL_FREE_E164 = example("IN", PhoneNumberType.TOLL_FREE);
    private static final String SHARED_COST_E164 = "+9118602661234";

    @Mock private BlockedPhoneNumberRepository blockedPhoneNumberRepository;
    @Mock private UserRepository userRepository;

    private PhoneRulesProperties properties;
    private PhoneNumberPolicyServiceImpl service;

    @BeforeEach
    void setUp() {
        properties = new PhoneRulesProperties();
        properties.setMode(Mode.ENFORCE);
        service = new PhoneNumberPolicyServiceImpl(blockedPhoneNumberRepository, userRepository, properties);
    }

    private static String example(String region, PhoneNumberType type) {
        return UTIL.format(UTIL.getExampleNumberForType(region, type), PhoneNumberFormat.E164);
    }

    private static String national(String e164) throws Exception {
        return String.valueOf(UTIL.parse(e164, "IN").getNationalNumber());
    }

    // ---------------------------------------------------------------- OFF

    @Test
    void offModeChecksNothingAndReturnsThePhoneUntouched() {
        properties.setMode(Mode.OFF);

        assertThat(service.checkForSignup(" 12345 ")).isEqualTo(" 12345 ");
        assertThat(service.checkForSignup(null)).isNull();

        verifyNoInteractions(blockedPhoneNumberRepository, userRepository);
    }

    // ---------------------------------------------------------------- ENFORCE: accepted

    @Test
    void acceptsAPersonalMobileAndReturnsE164() throws Exception {
        String typed = national(MOBILE_E164);

        assertThat(service.checkForSignup(typed)).isEqualTo(MOBILE_E164);
    }

    @ParameterizedTest
    @ValueSource(strings = {"98765 43210", "+91 98765 43210", "+91-98765-43210", "098765-43210", "919876543210"})
    void sameMobileTypedInDifferentWaysNormalisesToTheSameValue(String typed) {
        assertThat(service.checkForSignup(typed)).isEqualTo("+919876543210");
    }

    @Test
    void acceptsInternationalMobilesWithCountryCode() {
        assertThat(service.checkForSignup("+49 1512 3456789")).isEqualTo("+4915123456789");
        assertThat(service.checkForSignup("+1 650 253 0000")).isEqualTo("+16502530000"); // fixed-line-or-mobile region
    }

    // ---------------------------------------------------------------- ENFORCE: rejected

    @Test
    void rejectsOfficeLandline() {
        assertThatThrownBy(() -> service.checkForSignup(LANDLINE_E164))
                .isInstanceOf(BadRequestException.class)
                .hasMessage(PhoneNumberPolicyServiceImpl.NOT_ALLOWED_MESSAGE);
    }

    @Test
    void rejectsTollFreeAndSharedCostCompanyNumbers() {
        assertThatThrownBy(() -> service.checkForSignup(TOLL_FREE_E164))
                .isInstanceOf(BadRequestException.class)
                .hasMessage(PhoneNumberPolicyServiceImpl.NOT_ALLOWED_MESSAGE);
        assertThatThrownBy(() -> service.checkForSignup(SHARED_COST_E164))
                .isInstanceOf(BadRequestException.class)
                .hasMessage(PhoneNumberPolicyServiceImpl.NOT_ALLOWED_MESSAGE);
    }

    static Stream<String> missingOrInvalidNumbers() {
        return Stream.of(null, "", "   ", "abc", "12345", "0000000000", "+919876543210123456");
    }

    @ParameterizedTest
    @MethodSource("missingOrInvalidNumbers")
    void rejectsMissingOrInvalidNumbers(String typed) {
        assertThatThrownBy(() -> service.checkForSignup(typed))
                .isInstanceOf(BadRequestException.class)
                .hasMessage(PhoneNumberPolicyServiceImpl.INVALID_MESSAGE);
    }

    @Test
    void rejectsANumberOnTheCompanyBlocklistEvenIfItIsAMobile() {
        when(blockedPhoneNumberRepository.existsByPhoneE164(MOBILE_E164)).thenReturn(true);

        assertThatThrownBy(() -> service.checkForSignup(MOBILE_E164))
                .isInstanceOf(BadRequestException.class)
                .hasMessage(PhoneNumberPolicyServiceImpl.NOT_ALLOWED_MESSAGE);
        verify(userRepository, never()).existsByPhoneIn(anyCollection());
    }

    @Test
    void rejectsANumberAlreadyUsedByAnotherAccount() {
        when(userRepository.existsByPhoneIn(anyCollection())).thenReturn(true);

        assertThatThrownBy(() -> service.checkForSignup(MOBILE_E164))
                .isInstanceOf(BadRequestException.class)
                .hasMessage(PhoneNumberPolicyServiceImpl.ALREADY_USED_MESSAGE);
    }

    @Test
    @SuppressWarnings("unchecked")
    void existingAccountLookupCoversEveryWayOldAccountsStoredThePhone() throws Exception {
        service.checkForSignup(MOBILE_E164);

        ArgumentCaptor<Collection<String>> variants = ArgumentCaptor.forClass(Collection.class);
        verify(userRepository).existsByPhoneIn(variants.capture());
        String national = national(MOBILE_E164);
        assertThat(variants.getValue()).containsExactlyInAnyOrder(
                MOBILE_E164, MOBILE_E164.substring(1), national, "0" + national);
    }

    @Test
    void existingAccountCheckCanBeSwitchedOff() {
        properties.setCheckExistingAccounts(false);

        assertThat(service.checkForSignup(MOBILE_E164)).isEqualTo(MOBILE_E164);

        verifyNoInteractions(userRepository);
    }

    @Test
    void allowedTypesAreConfigurable() {
        properties.setAllowedTypes(List.of("MOBILE", "FIXED_LINE"));

        assertThat(service.checkForSignup(LANDLINE_E164)).isEqualTo(LANDLINE_E164);
    }

    // ---------------------------------------------------------------- MONITOR

    @ParameterizedTest
    @ValueSource(strings = {"12345", "+914423456789", "+911800123456"})
    void monitorModeNeverRejectsAndReturnsThePhoneUntouched(String typed) {
        properties.setMode(Mode.MONITOR);

        assertThat(service.checkForSignup(typed)).isEqualTo(typed);
    }

    @Test
    void monitorModeStillReturnsTheTypedValueForAGoodNumber() {
        properties.setMode(Mode.MONITOR);

        assertThat(service.checkForSignup("98765 43210")).isEqualTo("98765 43210");
    }

    // ---------------------------------------------------------------- startup validation

    @ParameterizedTest
    @EnumSource(value = Mode.class, names = {"MONITOR", "ENFORCE"})
    void startupFailsOnUnknownRegion(Mode mode) {
        properties.setMode(mode);
        properties.setDefaultRegion("ZZ");

        assertThatThrownBy(service::validateConfiguration).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void startupFailsOnUnknownAllowedType() {
        properties.setAllowedTypes(List.of("MOBILE", "NOT_A_TYPE"));

        assertThatThrownBy(service::validateConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NOT_A_TYPE");
    }

    @Test
    void startupNeverFailsWhenFeatureIsOff() {
        properties.setMode(Mode.OFF);
        properties.setDefaultRegion("ZZ");
        properties.setAllowedTypes(List.of("NOT_A_TYPE"));

        assertThatCode(service::validateConfiguration).doesNotThrowAnyException();
    }
}