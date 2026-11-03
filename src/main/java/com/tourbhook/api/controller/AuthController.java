package com.tourbhook.api.controller;
import com.tourbhook.api.dto.auth.*;
import com.tourbhook.api.dto.common.ApiResponse;
import com.tourbhook.api.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

	private final AuthService authService;
	private static final String DEVICE_ID_HEADER = "X-Device-Id";

	@PostMapping("/login")
	public ResponseEntity<ApiResponse<AuthResponse>> login(@Valid @RequestBody LoginRequest request) {
		AuthResponse response = authService.login(request);
		return ResponseEntity.ok(ApiResponse.<AuthResponse>builder().success(true).message("Login successful")
				.data(response).timestamp(LocalDateTime.now()).path("/api/v1/auth/login").build());
	}

	@PostMapping("/register")
	public ResponseEntity<ApiResponse<AuthResponse>> register(@Valid @RequestBody RegisterRequest request, @RequestHeader(value = DEVICE_ID_HEADER, required = false) String deviceId) {
		AuthResponse response = authService.register(request, deviceId);
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(ApiResponse.<AuthResponse>builder().success(true).message("Registration successful")
						.data(response).timestamp(LocalDateTime.now()).path("/api/v1/auth/register").build());
	}

	@PostMapping("/google")
	public ResponseEntity<ApiResponse<AuthResponse>> google(@Valid @RequestBody GoogleAuthRequest request,@RequestHeader(value = DEVICE_ID_HEADER, required = false) String deviceId) {
		AuthResponse response = authService.googleLogin(request, deviceId);
		return ResponseEntity.ok(ApiResponse.<AuthResponse>builder().success(true).message("Google sign-in successful")
				.data(response).timestamp(LocalDateTime.now()).path("/api/v1/auth/google").build());
	}

	@PostMapping("/refresh")
	public ResponseEntity<ApiResponse<TokenResponse>> refresh(@Valid @RequestBody RefreshRequest request) {
		TokenResponse response = authService.refresh(request);
		return ResponseEntity
				.ok(ApiResponse.<TokenResponse>builder().success(true).message("Token refreshed successfully")
						.data(response).timestamp(LocalDateTime.now()).path("/api/v1/auth/refresh").build());
	}

	@PostMapping("/verify-otp")
	public ResponseEntity<ApiResponse<OtpResponse>> verifyOtp(@Valid @RequestBody VerifyOtpRequest request) {
		OtpResponse response = authService.verifyOtp(request);
		return ResponseEntity.ok(ApiResponse.<OtpResponse>builder().success(true).message("OTP verified successfully")
				.data(response).timestamp(LocalDateTime.now()).path("/api/v1/auth/verify-otp").build());
	}

	@PostMapping("/forgot-password")
	public ResponseEntity<ApiResponse<OtpResponse>> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
		OtpResponse response = authService.forgotPassword(request);
		return ResponseEntity.ok(ApiResponse.<OtpResponse>builder().success(true).message("OTP sent successfully")
				.data(response).timestamp(LocalDateTime.now()).path("/api/v1/auth/forgot-password").build());
	}

	@PostMapping("/resend-otp")
	public ResponseEntity<ApiResponse<OtpResponse>> resendOtp(@Valid @RequestBody ResendOtpRequest request) {
		OtpResponse response = authService.resendOtp(request);
		return ResponseEntity.ok(ApiResponse.<OtpResponse>builder().success(true).message("OTP resent successfully")
				.data(response).timestamp(LocalDateTime.now()).path("/api/v1/auth/resend-otp").build());
	}

	@PostMapping("/reset-password")
	public ResponseEntity<ApiResponse<Map<String, Object>>> resetPassword(
			@Valid @RequestBody ResetPasswordRequest request) {
		Map<String, Object> response = authService.resetPassword(request);
		return ResponseEntity
				.ok(ApiResponse.<Map<String, Object>>builder().success(true).message("Password reset successful")
						.data(response).timestamp(LocalDateTime.now()).path("/api/v1/auth/reset-password").build());
	}

	@PostMapping("/logout")
	public ResponseEntity<ApiResponse<Map<String, Object>>> logout() {
		Map<String, Object> response = authService.logout();
		return ResponseEntity.ok(ApiResponse.<Map<String, Object>>builder().success(true).message("Logout successful")
				.data(response).timestamp(LocalDateTime.now()).path("/api/v1/auth/logout").build());
	}
}
