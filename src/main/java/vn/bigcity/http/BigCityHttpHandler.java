package vn.bigcity.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import vn.bigcity.RequestError;
import vn.bigcity.auth.AccountService;
import vn.bigcity.payment.PayOSPayments;
import vn.bigcity.resident.ResidentRequests;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

public final class BigCityHttpHandler implements HttpHandler {
    private static final String JSON = "application/json; charset=utf-8";
    private static final String COOKIE = "bigcity_session";
    private final Path frontend;
    private final Path data;
    private final String origin;
    private final AccountService accounts;
    private final ResidentRequests requests;
    private final PayOSPayments payments;

    public BigCityHttpHandler(Path frontend, Path data, String origin, AccountService accounts,
                              ResidentRequests requests, PayOSPayments payments) {
        this.frontend = frontend.toAbsolutePath().normalize();
        this.data = data;
        this.origin = origin;
        this.accounts = accounts;
        this.requests = requests;
        this.payments = payments;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            try {
                String path = exchange.getRequestURI().getPath();
                if (path.startsWith("/api/")) api(exchange, path);
                else staticFile(exchange, path);
            } catch (RequestError ex) {
                error(exchange, ex.status(), ex.getMessage());
            } catch (IllegalArgumentException ex) {
                error(exchange, 400, "Yêu cầu không hợp lệ.");
            } catch (IOException | GeneralSecurityException ex) {
                System.err.println(ex.getClass().getSimpleName() + ": " + ex.getMessage());
                error(exchange, 500, "Máy chủ đang gặp sự cố.");
            }
        }
    }

    private void api(HttpExchange exchange, String path) throws IOException, GeneralSecurityException {
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        String method = exchange.getRequestMethod();
        switch (path) {
            case "/api/site", "/api/news", "/api/rules", "/api/session" -> {
                if (!method.equals("GET")) {
                    methodNotAllowed(exchange, "GET");
                    return;
                }
                if (path.equals("/api/session")) {
                    String username = accounts.activeUsername(sessionToken(exchange));
                    sendJson(exchange, 200, username == null ? "{}" : userJson(username));
                } else {
                    String file = path.substring("/api/".length()) + ".json";
                    send(exchange, 200, JSON, Files.readAllBytes(data.resolve(file)));
                }
            }
            case "/api/resident/application", "/api/resident/support", "/api/resident/deposits",
                    "/api/resident/deposits/cancel", "/api/staff/requests" ->
                    residentApi(exchange, path, method);
            case "/api/login", "/api/register", "/api/logout" -> {
                if (!method.equals("POST")) {
                    methodNotAllowed(exchange, "POST");
                    return;
                }
                if (!origin.equals(exchange.getRequestHeaders().getFirst("Origin"))) {
                    error(exchange, 403, "Yêu cầu phải đến từ trang BIG CITY.");
                    return;
                }
                if (path.equals("/api/logout")) {
                    accounts.logout(sessionToken(exchange));
                    setCookie(exchange, "", 0);
                    sendJson(exchange, 200, "{}");
                } else {
                    authenticate(exchange, path.equals("/api/register"));
                }
            }
            default -> error(exchange, 404, "Không tìm thấy API.");
        }
    }

    private void authenticate(HttpExchange exchange, boolean register) throws IOException, GeneralSecurityException {
        String ip = exchange.getRemoteAddress().getAddress().getHostAddress();
        if (!accounts.permitAttempt(ip)) {
            error(exchange, 429, "Quá nhiều lượt thử. Vui lòng đợi một phút.");
            return;
        }
        Map<String, String> form = readForm(exchange, 4096);
        AccountService.Login login = accounts.authenticate(form, register, sessionToken(exchange));
        setCookie(exchange, login.token(), AccountService.SESSION_SECONDS);
        sendJson(exchange, register ? 201 : 200, userJson(login.username()));
    }

    private void residentApi(HttpExchange exchange, String path, String method) throws IOException {
        if (!method.equals("GET") && !method.equals("POST")) {
            methodNotAllowed(exchange, "GET, POST");
            return;
        }
        String username = accounts.activeUsername(sessionToken(exchange));
        if (username == null) throw new RequestError(401, "Vui lòng đăng nhập vào cổng cư dân.");
        boolean staff = path.equals("/api/staff/requests");
        if (staff && !accounts.isAdmin(username)) throw new RequestError(403, "Bạn không có quyền quản trị.");
        boolean cancelDeposit = path.equals("/api/resident/deposits/cancel");
        if (cancelDeposit && !method.equals("POST")) {
            methodNotAllowed(exchange, "POST");
            return;
        }
        String kind = switch (path) {
            case "/api/resident/application" -> "application";
            case "/api/resident/deposits" -> "deposit";
            default -> "support";
        };
        if (method.equals("GET")) {
            List<Properties> records = requests.listRequests(staff ? null : username, staff ? null : kind);
            String items = records.stream().map(BigCityHttpHandler::requestJson).collect(Collectors.joining(",", "[", "]"));
            if (kind.equals("deposit")) {
                long balance = records.stream().filter(record -> "approved".equals(record.getProperty("status")))
                        .mapToLong(record -> Long.parseLong(record.getProperty("amount"))).sum();
                sendJson(exchange, 200, "{\"enabled\":" + payments.enabled() + ",\"balance\":" + balance
                        + ",\"min\":" + ResidentRequests.MIN_DEPOSIT + ",\"max\":" + ResidentRequests.MAX_DEPOSIT + ",\"items\":" + items + "}");
            } else {
                sendJson(exchange, 200, items);
            }
            return;
        }
        if (!origin.equals(exchange.getRequestHeaders().getFirst("Origin"))) {
            throw new RequestError(403, "Yêu cầu phải đến từ trang BIG CITY.");
        }
        Map<String, String> form = readForm(exchange, 24_576);
        Properties record;
        if (staff) record = requests.reviewRequest(form, username);
        else if (cancelDeposit) record = requests.requestDepositCancellation(form, username, payments.enabled());
        else if (kind.equals("deposit")) record = requests.createDeposit(form, username, payments.enabled());
        else record = requests.submitRequest(kind, form, username);
        sendJson(exchange, staff || cancelDeposit ? 200 : 201, requestJson(record));
    }

    private static Map<String, String> readForm(HttpExchange exchange, int limit) throws IOException {
        String type = exchange.getRequestHeaders().getFirst("Content-Type");
        if (type == null || !type.split(";", 2)[0].equalsIgnoreCase("application/x-www-form-urlencoded")) {
            throw new RequestError(415, "Định dạng dữ liệu không hợp lệ.");
        }
        byte[] bytes = exchange.getRequestBody().readNBytes(limit + 1);
        if (bytes.length > limit) throw new RequestError(413, "Dữ liệu quá dài.");
        Map<String, String> form = new HashMap<>();
        for (String field : new String(bytes, StandardCharsets.UTF_8).split("&")) {
            String[] pair = field.split("=", 2);
            if (pair.length == 2) form.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
        }
        return form;
    }

    private static String sessionToken(HttpExchange exchange) {
        for (String header : exchange.getRequestHeaders().getOrDefault("Cookie", java.util.List.of())) {
            for (String field : header.split(";")) {
                String cookie = field.trim();
                if (cookie.startsWith(COOKIE + "=")) return cookie.substring(COOKIE.length() + 1);
            }
        }
        return "";
    }

    private void setCookie(HttpExchange exchange, String token, long maxAge) {
        String secure = origin.startsWith("https://") ? "; Secure" : "";
        exchange.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + token
                + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=" + maxAge + secure);
    }

    private void staticFile(HttpExchange exchange, String path) throws IOException {
        if (!exchange.getRequestMethod().equals("GET")) {
            methodNotAllowed(exchange, "GET");
            return;
        }
        Path file = frontend.resolve(path.equals("/") ? "index.html" : path.substring(1)).normalize();
        if (!file.startsWith(frontend) || !Files.isRegularFile(file)) {
            error(exchange, 404, "Không tìm thấy trang.");
            return;
        }
        String name = file.getFileName().toString();
        String type = switch (name.substring(name.lastIndexOf('.') + 1)) {
            case "html" -> "text/html; charset=utf-8";
            case "css" -> "text/css; charset=utf-8";
            case "js" -> "text/javascript; charset=utf-8";
            case "png" -> "image/png";
            case "json" -> JSON;
            default -> "application/octet-stream";
        };
        send(exchange, 200, type, Files.readAllBytes(file));
    }

    private static String requestJson(Properties record) {
        List<String> keys = new ArrayList<>(List.of("id", "kind", "username", "title", "body",
                "status", "createdAt", "revision", "reply", "reviewedAt"));
        if ("deposit".equals(record.getProperty("kind"))) {
            keys.addAll(List.of("amount", "reference", "bank", "account", "holder", "provider",
                    "checkoutUrl", "amountPaid", "cancelRequested", "expiresAt"));
        }
        return keys.stream().map(key -> quote(key) + ":" + quote(record.getProperty(key, "")))
                .collect(Collectors.joining(",", "{", "}"));
    }

    private String userJson(String username) {
        return "{\"username\":" + quote(username) + ",\"admin\":" + accounts.isAdmin(username) + "}";
    }

    private static String quote(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            if (ch == '"' || ch == '\\') result.append('\\').append(ch);
            else if (ch < 32) result.append(String.format("\\u%04x", (int) ch));
            else result.append(ch);
        }
        return result.append('"').toString();
    }

    private static void methodNotAllowed(HttpExchange exchange, String method) throws IOException {
        exchange.getResponseHeaders().set("Allow", method);
        error(exchange, 405, "Phương thức không được hỗ trợ.");
    }

    private static void error(HttpExchange exchange, int status, String message) throws IOException {
        sendJson(exchange, status, "{\"error\":" + quote(message) + "}");
    }

    private static void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        send(exchange, status, JSON, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }
}
