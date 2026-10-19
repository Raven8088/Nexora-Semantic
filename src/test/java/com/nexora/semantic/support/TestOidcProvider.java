package com.nexora.semantic.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Minimal local OpenID Connect provider for tests: serves the discovery document and the JWKS of
 * {@link TestJwt}. The application is configured with {@code issuer-uri} pointing here, exactly like production,
 * so tests exercise Spring Boot's issuer discovery, JWKS retrieval, issuer validation and the mandatory issuer
 * validator. Started once per JVM on a random loopback port.
 */
public final class TestOidcProvider {

    private static final String REALM_PATH = "/realms/test";
    private static String issuer;

    private TestOidcProvider() {
    }

    public static synchronized String issuer() {
        if (issuer == null) {
            start();
        }
        return issuer;
    }

    private static void start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + REALM_PATH;
            String discovery = """
                    {"issuer":"%1$s","jwks_uri":"%1$s/protocol/openid-connect/certs",
                     "authorization_endpoint":"%1$s/protocol/openid-connect/auth",
                     "token_endpoint":"%1$s/protocol/openid-connect/token",
                     "response_types_supported":["code"],"subject_types_supported":["public"],
                     "id_token_signing_alg_values_supported":["RS256"]}""".formatted(base);
            server.createContext(REALM_PATH + "/.well-known/openid-configuration", exchange -> respond(exchange, discovery));
            server.createContext(REALM_PATH + "/protocol/openid-connect/certs", exchange -> respond(exchange, TestJwt.jwks()));
            server.setExecutor(null);
            server.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
            issuer = base;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void respond(HttpExchange exchange, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
