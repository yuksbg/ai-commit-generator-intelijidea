package dev.aicommit;

import com.google.gson.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.*;

final class OpenAiClient {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    private final URI base;
    OpenAiClient() { this(URI.create("https://api.openai.com/v1/")); }
    OpenAiClient(URI base) { this.base = base; }

    String generate(String key, String model, String instructions, String diff, Runnable checkCancelled) throws IOException {
        var body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("instructions", instructions);
        body.addProperty("input", "Generate a commit message from this Git diff:\n\n" + diff);
        body.addProperty("store", false);
        return parseMessage(request("responses", key, body, checkCancelled));
    }

    List<String> models(String key, Runnable checkCancelled) throws IOException {
        JsonObject response = request("models", key, null, checkCancelled);
        try {
            List<String> models = new ArrayList<>();
            for (JsonElement item : response.getAsJsonArray("data")) models.add(item.getAsJsonObject().get("id").getAsString());
            return models.stream().distinct().sorted().toList();
        } catch (RuntimeException e) { throw new IOException("OpenAI returned an invalid model list."); }
    }

    private JsonObject request(String path, String key, JsonObject body, Runnable checkCancelled) throws IOException {
        var builder = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(120))
            .header("Authorization", "Bearer " + key).header("Content-Type", "application/json");
        if (body == null) builder.GET(); else builder.POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        var future = HTTP.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString());
        try {
            while (true) {
                checkCancelled.run();
                try {
                    var response = future.get(100, TimeUnit.MILLISECONDS);
                    if (response.statusCode() / 100 != 2) throw new IOException(errorForResponse(response.statusCode(), response.body()));
                    try { return JsonParser.parseString(response.body()).getAsJsonObject(); }
                    catch (RuntimeException e) { throw new IOException("OpenAI returned an invalid JSON response."); }
                } catch (TimeoutException ignored) { }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Request interrupted.");
        } catch (ExecutionException e) {
            throw new IOException("Could not reach OpenAI. Check your network connection and try again.");
        } finally { future.cancel(true); }
    }

    static String parseMessage(JsonObject response) throws IOException {
        try {
            if (!"completed".equals(response.get("status").getAsString()))
                throw new IOException("OpenAI did not complete the message. Try again or choose another model.");
            List<String> texts = new ArrayList<>();
            for (JsonElement item : response.getAsJsonArray("output")) {
                JsonObject output = item.getAsJsonObject();
                if (!"message".equals(output.get("type").getAsString())) continue;
                for (JsonElement content : output.getAsJsonArray("content")) {
                    JsonObject part = content.getAsJsonObject();
                    String type = part.get("type").getAsString();
                    if ("refusal".equals(type)) throw new IOException("The model declined to generate a commit message.");
                    if ("output_text".equals(type)) texts.add(part.get("text").getAsString());
                }
            }
            String message = String.join("\n", texts).strip();
            if (message.isEmpty()) throw new IOException("OpenAI returned no commit message. Choose a text-generation model.");
            return message;
        } catch (RuntimeException e) { throw new IOException("OpenAI returned an unexpected response format."); }
    }

    static String errorForResponse(int status, String body) {
        if (status != 429) return errorForStatus(status);
        String code = "";
        String type = "";
        try {
            JsonObject error = JsonParser.parseString(body).getAsJsonObject().getAsJsonObject("error");
            code = errorString(error, "code");
            type = errorString(error, "type");
        } catch (RuntimeException ignored) {
            // A gateway may return HTML or malformed JSON. Keep the HTTP fallback.
        }
        String detail = switch (code) {
            case "credit_balance_exhausted" -> "OpenAI API credits are exhausted. Add credits in API billing for the organization owning this key.";
            case "organization_spend_limit_exceeded" -> "The OpenAI organization spend limit was reached. Review its spend limit or wait for the monthly reset.";
            case "project_spend_limit_exceeded" -> "The OpenAI project spend limit was reached. Review the spend limit for the project owning this key or wait for the monthly reset.";
            case "organization_usage_limit_exceeded" -> "The OpenAI organization usage limit was reached. Request a higher approved API usage limit.";
            case "insufficient_quota" -> quotaMessage();
            case "rate_limit_exceeded", "slow_down" -> "OpenAI temporarily rate-limited this request. Wait before retrying, or reduce the selected diff size.";
            default -> "insufficient_quota".equals(type) ? quotaMessage()
                : ("rate_limit_error".equals(type)
                    ? "OpenAI temporarily rate-limited this request. Wait before retrying, or reduce the selected diff size."
                    : errorForStatus(status));
        };
        // Only display known codes; never echo arbitrary server text or request data.
        return detail;
    }

    private static String errorString(JsonObject object, String field) {
        JsonElement value = object.get(field);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
            ? value.getAsString() : "";
    }

    private static String quotaMessage() {
        return "OpenAI reported insufficient API quota. Check API credits and usage limits for the organization/project owning this key. "
            + "A ChatGPT subscription does not provide API credits. Retrying alone will not restore quota.";
    }

    static String errorForStatus(int status) {
        return switch (status) {
            case 401 -> "OpenAI rejected the API key. Update it in Settings | Tools | AI Commit Message generator by 0config.eu.";
            case 403 -> "This API key does not have access to the selected model.";
            case 429 -> "OpenAI rate or quota limit reached. Check API billing or try again later.";
            case 400, 404 -> "OpenAI rejected the request. Check that your model supports the Responses API.";
            default -> "OpenAI request failed (HTTP " + status + "). Try again later.";
        };
    }
}
