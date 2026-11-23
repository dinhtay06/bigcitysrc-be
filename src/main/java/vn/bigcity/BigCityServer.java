package vn.bigcity;

import com.sun.net.httpserver.HttpServer;
import vn.bigcity.auth.AccountService;
import vn.bigcity.http.BigCityHttpHandler;
import vn.bigcity.payment.PayOSPayments;
import vn.bigcity.resident.ResidentRequests;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class BigCityServer {
    private BigCityServer() {}

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        String bind = System.getenv().getOrDefault("BIGCITY_BIND", "127.0.0.1");
        String origin = System.getenv().getOrDefault("BIGCITY_ORIGIN", "http://127.0.0.1:" + port);
        Path workingDirectory = Path.of("").toAbsolutePath();
        Path backend = Files.isRegularFile(workingDirectory.resolve("pom.xml"))
                && Files.isDirectory(workingDirectory.resolve("data")) ? workingDirectory : workingDirectory.resolve("backend");
        Path project = backend.getParent();
        Path runtime = Path.of(System.getenv().getOrDefault("BIGCITY_RUNTIME", backend.resolve("runtime").toString()));
        Path frontend = project.resolve("frontend/dist");
        if (!Files.isRegularFile(frontend.resolve("index.html"))) frontend = project.resolve("dist");

        AccountService accounts = new AccountService(runtime);
        ResidentRequests requests = new ResidentRequests(runtime);
        PayOSPayments payments = PayOSPayments.fromEnvironment(requests, origin);
        BigCityHttpHandler handler = new BigCityHttpHandler(frontend, backend.resolve("data"), origin, accounts, requests, payments);
        HttpServer server = HttpServer.create(new InetSocketAddress(bind, port), 0);
        server.createContext("/", handler);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        if (payments.enabled()) {
            Executors.newSingleThreadScheduledExecutor().scheduleWithFixedDelay(payments::syncDeposits, 0, 10, TimeUnit.SECONDS);
        }
        System.out.println("BIG CITY: " + origin);
    }
}
