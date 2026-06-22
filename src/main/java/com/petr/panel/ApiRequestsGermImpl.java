package com.petr.panel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petr.exception.DuplicateEmailException;
import com.petr.exception.LoginException;
import com.petr.exception.RequestException;
import com.petr.exception.RetryAttemptsLeftException;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class ApiRequestsGermImpl implements ApiRequests {

    private static final int MAX_RETRIES = 2;
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient client = HttpClient.newBuilder()
            .cookieHandler(cookies)
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private final URI baseUri;

    private final String settingsTemplate = "{\"clients\": [{ " +
            "\"id\": \"%s\", " +
            "\"flow\": \"\", " +
            "\"email\": \"%s\", " +
            "\"limitIp\": 0, " +
            "\"totalGB\": 0, " +
            "\"expiryTime\": 0, " +
            "\"enable\": true, " +
            "\"tgId\": \"%s\", " +
            "\"subId\": \"%s\", " +
            "\"comment\": \"created from tg bot\", " +
            "\"reset\": 0 }]}";

    public ApiRequestsGermImpl() {
        String url = System.getenv("GERMAN_PANEL_HOME_URL");
        if (url == null || url.isBlank()) throw new RuntimeException("GERMAN_PANEL_HOME_URL не задан");
        if (!url.endsWith("/")) url = url + "/";
        this.baseUri = URI.create(url);
        System.out.println("[ApiGerm] baseUri=" + baseUri);
        try {
            login();
        } catch (Exception e) {
            System.out.println("[ApiGerm] Начальный логин не удался: " + e.getMessage());
        }
    }

    private <T> T executeWithRetry(ThrowingSupplier<T> request) throws IOException, InterruptedException {
        int attempts = 0;
        Exception lastException = null;

        while (attempts < MAX_RETRIES) {
            try {
                return request.get();
            } catch (RequestException ex) {
                lastException = ex;
                System.out.println("[ApiGerm] Запрос упал: " + ex.getMessage() + ", пробую перелогиниться...");
                try {
                    login();
                } catch (LoginException le) {
                    lastException = le;
                    System.out.println("[ApiGerm] Логин упал: " + le.getMessage());
                }
            } catch (LoginException ex) {
                lastException = ex;
                System.out.println("[ApiGerm] Логин упал: " + ex.getMessage());
            } catch (DuplicateEmailException ex) {
                throw ex;
            } catch (Exception ex) {
                lastException = ex;
                System.out.println("[ApiGerm] Необработанное исключение: " + ex.getClass().getSimpleName() + " - " + ex.getMessage());
            }
            attempts++;
        }

        String reason = lastException == null ? "неизвестная причина"
                : lastException.getClass().getSimpleName() + ": " + lastException.getMessage();
        throw new RetryAttemptsLeftException("Too many attempts. Last error: " + reason, MAX_RETRIES);
    }

    private void login() throws LoginException, IOException, InterruptedException {
        String username = System.getenv("XUI_USERNAME");
        String password = System.getenv("XUI_PASSWORD");

        URI loginUri = baseUri.resolve("login");
        System.out.println("[ApiGerm] login → " + loginUri + " (user='" + username + "')");

        String body = "username=" + URLEncoder.encode(username, StandardCharsets.UTF_8) +
                "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder(loginUri)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent", USER_AGENT)
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        System.out.println("[ApiGerm] login status=" + response.statusCode() + " body=" + response.body());

        if (response.statusCode() != 200) {
            throw new LoginException("HTTP " + response.statusCode(), response.statusCode());
        }

        try {
            JsonNode json = mapper.readTree(response.body());
            if (!json.path("success").asBoolean(false)) {
                String msg = json.path("msg").asText("неверные credentials");
                throw new LoginException("success=false: " + msg, response.statusCode());
            }
        } catch (LoginException e) {
            throw e;
        } catch (Exception e) {
            throw new LoginException("Не удалось разобрать ответ логина: " + e.getMessage(), response.statusCode());
        }

        System.out.println("[ApiGerm] login OK, cookies=" + cookies.getCookieStore().getCookies());
    }

    @Override
    public String addClientRequest(String inboundId, UUID uuid, UUID subUuid, String configName, long tgId)
            throws IOException, InterruptedException {
        return executeWithRetry(() -> {
            URI url = baseUri.resolve("panel/api/inbounds/addClient");
            String settings = String.format(settingsTemplate, uuid, configName, tgId, subUuid);
            String body = "id=" + URLEncoder.encode(inboundId, StandardCharsets.UTF_8) +
                    "&settings=" + URLEncoder.encode(settings, StandardCharsets.UTF_8);

            HttpRequest request = HttpRequest.newBuilder(url)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("User-Agent", USER_AGENT)
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            System.out.println("[ApiGerm] addClient inbound=" + inboundId +
                    " status=" + response.statusCode() + " body=" + response.body());

            if (response.statusCode() != 200) {
                throw new RequestException(
                        "addClient HTTP " + response.statusCode() + " body=" + response.body(),
                        response.statusCode());
            }

            JsonNode json = mapper.readTree(response.body());
            if (json.path("success").asBoolean(false)) {
                return "конфиг удачно добавлен на панель (inbound=" + inboundId + ")!";
            }
            String msg = json.path("msg").asText("неизвестная ошибка панели");
            if (msg.contains("Duplicate email")) {
                String email = msg.replaceAll(".*Duplicate email:\\s*", "").trim()
                        .replaceAll("\\s*\\n.*", "");
                throw new DuplicateEmailException(email);
            }
            throw new RequestException("addClient success=false inbound=" + inboundId + ": " + msg, 200);
        });
    }

    @Override
    public HttpResponse<String> getAllConfigsRequest() throws IOException, InterruptedException {
        return executeWithRetry(() -> {
            URI url = baseUri.resolve("panel/api/inbounds/list");
            System.out.println("[ApiGerm] getAllConfigs → " + url);

            HttpRequest request = HttpRequest.newBuilder(url)
                    .GET()
                    .header("User-Agent", USER_AGENT)
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            System.out.println("[ApiGerm] getAllConfigs status=" + response.statusCode());

            if (response.statusCode() != 200) {
                throw new RequestException("HTTP " + response.statusCode() + " body=" + response.body(), response.statusCode());
            }
            return response;
        });
    }

    @Override
    public String deleteClient(String inboundId, String configName) throws IOException, InterruptedException {
        return executeWithRetry(() -> {
            URI url = baseUri.resolve(String.format("panel/api/inbounds/%s/delClientByEmail/%s", inboundId, configName));

            HttpRequest request = HttpRequest.newBuilder(url)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("User-Agent", USER_AGENT)
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            System.out.println("[ApiGerm] deleteClient status=" + response.statusCode());

            if (response.statusCode() != 200) {
                throw new RequestException("HTTP " + response.statusCode(), response.statusCode());
            }
            return "Клиент " + configName + " удален!";
        });
    }
}
