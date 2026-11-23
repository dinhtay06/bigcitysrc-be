package vn.bigcity.auth;

import vn.bigcity.RequestError;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class AccountService {
    public static final long SESSION_SECONDS = Duration.ofHours(8).toSeconds();
    private final Path accounts;
    private final List<String> admins;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Attempts> attempts = new HashMap<>();

    public record Login(String username, String token) {}
    private record Session(String username, long expiresAt) {}
    private record Attempts(int count, long expiresAt) {}

    public AccountService(Path runtime) throws IOException {
        this.accounts = Files.createDirectories(runtime.resolve("accounts"));
        this.admins = List.of(System.getenv().getOrDefault("BIGCITY_ADMINS", "")
                .toLowerCase(Locale.ROOT).split("\\s*,\\s*"));
    }

    public Login authenticate(Map<String, String> form, boolean register, String previousToken)
            throws IOException, GeneralSecurityException {
        String username = form.getOrDefault("username", "").toLowerCase(Locale.ROOT);
        String password = form.getOrDefault("password", "");
        if (!username.matches("[a-z0-9_]{3,24}") || password.length() < 10 || password.length() > 128) {
            throw new RequestError(400, "Kiểm tra lại tên tài khoản và độ dài mật khẩu.");
        }

        Path account = accounts.resolve(username + ".txt");
        if (register) {
            if (admins.contains(username) || !saveAccount(account, password)) {
                throw new RequestError(409, "Tên tài khoản đã được sử dụng hoặc đã được dành riêng.");
            }
        } else if (!verifyPassword(account, password)) {
            throw new RequestError(401, "Tên tài khoản hoặc mật khẩu không đúng.");
        }

        sessions.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now());
        sessions.remove(previousToken);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(32));
        sessions.put(token, new Session(username, now() + SESSION_SECONDS));
        return new Login(username, token);
    }

    public String activeUsername(String token) {
        Session session = sessions.get(token);
        return session != null && session.expiresAt() > now() ? session.username() : null;
    }

    public void logout(String token) {
        sessions.remove(token);
    }

    public boolean isAdmin(String username) {
        return admins.contains(username);
    }

    public synchronized boolean permitAttempt(String ip) {
        long now = now();
        attempts.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
        Attempts window = attempts.getOrDefault(ip, new Attempts(0, now + 60));
        if (window.count() >= 12) return false;
        attempts.put(ip, new Attempts(window.count() + 1, window.expiresAt()));
        return true;
    }

    private boolean saveAccount(Path account, String password) throws IOException, GeneralSecurityException {
        byte[] salt = randomBytes(16);
        Base64.Encoder encoder = Base64.getEncoder();
        String record = encoder.encodeToString(salt) + ":" + encoder.encodeToString(hash(password, salt));
        Path pending = Files.createTempFile(accounts, "account-", ".tmp");
        try {
            Files.writeString(pending, record);
            synchronized (accounts) {
                if (Files.exists(account)) return false;
                Files.move(pending, account, StandardCopyOption.ATOMIC_MOVE);
                return true;
            }
        } finally {
            Files.deleteIfExists(pending);
        }
    }

    private boolean verifyPassword(Path account, String password) throws IOException, GeneralSecurityException {
        if (!Files.exists(account)) {
            hash(password, new byte[16]);
            return false;
        }
        String[] record = Files.readString(account).split(":", 2);
        Base64.Decoder decoder = Base64.getDecoder();
        return MessageDigest.isEqual(hash(password, decoder.decode(record[0])), decoder.decode(record[1]));
    }

    private static byte[] hash(String password, byte[] salt) throws GeneralSecurityException {
        PBEKeySpec key = new PBEKeySpec(password.toCharArray(), salt, 600_000, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(key).getEncoded();
        } finally {
            key.clearPassword();
        }
    }

    private byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        random.nextBytes(bytes);
        return bytes;
    }

    private static long now() { return System.currentTimeMillis() / 1000; }
}
