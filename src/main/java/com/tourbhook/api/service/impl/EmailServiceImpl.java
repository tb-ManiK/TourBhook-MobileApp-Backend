
package com.tourbhook.api.service.impl;

import com.tourbhook.api.service.EmailService;
import com.tourbhook.api.service.MessageService;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

@Service
@RequiredArgsConstructor
public class EmailServiceImpl implements EmailService {

    private final JavaMailSender mailSender;
    private final MessageService messageService;

    @Value("${spring.mail.username}")
    private String senderEmail;

    @Override
    public void sendOtpEmail(
            String to,
            String otp,
            String languageCode,
            String userName
    ) {
        String subject = messageService.getForLanguage(
                "otp.email.subject", languageCode
        );

        String safeName = HtmlUtils.htmlEscape(
                userName == null || userName.isBlank()
                        ? "there" : userName.trim()
        );

        String safeOtp = HtmlUtils.htmlEscape(otp);

        String text = """
                TourBhook
                Verify Your Email Address

                Hello %s,

                Thanks for signing up for TourBhook! Please use the
                One-Time Password (OTP) below to verify your email address:

                %s

                This code is valid for the next 5 minutes.

                Please do not share this code with anyone. PRAMAHAR
                Technologies Private Limited employees will never ask
                you for this code.

                If you did not request this, please ignore this email.

                Best regards,
                The TourBhook Team

                © 2026 PRAMAHAR TECHNOLOGIES. All rights reserved.
                """.formatted(
                userName == null || userName.isBlank()
                        ? "there" : userName.trim(),
                otp
        );

        String html = """
                <!DOCTYPE html>
                <html>
                <body style="margin:0;padding:24px;background:#f4f6f8;
                             font-family:Arial,sans-serif;color:#202124;">
                  <div style="max-width:560px;margin:0 auto;background:#fff;
                              border-radius:12px;overflow:hidden;
                              border:1px solid #e5e7eb;">
                    <div style="background:#173f35;padding:24px;
                                text-align:center;color:#fff;">
                      <h1 style="margin:0;font-size:26px;">TourBhook</h1>
                    </div>
                    <div style="padding:30px 26px;">
                      <h2 style="margin-top:0;">Verify Your Email Address</h2>
                      <p>Hello %s,</p>
                      <p>Thanks for signing up for TourBhook! Please use the
                         One-Time Password (OTP) below to verify your
                         email address:</p>
                      <div style="margin:26px 0;padding:18px;
                                  background:#f0f7f4;border-radius:8px;
                                  text-align:center;">
                        <div style="font-size:32px;font-weight:bold;
                                    letter-spacing:8px;color:#173f35;">
                          %s
                        </div>
                      </div>
                      <p>This code is valid for the next <strong>5 minutes</strong>.</p>
                      <p style="font-size:14px;color:#555;">
                        Please do not share this code with anyone.
                        PRAMAHAR Technologies Private Limited employees
                        will never ask you for this code.
                      </p>
                      <p>If you did not request this, please ignore this email.</p>
                      <p>Best regards,<br><strong>The TourBhook Team</strong></p>
                    </div>
                    <div style="padding:16px;text-align:center;
                                background:#f8faf9;color:#777;font-size:12px;">
                      © 2026 PRAMAHAR TECHNOLOGIES. All rights reserved.
                    </div>
                  </div>
                </body>
                </html>
                """.formatted(safeName, safeOtp);

        try {
            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(
                    mimeMessage, true, "UTF-8"
            );
            helper.setFrom(senderEmail);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(text, html);
            mailSender.send(mimeMessage);
        } catch (Exception e) {
            org.slf4j.LoggerFactory
                    .getLogger(EmailServiceImpl.class)
                    .error("Failed to send OTP email. Cause: {}", e.getMessage(), e);

            throw new IllegalStateException("Failed to send OTP email", e);
        }
    }
}
