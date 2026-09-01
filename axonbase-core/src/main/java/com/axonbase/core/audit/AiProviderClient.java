package com.axonbase.core.audit;

import com.axonbase.common.Messages;
import com.axonbase.core.catalog.Database;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Client para chamar API de IA (Claude, OpenAI, Gemini, Ollama) e gerar
 * regras de auditoria a partir de prompts em linguagem natural.
 * Chamado UMA ÚNICA VEZ por CREATE AI AUDIT.
 */
public final class AiProviderClient {

    private final String provider;
    private final String model;
    private final String apiKey;
    private final String baseUrl;
    private final HttpClient http;

    public AiProviderClient(String provider, String model, String apiKey) {
        this(provider, model, apiKey, null);
    }

    public AiProviderClient(String provider, String model, String apiKey, String baseUrl) {
        this.provider = provider;
        this.model = model;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? null : baseUrl;
        this.http = HttpClient.newHttpClient();
    }

    public boolean isConfigured() {
        return provider != null && !provider.isBlank()
            && model != null && !model.isBlank()
            && apiKey != null && !apiKey.isBlank();
    }

    /**
     * Gera regras de auditoria (JSON) a partir dos prompts.
     * Retorna o JSON bruto para ser parseado pelo executor.
     */
    public String generateRules(String warningPrompt, String dangerPrompt, Database db) {
        String schema = buildSchema(db);
        String systemPrompt = "You are a SQL security rule generator for AxonBase database. "
            + "Generate a JSON ruleset for the following audit requirements. "
            + "Return ONLY a JSON object with two keys: \"warning\" and \"danger\". "
            + "Each key contains an array of rule objects. "
            + "Rule types available: "
            + "\"regex\" (pattern as string), "
            + "\"command\" (commands: list of command prefixes + require_where: boolean), "
            + "\"keyword\" (words: list of words). "
            + "Example: {\"warning\":[{\"type\":\"regex\",\"pattern\":\"DELETE\\\\s+FROM\\\\s+\\\\w+\\\\s*(?!\\\\s*WHERE)\"}],"
            + "\"danger\":[{\"type\":\"command\",\"commands\":[\"DROP TABLE\",\"TRUNCATE\"],\"require_where\":false}]}";

        String userPrompt = "WARNING prompt: \"" + warningPrompt + "\"\n"
            + "DANGER prompt: \"" + dangerPrompt + "\"\n"
            + "Database schema: " + schema;

        return switch (provider) {
            case "claude" -> callClaude(systemPrompt, userPrompt);
            case "openai" -> callOpenAI(systemPrompt, userPrompt);
            case "gemini" -> callGemini(systemPrompt, userPrompt);
            case "ollama" -> callOllama(systemPrompt, userPrompt);
            default -> throw new IllegalArgumentException(Messages.get("audit_provider_unsupported", provider));
        };
    }

    private String endpoint(String defaultUrl) {
        String endpoint = baseUrl != null ? baseUrl : defaultUrl;
        return endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
    }

    private String callClaude(String system, String user) {
        try {
            String body = "{\"model\":\"" + model + "\",\"system\":\"" + escape(system)
                + "\",\"messages\":[{\"role\":\"user\",\"content\":\"" + escape(user) + "\"}],"
                + "\"max_tokens\":2000,\"temperature\":0}";
            var req = HttpRequest.newBuilder()
                .uri(URI.create(endpoint("https://api.anthropic.com") + "/v1/messages"))
                .header("Content-Type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            AxonValue response = parseResponse(http.send(req, HttpResponse.BodyHandlers.ofString()));
            return field(arrayItem(field(response, "content")), "text").asString();
        } catch (Exception e) {
            throw new RuntimeException(Messages.get("audit_provider_error", "Claude", e.getMessage()), e);
        }
    }

    private String callOpenAI(String system, String user) {
        try {
            String body = "{\"model\":\"" + model + "\",\"messages\":["
                + "{\"role\":\"system\",\"content\":\"" + escape(system) + "\"},"
                + "{\"role\":\"user\",\"content\":\"" + escape(user) + "\"}],"
                + "\"max_tokens\":2000,\"temperature\":0}";
            String base = endpoint("https://api.openai.com");
            String url = base.endsWith("/chat/completions") ? base : base + "/v1/chat/completions";
            var req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            AxonValue response = parseResponse(http.send(req, HttpResponse.BodyHandlers.ofString()));
            return field(field(arrayItem(field(response, "choices")), "message"), "content").asString();
        } catch (Exception e) {
            throw new RuntimeException(Messages.get("audit_provider_error", "OpenAI", e.getMessage()), e);
        }
    }

    private String callGemini(String system, String user) {
        try {
            String body = "{\"contents\":[{\"parts\":[{\"text\":\"" + escape(system + "\n\n" + user) + "\"}]}],"
                + "\"generationConfig\":{\"maxOutputTokens\":2000,\"temperature\":0}}";
            String base = endpoint("https://generativelanguage.googleapis.com");
            var req = HttpRequest.newBuilder()
                .uri(URI.create(base + "/v1beta/models/" + model + ":generateContent?key=" + apiKey))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            AxonValue response = parseResponse(http.send(req, HttpResponse.BodyHandlers.ofString()));
            AxonValue candidate = arrayItem(field(response, "candidates"));
            AxonValue content = field(candidate, "content");
            return field(arrayItem(field(content, "parts")), "text").asString();
        } catch (Exception e) {
            throw new RuntimeException(Messages.get("audit_provider_error", "Gemini", e.getMessage()), e);
        }
    }

    private String callOllama(String system, String user) {
        try {
            String body = "{\"model\":\"" + model + "\",\"system\":\"" + escape(system)
                + "\",\"prompt\":\"" + escape(user) + "\",\"stream\":false,\"options\":{\"temperature\":0}}";
            String baseUrl = endpoint("http://localhost:11434");
            var req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/generate"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            AxonValue response = parseResponse(http.send(req, HttpResponse.BodyHandlers.ofString()));
            return field(response, "response").asString();
        } catch (Exception e) {
            throw new RuntimeException(Messages.get("audit_provider_error", "Ollama", e.getMessage()), e);
        }
    }

    private String buildSchema(Database db) {
        var catalog = db.catalog();
        StringBuilder sb = new StringBuilder();
        for (var table : catalog.tables()) {
            sb.append("Table ").append(table.name()).append(": ");
            sb.append("columns [");
            boolean first = true;
            for (var field : table.fields().values()) {
                if (!first) sb.append(", ");
                sb.append(field.name()).append("(").append(field.type()).append(")");
                first = false;
            }
            sb.append("]; ");
        }
        if (sb.isEmpty()) sb.append("(empty)");
        return sb.toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private static String unescape(String s) {
        return s.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\").replace("\\r", "\r").replace("\\t", "\t");
    }

    private AxonValue parseResponse(HttpResponse<String> response) {
        String body = response.body();
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException(Messages.get("audit_provider_response") + ": HTTP "
                + response.statusCode() + " " + body.substring(0, Math.min(body.length(), 200)));
        }
        try {
            return AxonJson.parseDocument(body);
        } catch (RuntimeException e) {
            throw new RuntimeException(Messages.get("audit_provider_response"), e);
        }
    }

    private AxonValue field(AxonValue object, String name) {
        if (!object.isObject()) {
            throw new RuntimeException(Messages.get("audit_provider_response"));
        }
        AxonValue value = object.asObject().get(name);
        if (value == null) {
            throw new RuntimeException(Messages.get("audit_provider_response"));
        }
        return value;
    }

    private AxonValue arrayItem(AxonValue array) {
        if (!array.isArray() || array.asArray().isEmpty()) {
            throw new RuntimeException(Messages.get("audit_provider_response"));
        }
        return array.asArray().getFirst();
    }
}
