package com.tourbhook.api.service;

import com.tourbhook.api.dto.auth.*;

import java.util.Map;

public interface AuthService {
    AuthResponse login(LoginRequest request);
    AuthResponse register(RegisterRequest request, String deviceId);
    AuthResponse googleLogin(GoogleAuthRequest request, String deviceId);
    TokenResponse refresh(RefreshRequest request);
    OtpResponse verifyOtp(VerifyOtpRequest request);
    OtpResponse forgotPassword(ForgotPasswordRequest request);
    OtpResponse resendOtp(ResendOtpRequest request);
    Map<String, Object> resetPassword(ResetPasswordRequest request);
    Map<String, Object> logout();
}
