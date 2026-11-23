package vn.bigcity.resident;

import vn.bigcity.RequestError;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

public final class ResidentRequests {
    public static final long MIN_DEPOSIT = 10_000;
    public static final long MAX_DEPOSIT = 10_000_000;
    private final Path requests;
    private final SecureRandom random = new SecureRandom();

    public ResidentRequests(Path runtime) throws IOException {
        this.requests = Files.createDirectories(runtime.resolve("requests"));
    }

    public synchronized Properties submitRequest(String kind, Map<String, String> form, String username) throws IOException {
        boolean application = kind.equals("application");
        String title = requiredText(form, "title", 3, 80);
        String body = requiredText(form, "body", application ? 40 : 10, 1500);
        String id = application ? "rp-" + username : "ticket-" + UUID.randomUUID();
        if (application) {
            if (!"yes".equals(form.get("rules"))) throw new RequestError(400, "Hãy xác nhận đã đọc luật nhập vai.");
            Properties previous = readRequest(id);
            if (previous != null && !"rejected".equals(previous.getProperty("status"))) {
                throw new RequestError(409, "Tài khoản đã có hồ sơ đang chờ hoặc đã được duyệt.");
            }
        } else if (listRequests(username, kind).stream().anyMatch(record -> "pending".equals(record.getProperty("status")))) {
            throw new RequestError(409, "Bạn đang có một yêu cầu hỗ trợ chờ phản hồi.");
        }
        Properties record = newRequest(id, kind, username);
        record.setProperty("title", title);
        record.setProperty("body", body);
        saveRequest(record);
        return record;
    }

    public synchronized Properties createDeposit(Map<String, String> form, String username, boolean paymentsEnabled) throws IOException {
        if (!paymentsEnabled) throw new RequestError(503, "BIG CITY chưa mở thanh toán qua payOS.");
        String amount = form.getOrDefault("amount", "");
        if (!amount.matches("[1-9][0-9]{0,7}") || Long.parseLong(amount) < MIN_DEPOSIT || Long.parseLong(amount) > MAX_DEPOSIT) {
            throw new RequestError(400, "Số tiền nạp phải từ 10.000 đến 10.000.000 đồng.");
        }
        if (listRequests(username, "deposit").stream().anyMatch(record -> "pending".equals(record.getProperty("status")))) {
            throw new RequestError(409, "Bạn đã có một yêu cầu nạp đang chờ đối soát.");
        }
        String orderCode;
        do {
            orderCode = Long.toString(random.nextLong(1_000_000_000_000L, 10_000_000_000_000L));
        } while (readRequest("payos-" + orderCode) != null);
        Properties record = newRequest("payos-" + orderCode, "deposit", username);
        record.setProperty("title", "Nạp tiền qua payOS");
        record.setProperty("provider", "payos");
        record.setProperty("amount", amount);
        record.setProperty("reference", orderCode);
        record.setProperty("expiresAt", Long.toString(now() + Duration.ofMinutes(30).toSeconds()));
        saveRequest(record);
        return record;
    }

    public synchronized Properties requestDepositCancellation(Map<String, String> form, String username, boolean paymentsEnabled) throws IOException {
        String id = form.getOrDefault("id", "");
        if (!id.matches("payos-[0-9]{13}")) throw new RequestError(404, "Không tìm thấy yêu cầu nạp.");
        Properties record = readRequest(id);
        if (record == null || !username.equals(record.getProperty("username"))) {
            throw new RequestError(404, "Không tìm thấy yêu cầu nạp.");
        }
        if (!paymentsEnabled || !"pending".equals(record.getProperty("status")) || !record.containsKey("linkId")
                || !"0".equals(record.getProperty("amountPaid", "0"))) {
            throw new RequestError(409, "Yêu cầu nạp này hiện không thể hủy.");
        }
        record.setProperty("cancelRequested", "true");
        saveRequest(record);
        return record;
    }

    public synchronized Properties reviewRequest(Map<String, String> form, String reviewer) throws IOException {
        String id = form.getOrDefault("id", "");
        if (!id.matches("rp-[a-z0-9_]{3,24}|(?:ticket|deposit)-[a-f0-9-]{36}|payos-[0-9]{13}")) {
            throw new RequestError(400, "Mã yêu cầu không hợp lệ.");
        }
        Properties record = readRequest(id);
        if (record == null) throw new RequestError(404, "Không tìm thấy yêu cầu.");
        if ("payos".equals(record.getProperty("provider"))) {
            throw new RequestError(409, "Giao dịch payOS do cổng thanh toán xác nhận.");
        }
        if (!"pending".equals(record.getProperty("status")) || !record.getProperty("revision").equals(form.get("revision"))) {
            throw new RequestError(409, "Yêu cầu đã thay đổi. Hãy tải lại danh sách.");
        }
        String status = form.getOrDefault("status", "");
        String kind = record.getProperty("kind");
        List<String> allowed = "support".equals(kind) ? List.of("answered") : List.of("approved", "rejected");
        if (!allowed.contains(status)) throw new RequestError(400, "Trạng thái không hợp lệ.");
        String reply = requiredText(form, "reply", status.equals("approved") ? 0 : 3, 1000);
        if (kind.equals("deposit") && status.equals("approved")) {
            verifyDeposit(form, record);
        }
        record.setProperty("status", status);
        record.setProperty("reply", reply);
        record.setProperty("reviewer", reviewer);
        record.setProperty("reviewedAt", Instant.now().toString());
        saveRequest(record);
        return record;
    }

    private void verifyDeposit(Map<String, String> form, Properties record) throws IOException {
        if (!record.getProperty("amount").equals(form.get("receivedAmount"))) {
            throw new RequestError(400, "Số tiền thực nhận phải khớp với yêu cầu nạp.");
        }
        String transaction = form.getOrDefault("transaction", "").strip().toUpperCase(Locale.ROOT);
        if (!transaction.matches("[A-Z0-9._/-]{3,100}")) {
            throw new RequestError(400, "Nhập mã giao dịch từ sao kê ngân hàng.");
        }
        if (listRequests(null, "deposit").stream().anyMatch(item -> transaction.equals(item.getProperty("transaction")))) {
            throw new RequestError(409, "Mã giao dịch này đã được dùng để duyệt một lần nạp.");
        }
        record.setProperty("transaction", transaction);
    }

    public List<Properties> listRequests(String username, String kind) throws IOException {
        if ("application".equals(kind)) {
            Properties record = readRequest("rp-" + username);
            return record == null ? List.of() : List.of(record);
        }
        List<Properties> records = new ArrayList<>();
        try (var files = Files.newDirectoryStream(requests, "*.properties")) {
            for (Path file : files) {
                Properties record = new Properties();
                try (InputStream input = Files.newInputStream(file)) {
                    record.load(input);
                }
                if ((username == null || username.equals(record.getProperty("username")))
                        && (kind == null || kind.equals(record.getProperty("kind")))) {
                    records.add(record);
                }
            }
        }
        records.sort(Comparator.comparing((Properties record) -> record.getProperty("createdAt")).reversed());
        return records;
    }

    public synchronized void saveCheckout(String id, String linkId, String checkout) throws IOException {
        Properties record = readRequest(id);
        record.setProperty("linkId", linkId);
        record.setProperty("checkoutUrl", checkout);
        saveRequest(record);
    }

    public synchronized void saveDepositState(String id, String status, long paid, String linkId, String checkout) throws IOException {
        Properties record = readRequest(id);
        if (!"pending".equals(record.getProperty("status"))) return;
        String amountPaid = Long.toString(paid);
        boolean newLink = !record.containsKey("linkId") && !linkId.isEmpty();
        if (!newLink && status.equals(record.getProperty("status"))
                && amountPaid.equals(record.getProperty("amountPaid", "0"))) return;
        if (newLink) {
            record.setProperty("linkId", linkId);
            record.setProperty("checkoutUrl", checkout);
        }
        record.setProperty("status", status);
        record.setProperty("amountPaid", amountPaid);
        if (paid > 0) record.remove("cancelRequested");
        if (!status.equals("pending")) record.setProperty("reviewedAt", Instant.now().toString());
        saveRequest(record);
    }

    private static Properties newRequest(String id, String kind, String username) {
        Properties record = new Properties();
        record.setProperty("id", id);
        record.setProperty("kind", kind);
        record.setProperty("username", username);
        record.setProperty("status", "pending");
        record.setProperty("createdAt", Instant.now().toString());
        record.setProperty("revision", UUID.randomUUID().toString());
        return record;
    }

    private static String requiredText(Map<String, String> form, String key, int min, int max) {
        String text = form.getOrDefault(key, "").strip();
        if (text.length() < min || text.length() > max) {
            throw new RequestError(400, "Kiểm tra lại độ dài các trường trong biểu mẫu.");
        }
        return text;
    }

    private Properties readRequest(String id) throws IOException {
        Path path = requests.resolve(id + ".properties");
        if (!Files.exists(path)) return null;
        Properties record = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            record.load(input);
        }
        return record;
    }

    private void saveRequest(Properties record) throws IOException {
        Path pending = Files.createTempFile(requests, "request-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(pending)) {
                record.store(output, null);
            }
            Files.move(pending, requests.resolve(record.getProperty("id") + ".properties"),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(pending);
        }
    }

    private static long now() { return System.currentTimeMillis() / 1000; }
}
