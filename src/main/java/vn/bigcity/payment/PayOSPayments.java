package vn.bigcity.payment;

import vn.bigcity.resident.ResidentRequests;
import vn.payos.PayOS;
import vn.payos.core.ClientOptions;
import vn.payos.exception.APIException;
import vn.payos.exception.PayOSException;
import vn.payos.model.v2.paymentRequests.CreatePaymentLinkRequest;
import vn.payos.model.v2.paymentRequests.PaymentLink;
import vn.payos.model.v2.paymentRequests.PaymentLinkStatus;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;

public final class PayOSPayments {
    private final ResidentRequests requests;
    private final String origin;
    private final PayOS payos;

    private PayOSPayments(ResidentRequests requests, String origin, PayOS payos) {
        this.requests = requests;
        this.origin = origin;
        this.payos = payos;
    }

    public boolean enabled() {
        return payos != null;
    }

    public static PayOSPayments fromEnvironment(ResidentRequests requests, String origin) {
        String clientId = System.getenv().getOrDefault("PAYOS_CLIENT_ID", "").strip();
        String apiKey = System.getenv().getOrDefault("PAYOS_API_KEY", "").strip();
        String checksum = System.getenv().getOrDefault("PAYOS_CHECKSUM_KEY", "").strip();
        if (clientId.isBlank() || apiKey.isBlank() || checksum.isBlank()) {
            return new PayOSPayments(requests, origin, null);
        }
        PayOS payos = new PayOS(ClientOptions.builder().clientId(clientId).apiKey(apiKey).checksumKey(checksum)
                .baseURL("https://api-merchant.payos.vn").timeoutMs(5_000).maxRetries(0)
                .logLevel(ClientOptions.LogLevel.NONE).build());
        return new PayOSPayments(requests, origin, payos);
    }

    public void syncDeposits() {
        try {
            for (Properties deposit : requests.listRequests(null, "deposit").reversed()) {
                if (!"payos".equals(deposit.getProperty("provider")) || !"pending".equals(deposit.getProperty("status"))) continue;
                try {
                    syncDeposit(deposit);
                } catch (RuntimeException | IOException failure) {
                    System.err.println("payOS " + deposit.getProperty("id") + ": " + failure.getClass().getSimpleName());
                }
            }
        } catch (IOException failure) {
            System.err.println("Không thể đọc danh sách nạp tiền payOS.");
        }
    }

    private void syncDeposit(Properties deposit) throws IOException {
        long orderCode = Long.parseLong(deposit.getProperty("reference"));
        long amount = Long.parseLong(deposit.getProperty("amount"));
        long expiresAt = Long.parseLong(deposit.getProperty("expiresAt"));
        if (!deposit.containsKey("linkId") && now() < expiresAt) {
            try {
                String returnUrl = origin + "/?wallet=1";
                String description = Long.toString(orderCode, 36).toUpperCase(Locale.ROOT);
                var link = payos.paymentRequests().create(CreatePaymentLinkRequest.builder()
                        .orderCode(orderCode).amount(amount).description(description)
                        .returnUrl(returnUrl).cancelUrl(returnUrl).expiredAt(expiresAt).build());
                if (!Objects.equals(link.getOrderCode(), orderCode) || !Objects.equals(link.getAmount(), amount)
                        || !"VND".equals(link.getCurrency())) {
                    throw new IOException("Unexpected payOS checkout");
                }
                validateCheckout(link.getPaymentLinkId(), link.getCheckoutUrl());
                requests.saveCheckout(deposit.getProperty("id"), link.getPaymentLinkId(), link.getCheckoutUrl());
                return;
            } catch (PayOSException createFailure) {
                // The remote request may have succeeded before the connection failed.
                // Recover by querying the same persisted merchant order code.
            }
        }
        PaymentLink link;
        try {
            link = payos.paymentRequests().get(orderCode);
        } catch (APIException failure) {
            if (!deposit.containsKey("linkId") && now() >= expiresAt
                    && failure.getStatusCode().filter(code -> code == 404).isPresent()) {
                requests.saveDepositState(deposit.getProperty("id"), "expired", 0, "", "");
                return;
            }
            throw failure;
        }
        if ("true".equals(deposit.getProperty("cancelRequested"))
                && link.getStatus() == PaymentLinkStatus.PENDING
                && Objects.equals(link.getAmountPaid(), 0L)
                && Objects.equals(link.getOrderCode(), orderCode) && Objects.equals(link.getAmount(), amount)
                && Objects.equals(link.getId(), deposit.getProperty("linkId"))) {
            link = payos.paymentRequests().cancel(orderCode, "BIG CITY: User requested cancellation");
        }
        if (!Objects.equals(link.getOrderCode(), orderCode) || !Objects.equals(link.getAmount(), amount)
                || link.getId() == null || !link.getId().matches("[a-fA-F0-9]{32}")
                || (deposit.containsKey("linkId") && !link.getId().equals(deposit.getProperty("linkId")))
                || link.getAmountPaid() == null || link.getAmountPaid() < 0 || link.getStatus() == null) {
            throw new IOException("Unexpected payOS payment details");
        }
        String status = switch (link.getStatus()) {
            case PAID -> link.getAmountPaid() == amount && Objects.equals(link.getAmountRemaining(), 0L)
                    ? "approved" : "payment_review";
            case CANCELLED, EXPIRED, FAILED -> link.getAmountPaid() > 0 ? "payment_review" : switch (link.getStatus()) {
                case CANCELLED -> "cancelled";
                case EXPIRED -> "expired";
                default -> "rejected";
            };
            default -> "pending";
        };
        requests.saveDepositState(deposit.getProperty("id"), status, link.getAmountPaid(), link.getId(),
                "https://pay.payos.vn/web/" + link.getId());
    }

    private static void validateCheckout(String linkId, String checkout) throws IOException {
        URI uri = URI.create(checkout == null ? "" : checkout);
        if (linkId == null || !linkId.matches("[a-fA-F0-9]{32}") || !"https".equals(uri.getScheme())
                || !"pay.payos.vn".equals(uri.getHost()) || uri.getUserInfo() != null || uri.getPort() != -1) {
            throw new IOException("Invalid payOS checkout URL");
        }
    }

    private static long now() { return System.currentTimeMillis() / 1000; }
}
