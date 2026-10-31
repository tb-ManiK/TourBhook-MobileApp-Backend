package com.tourbhook.api.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.razorpay.Order;
import com.razorpay.RazorpayClient;
import com.tourbhook.api.config.RazorpayProperties;
import com.tourbhook.api.dto.payment.CreatePaymentOrderRequest;
import com.tourbhook.api.dto.payment.InvoiceMetaResponse;
import com.tourbhook.api.dto.payment.PaymentOrderResponse;
import com.tourbhook.api.dto.payment.PaymentWebhookResponse;
import com.tourbhook.api.dto.payment.VerifyPaymentRequest;
import com.tourbhook.api.dto.payment.VerifyPaymentResponse;
import com.tourbhook.api.entity.PaymentOrder;
import com.tourbhook.api.entity.Subscription;
import com.tourbhook.api.entity.User;

import com.tourbhook.api.repository.PaymentOrderRepository;
import com.tourbhook.api.repository.SubscriptionRepository;
import com.tourbhook.api.repository.UserRepository;
import com.tourbhook.api.repository.exception.BadRequestException;
import com.tourbhook.api.repository.exception.ResourceNotFoundException;
import com.tourbhook.api.service.AuthenticatedUserService;
import com.tourbhook.api.service.PaymentGatewayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class PaymentGatewayServiceImpl implements PaymentGatewayService {

    private static final String PREMIUM_PLAN = "premium_plan";
    private static final String ENTERPRISE_PLAN = "enterprise_plan";
    private static final String LIFETIME_PLAN = "lifetime_plan";

    private static final Map<String, Integer> PLAN_AMOUNTS = Map.of(
            PREMIUM_PLAN,  149*100,
            ENTERPRISE_PLAN, 149*100,
            LIFETIME_PLAN, 149*100
    );

    private static final Map<String, List<String>> PLAN_FEATURES = Map.of(
            PREMIUM_PLAN, List.of("unlimited_trips", "smart_itinerary", "priority_support"),
            ENTERPRISE_PLAN, List.of("unlimited_trips", "smart_itinerary", "priority_support", "offline_exports"),
            LIFETIME_PLAN, List.of("unlimited_trips", "smart_itinerary", "priority_support", "offline_exports", "family_mode")
    );

    private final RazorpayClient razorpayClient;
    private final RazorpayProperties razorpayProperties;
    private final PaymentOrderRepository paymentOrderRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final UserRepository userRepository;
    private final AuthenticatedUserService authenticatedUserService;
    private final ObjectMapper objectMapper;

    @Override
    public PaymentOrderResponse createOrder(CreatePaymentOrderRequest request) {
        User user = authenticatedUserService.getCurrentUser();

        String normalizedPlanCode = normalizePlanCode(request.planCode());
        log.info("planCode raw={}, normalized={}", request.planCode(), normalizedPlanCode);

        Integer amount = PLAN_AMOUNTS.get(normalizedPlanCode);
        if (amount == null) {
            throw new BadRequestException("Unsupported planCode. Allowed values: [Premium_plan, LifeTime_plan, Enterprise_plan]");
        }

        try {
            String receipt = "rcpt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 18);

            JSONObject payload = new JSONObject();
            payload.put("amount", amount);
            payload.put("currency", razorpayProperties.getCurrency());
            payload.put("receipt", receipt);

            JSONObject notes = new JSONObject();
            notes.put("userId", user.getId());
            notes.put("planCode", normalizedPlanCode);
            notes.put("email", user.getEmail());
            payload.put("notes", notes);

            Order order = razorpayClient.orders.create(payload);

            PaymentOrder paymentOrder = paymentOrderRepository.save(
                    PaymentOrder.builder()
                            .user(user)
                            .planCode(normalizedPlanCode)
                            .amount(amount)
                            .currency(razorpayProperties.getCurrency())
                            .status("CREATED")
                            .razorpayOrderId(order.get("id"))
                            .receipt(receipt)
                            .notesJson(notes.toString())
                            .gatewayResponse(order.toString())
                            .build()
            );

            return map(paymentOrder, true);
        } catch (Exception ex) {
            log.error("Failed to create Razorpay order", ex);
            throw new BadRequestException("Unable to create payment order");
        }
    }

    @Override
    public VerifyPaymentResponse verifyPayment(VerifyPaymentRequest request) {
        PaymentOrder order = paymentOrderRepository.findByRazorpayOrderId(request.razorpayOrderId())
                .orElseThrow(() -> new ResourceNotFoundException("Payment order not found"));

        User currentUser = authenticatedUserService.getCurrentUser();

        if (!order.getUser().getId().equals(currentUser.getId())) {
            throw new BadRequestException(
                    "You are not allowed to verify this payment order"
            );
        }

        String data = request.razorpayOrderId() + "|" + request.razorpayPaymentId();
        String generatedSignature = hmacSha256Hex(data, razorpayProperties.getKeySecret());

        if (!generatedSignature.equals(request.razorpaySignature())) {
            order.setStatus("FAILED");
            order.setRazorpayPaymentId(request.razorpayPaymentId());
            order.setRazorpaySignature(request.razorpaySignature());
            paymentOrderRepository.save(order);
            throw new BadRequestException("Invalid payment signature");
        }

        order.setStatus("PAID");
        order.setRazorpayPaymentId(request.razorpayPaymentId());
        order.setRazorpaySignature(request.razorpaySignature());
        order.setPaidAt(Instant.now());
        paymentOrderRepository.save(order);

        activateSubscription(order);

        return new VerifyPaymentResponse(
                order.getRazorpayOrderId(),
                order.getRazorpayPaymentId(),
                order.getStatus(),
                order.getPlanCode(),
                order.getPaidAt()
        );
    }

    @Override
    public PaymentWebhookResponse processWebhook(String signature, String payload) {
        if (signature == null || signature.isBlank()) {
            throw new BadRequestException("Missing X-Razorpay-Signature header");
        }

        String expected = hmacSha256Hex(payload, razorpayProperties.getWebhookSecret());
        if (!expected.equals(signature)) {
            throw new BadRequestException("Invalid webhook signature");
        }

        try {
            JsonNode root = objectMapper.readTree(payload);
            String event = root.path("event").asText();
            JsonNode entity = root.path("payload").path("payment").path("entity");

            String paymentId = entity.path("id").asText(null);
            String orderId = entity.path("order_id").asText(null);
            String status = entity.path("status").asText(null);

            if (orderId != null) {
                paymentOrderRepository.findByRazorpayOrderId(orderId).ifPresent(order -> {
                    order.setRazorpayPaymentId(paymentId);
                    order.setGatewayResponse(payload);

                    if ("captured".equalsIgnoreCase(status)) {
                        order.setStatus("PAID");
                        if (order.getPaidAt() == null) {
                            order.setPaidAt(Instant.now());
                        }
                        activateSubscription(order);
                    } else if (status != null && !status.isBlank()) {
                        order.setStatus(status.toUpperCase());
                    }

                    paymentOrderRepository.save(order);
                });
            }

            return new PaymentWebhookResponse(true, event, orderId, paymentId, status);
        } catch (JsonProcessingException ex) {
            throw new BadRequestException("Invalid webhook payload");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<PaymentOrderResponse> getMyOrders() {
        return paymentOrderRepository.findByUserOrderByCreatedAtDesc(authenticatedUserService.getCurrentUser())
                .stream()
                .map(order -> map(order, false))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentOrderResponse getOrder(String id) {
        PaymentOrder order = getOwnedOrder(id);
        return map(order, false);
    }

    @Override
    @Transactional(readOnly = true)
    public InvoiceMetaResponse getInvoiceMeta(String id) {
        PaymentOrder order = getOwnedOrder(id);

        return new InvoiceMetaResponse(
                order.getId(),
                buildInvoiceNumber(order),
                order.getReceipt(),
                order.getPlanCode(),
                order.getAmount(),
                order.getCurrency(),
                order.getStatus(),
                order.getPaidAt(),
                order.getCreatedAt() == null ? null : order.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant(),
                "/api/v1/payments/orders/" + order.getId() + "/invoice"
        );
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] downloadInvoice(String id) {
        PaymentOrder order = getOwnedOrder(id);

        if (!"PAID".equalsIgnoreCase(order.getStatus())) {
            throw new BadRequestException("Invoice can only be downloaded for paid orders");
        }

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4, 36, 36, 36, 36);
            PdfWriter.getInstance(document, out);
            document.open();

            Font titleFont = new Font(Font.HELVETICA, 18, Font.BOLD);
            Font sectionFont = new Font(Font.HELVETICA, 12, Font.BOLD);
            Font bodyFont = new Font(Font.HELVETICA, 11, Font.NORMAL);

            Paragraph title = new Paragraph("Invoice", titleFont);
            title.setSpacingAfter(12f);
            document.add(title);

            document.add(new Paragraph("Invoice No: " + buildInvoiceNumber(order), bodyFont));
            document.add(new Paragraph("Receipt: " + safe(order.getReceipt()), bodyFont));
            document.add(new Paragraph("Order ID: " + safe(order.getId()), bodyFont));
            document.add(new Paragraph("Issued At: " + formatInstant(
                    order.getPaidAt() != null
                            ? order.getPaidAt()
                            : order.getCreatedAt().atZone(ZoneId.of("Asia/Kolkata")).toInstant()
            ), bodyFont));
            document.add(Chunk.NEWLINE);

            document.add(new Paragraph("Billed To", sectionFont));
            document.add(new Paragraph("Name: " + safe(order.getUser().getName()), bodyFont));
            document.add(new Paragraph("Email: " + safe(order.getUser().getEmail()), bodyFont));
            document.add(new Paragraph("Phone: " + safe(order.getUser().getPhone()), bodyFont));

            document.add(Chunk.NEWLINE);

            PdfPTable table = new PdfPTable(5);
            table.setWidthPercentage(100);
            table.setSpacingBefore(10f);
            table.setSpacingAfter(10f);
            table.setWidths(new float[]{3.5f, 2.5f, 2.0f, 2.0f,2.0f});

            addHeader(table, "Description");
            addHeader(table, "payementId");
            addHeader(table, "Plan");
            addHeader(table, "Status");
            addHeader(table, "Amount");

            table.addCell(cell("TourBhook Subscription"));
            table.addCell(cell(order.getRazorpayPaymentId()));        
            table.addCell(cell(order.getPlanCode()));
            table.addCell(cell(order.getStatus()));
            table.addCell(cell(formatMoney(order.getAmount(), order.getCurrency())));

            document.add(table);
            document.add(Chunk.NEWLINE);
            document.add(new Paragraph("Thank you for your payment.", bodyFont));
            document.close();

            return out.toByteArray();
        } catch (Exception ex) {
            log.error("Failed to generate invoice PDF for order {}", id, ex);
            throw new BadRequestException("Unable to generate invoice");
        }
    }

    private PaymentOrder getOwnedOrder(String id) {
        PaymentOrder order = paymentOrderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Payment order not found"));

        String currentUserId = authenticatedUserService.getCurrentUser().getId();
        if (!order.getUser().getId().equals(currentUserId)) {
            throw new BadRequestException("You are not allowed to access this payment order");
        }

        return order;
    }

    private PaymentOrderResponse map(PaymentOrder order, boolean includeRazorpayKey) {
        return new PaymentOrderResponse(
                order.getId(),
                order.getPlanCode(),
                order.getAmount(),
                order.getCurrency(),
                order.getStatus(),
                includeRazorpayKey ? razorpayProperties.getKeyId() : null,
                order.getRazorpayOrderId(),
                order.getReceipt(),
                order.getPaidAt(),
                order.getCreatedAt()
        );
    }

    private void activateSubscription(PaymentOrder order) {
        User user = userRepository.findById(order.getUser().getId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        user.setPremium(true);
        userRepository.save(user);

        Subscription subscription = subscriptionRepository.findByUser(user)
                .orElseGet(() -> Subscription.builder().user(user).build());

        String normalizedPlanCode = normalizePlanCode(order.getPlanCode());
        subscription.setPlan(normalizedPlanCode);
        subscription.setStatus("active");
        subscription.setRenewsAt(calculateRenewalDate(normalizedPlanCode));
        subscription.setFeatures(PLAN_FEATURES.getOrDefault(normalizedPlanCode, List.of("unlimited_trips")));

        subscriptionRepository.save(subscription);
    }

    private LocalDate calculateRenewalDate(String planCode) {
        return switch (normalizePlanCode(planCode)) {
            case PREMIUM_PLAN -> LocalDate.now().plusMonths(1);
            case ENTERPRISE_PLAN -> LocalDate.now().plusMonths(3);
            case LIFETIME_PLAN -> LocalDate.now().plusYears(1);
            default -> throw new BadRequestException("Unsupported planCode: " + planCode);
        };
    }

    private String normalizePlanCode(String planCode) {
        return planCode == null ? "" : planCode.trim().toLowerCase();
    }

    private String hmacSha256Hex(String payload, String secret) {
        try {
            Mac sha256 = Mac.getInstance("HmacSHA256");
            sha256.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(sha256.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new BadRequestException("Unable to validate signature");
        }
    }

    private String buildInvoiceNumber(PaymentOrder order) {
        return "INV-" + order.getCreatedAt().toLocalDate().toString().replace("-", "")
                + "-" + order.getId().substring(0, 8).toUpperCase();
    }

    private String formatMoney(Integer amount, String currency) {
        double value = amount == null ? 0.0 : amount / 100.0;
        return "INR " + String.format("%.2f", value);
    }

    private String formatInstant(Instant instant) {
        if (instant == null) {
            return "-";
        }
        return DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a")
                .withZone(ZoneId.of("Asia/Kolkata"))
                .format(instant);
    }

    private void addHeader(PdfPTable table, String value) {
        PdfPCell cell = new PdfPCell(new Phrase(value));
        cell.setPadding(8f);
        table.addCell(cell);
    }

    private PdfPCell cell(String value) {
        PdfPCell cell = new PdfPCell(new Phrase(safe(value)));
        cell.setPadding(8f);
        return cell;
    }
    

    private String safe(String value) {
        return value == null ? "-" : value;
    }
}