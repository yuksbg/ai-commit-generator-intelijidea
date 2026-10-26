package dev.aicommit;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;

public final class CoreTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        equal("Branch: fix/{DATE_ISO_8601} Date: 2026-10-26",
            PromptTemplate.render("Branch: {BRANCH_NAME} Date: {DATE_ISO_8601}", "fix/{DATE_ISO_8601}", LocalDate.of(2026, 10, 26)));
        equal("custom $1 \\ prompt", PromptTemplate.render("custom $1 \\ prompt", "main", LocalDate.now()));
        equal("Fix login\n\nHandle expired tokens", OpenAiClient.parseMessage(json("""
            {"status":"completed","output":[{"type":"reasoning"},{"type":"message","content":[
            {"type":"output_text","text":"Fix login\n\nHandle expired tokens"}]}]}
            """.replace("Fix login\n\nHandle", "Fix login\\n\\nHandle"))));
        fails(() -> OpenAiClient.parseMessage(json("{\"status\":\"incomplete\",\"output\":[]}")), "did not complete");
        fails(() -> OpenAiClient.parseMessage(json("{\"status\":\"completed\",\"output\":[]}")), "no commit message");
        fails(() -> OpenAiClient.parseMessage(json("{}")), "unexpected response");
        fails(() -> OpenAiClient.parseMessage(json("""
            {"status":"completed","output":[{"type":"message","content":[{"type":"refusal","refusal":"no"}]}]}
            """)), "declined");
        String[][] errors = {
            {"insufficient_quota", "insufficient API quota"},
            {"credit_balance_exhausted", "credits are exhausted"},
            {"organization_spend_limit_exceeded", "organization spend limit"},
            {"project_spend_limit_exceeded", "project spend limit"},
            {"organization_usage_limit_exceeded", "organization usage limit"},
            {"rate_limit_exceeded", "temporarily rate-limited"},
            {"slow_down", "temporarily rate-limited"}
        };
        for (String[] error : errors) {
            var payload = new JsonObject();
            var detail = new JsonObject();
            detail.addProperty("code", error[0]);
            detail.addProperty("type", "insufficient_quota");
            payload.add("error", detail);
            equal(true, OpenAiClient.errorForResponse(429, payload.toString()).contains(error[1]));
        }
        equal(true, OpenAiClient.errorForResponse(429,
            "{\"error\":{\"code\":null,\"type\":\"insufficient_quota\"}}").contains("insufficient API quota"));
        for (String malformed : new String[]{"<html>gateway error</html>", "{}", "null", "{\"error\":null}"})
            equal(OpenAiClient.errorForStatus(429), OpenAiClient.errorForResponse(429, malformed));
        equal(OpenAiClient.errorForStatus(429), OpenAiClient.errorForResponse(429,
            "{\"error\":{\"code\":\"unknown\",\"message\":\"secret-data\"}}"));
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/responses", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"Fix bug\"}]}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.createContext("/v1/models", exchange -> {
            byte[] body = "{\"data\":[{\"id\":\"z-model\"},{\"id\":\"a-model\"},{\"id\":\"z-model\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.createContext("/denied/responses", exchange -> {
            exchange.sendResponseHeaders(401, -1); exchange.close();
        });
        server.createContext("/quota/responses", exchange -> {
            byte[] body = "{\"error\":{\"code\":\"insufficient_quota\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(429, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var client = new OpenAiClient(URI.create(base + "/v1/"));
            equal("Fix bug", client.generate("fake-test-key", "chosen-model", "Use imperative mood", "-old\n+new", () -> {}));
            equal("Bearer fake-test-key", authorization.get());
            JsonObject body = json(requestBody.get());
            equal("chosen-model", body.get("model").getAsString());
            equal("Use imperative mood", body.get("instructions").getAsString());
            equal(false, body.get("store").getAsBoolean());
            equal(true, body.get("input").getAsString().contains("-old\n+new"));
            equal(java.util.List.of("a-model", "z-model"), client.models("fake-test-key", () -> {}));
            fails(() -> new OpenAiClient(URI.create(base + "/denied/")).generate("fake", "m", "p", "d", () -> {}), "rejected the API key");
            fails(() -> new OpenAiClient(URI.create(base + "/quota/")).generate("fake", "m", "p", "d", () -> {}), "insufficient API quota");
            try {
                client.generate("fake", "m", "p", "d", () -> { throw new java.util.concurrent.CancellationException(); });
                throw new AssertionError("Cancellation was swallowed");
            } catch (java.util.concurrent.CancellationException expected) { checks++; }
        } finally { server.stop(0); }
        equal(true, OpenAiClient.errorForStatus(429).contains("quota"));
        System.out.println("Passed " + checks + " core checks.");
    }
    private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
    private static void equal(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
        checks++;
    }
    private static void fails(Throwing action, String fragment) throws Exception {
        try { action.run(); throw new AssertionError("Expected IOException"); }
        catch (IOException e) { if (!e.getMessage().contains(fragment)) throw new AssertionError(e); checks++; }
    }
    private interface Throwing { void run() throws Exception; }
}
