
package com.tourbhook.api.service;

public interface EmailService {

    default void sendOtpEmail(String to, String otp) {
        sendOtpEmail(to, otp, "en");
    }

    default void sendOtpEmail(String to, String otp, String languageCode) {
        sendOtpEmail(to, otp, languageCode, null);
    }

    void sendOtpEmail(
            String to,
            String otp,
            String languageCode,
            String userName
    );
}
