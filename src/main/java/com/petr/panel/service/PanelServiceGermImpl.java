package com.petr.panel.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petr.exception.DuplicateEmailException;
import com.petr.panel.ApiRequests;
import com.petr.panel.ApiRequestsGermImpl;
import com.petr.panel.dto.PanelClient;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class PanelServiceGermImpl implements PanelService {

    private static final String WS_INBOUND = "3";
    private static final String XHTTP_INBOUND = "2";

    private final ApiRequests api = new ApiRequestsGermImpl();
    private final ObjectMapper mapper = new ObjectMapper();

    private final String serverHost;
    private final String wsPort;
    private final String xhttpPort;
    private final String xhttpPbk;
    private final String xhttpFp;
    private final String xhttpSni;
    private final String xhttpSid;
    private final String xhttpPath;
    private final String xhttpHost;
    private final String xhttpSpx;
    private final String subBaseUrl;

    public PanelServiceGermImpl() {
        serverHost = nvl(System.getenv("GERM_SERVER_HOST"), "");
        wsPort = nvl(System.getenv("GERM_WS_PORT"), "1235");
        xhttpPort = nvl(System.getenv("GERM_XHTTP_PORT"), "54228");
        xhttpPbk = nvl(System.getenv("GERM_XHTTP_PBK"), "");
        xhttpFp = nvl(System.getenv("GERM_XHTTP_FP"), "chrome");
        xhttpSni = nvl(System.getenv("GERM_XHTTP_SNI"), "www.sap.com");
        xhttpSid = nvl(System.getenv("GERM_XHTTP_SID"), "");
        xhttpPath = nvl(System.getenv("GERM_XHTTP_PATH"), "/");
        xhttpHost = nvl(System.getenv("GERM_XHTTP_HOST"), "www.sap.com");
        xhttpSpx = nvl(System.getenv("GERM_XHTTP_SPX"), "/");
        subBaseUrl = nvl(System.getenv("GERM_SUB_BASE_URL"), "");

        System.out.println("[PanelGerm] serverHost=" + serverHost
                + ", wsPort=" + wsPort + ", xhttpPort=" + xhttpPort);
    }

    private static String nvl(String v, String def) {
        return (v != null && !v.isBlank()) ? v : def;
    }

    @Override
    public String listClients() throws IOException, InterruptedException {
        return api.getAllConfigsRequest().body();
    }

    @Override
    public String deleteClient(String clientName) throws IOException, InterruptedException {
        String result = api.deleteClient(WS_INBOUND, clientName);
        try {
            api.deleteClient(XHTTP_INBOUND, toXhttpEmail(clientName));
        } catch (Exception e) {
            System.out.println("[PanelGerm] xhttp delete skipped for " + clientName + ": " + e.getMessage());
        }
        return result;
    }

    /**
     * @return [wsVlessLink, subLink, xhttpVlessLink] — null для неиспользованных типов
     */
    @Override
    public String[] createClient(String clientName, long tgId, String configType, UUID reusedSubUuid)
            throws IOException, InterruptedException {

        UUID subUuid = (reusedSubUuid != null) ? reusedSubUuid : UUID.randomUUID();
        String subLink = createSubLink(subUuid);

        boolean doWs = "ws".equals(configType) || "both".equals(configType);
        boolean doXhttp = "xhttp".equals(configType) || "both".equals(configType);

        String wsLink = null;
        String xhttpLink = null;

        if (doWs) {
            UUID wsUuid = UUID.randomUUID();
            wsLink = createWsLink(clientName, wsUuid);
            try {
                api.addClientRequest(WS_INBOUND, wsUuid, subUuid, clientName, tgId);
            } catch (DuplicateEmailException e) {
                String existingUuid = findClientUuid(WS_INBOUND, clientName);
                if (existingUuid != null) {
                    wsLink = createWsLink(clientName, UUID.fromString(existingUuid));
                    System.out.println("[PanelGerm] WS клиент уже на панели, UUID=" + existingUuid);
                }
            }
        }

        if (doXhttp) {
            String xhttpEmail = toXhttpEmail(clientName);
            UUID xhttpUuid = UUID.randomUUID();
            xhttpLink = createXhttpLink(clientName, xhttpUuid);
            try {
                api.addClientRequest(XHTTP_INBOUND, xhttpUuid, subUuid, xhttpEmail, tgId);
            } catch (DuplicateEmailException e) {
                String existingUuid = findClientUuid(XHTTP_INBOUND, xhttpEmail);
                if (existingUuid != null) {
                    xhttpLink = createXhttpLink(clientName, UUID.fromString(existingUuid));
                    System.out.println("[PanelGerm] xhttp клиент уже на панели, UUID=" + existingUuid);
                }
            }
        }

        return new String[]{wsLink, subLink, xhttpLink};
    }

    @Override
    public List<PanelClient> getClients() throws IOException, InterruptedException {
        String body = api.getAllConfigsRequest().body();
        JsonNode root = mapper.readTree(body);

        if (!root.path("success").asBoolean(false)) {
            return Collections.emptyList();
        }

        JsonNode objArray = root.path("obj");
        if (!objArray.isArray()) {
            return Collections.emptyList();
        }

        JsonNode wsInboundNode = null;
        JsonNode xhttpInboundNode = null;

        int wsInboundId = Integer.parseInt(WS_INBOUND);
        int xhttpInboundId = Integer.parseInt(XHTTP_INBOUND);

        for (JsonNode inboundNode : objArray) {
            int id = inboundNode.path("id").asInt(-1);
            if (id == wsInboundId) {
                wsInboundNode = inboundNode;
            } else if (id == xhttpInboundId) {
                xhttpInboundNode = inboundNode;
            }
        }

        java.util.Map<String, MutableClient> merged = new java.util.LinkedHashMap<>();

        // Читаем WS inbound
        if (wsInboundNode != null) {
            String settingsStr = wsInboundNode.path("settings").asText("");
            if (!settingsStr.isEmpty()) {
                JsonNode settings = mapper.readTree(settingsStr);
                JsonNode clients = settings.path("clients");

                if (clients.isArray()) {
                    for (JsonNode c : clients) {
                        String tgIdStr = c.path("tgId").asText("").trim();
                        if (tgIdStr.isEmpty() || tgIdStr.equals("0")) {
                            continue;
                        }

                        long tgId;
                        try {
                            tgId = Long.parseLong(tgIdStr);
                        } catch (NumberFormatException e) {
                            continue;
                        }

                        String uuid = c.path("id").asText("");
                        String subUuidStr = c.path("subId").asText("");
                        String email = c.path("email").asText("");

                        if (uuid.isEmpty() || subUuidStr.isEmpty() || email.isEmpty()) {
                            continue;
                        }

                        UUID wsUuid;
                        UUID subUuid;
                        try {
                            wsUuid = UUID.fromString(uuid);
                            subUuid = UUID.fromString(subUuidStr);
                        } catch (IllegalArgumentException e) {
                            continue;
                        }

                        String key = tgId + "|" + normalizeBaseEmail(email);

                        MutableClient item = merged.computeIfAbsent(key, k -> new MutableClient());
                        item.tgId = tgId;
                        item.configName = normalizeBaseEmail(email);
                        item.vlessLink = createWsLink(item.configName, wsUuid);
                        item.subLink = createSubLink(subUuid);
                    }
                }
            }
        }

        // Читаем XHTTP inbound
        if (xhttpInboundNode != null) {
            String settingsStr = xhttpInboundNode.path("settings").asText("");
            if (!settingsStr.isEmpty()) {
                JsonNode settings = mapper.readTree(settingsStr);
                JsonNode clients = settings.path("clients");

                if (clients.isArray()) {
                    for (JsonNode c : clients) {
                        String tgIdStr = c.path("tgId").asText("").trim();
                        if (tgIdStr.isEmpty() || tgIdStr.equals("0")) {
                            continue;
                        }

                        long tgId;
                        try {
                            tgId = Long.parseLong(tgIdStr);
                        } catch (NumberFormatException e) {
                            continue;
                        }

                        String uuid = c.path("id").asText("");
                        String email = c.path("email").asText("");

                        if (uuid.isEmpty() || email.isEmpty()) {
                            continue;
                        }

                        UUID xhttpUuid;
                        try {
                            xhttpUuid = UUID.fromString(uuid);
                        } catch (IllegalArgumentException e) {
                            continue;
                        }

                        String baseEmail = normalizeBaseEmail(email);
                        String key = tgId + "|" + baseEmail;

                        MutableClient item = merged.computeIfAbsent(key, k -> new MutableClient());
                        item.tgId = tgId;
                        if (item.configName == null || item.configName.isBlank()) {
                            item.configName = baseEmail;
                        }
                        item.xhttpLink = createXhttpLink(baseEmail, xhttpUuid);
                    }
                }
            }
        }

        List<PanelClient> result = new ArrayList<>();
        for (MutableClient item : merged.values()) {
            if (item.configName == null || item.configName.isBlank()) {
                continue;
            }
            result.add(new PanelClient(
                    item.tgId,
                    item.configName,
                    item.vlessLink,
                    item.subLink,
                    item.xhttpLink
            ));
        }

        return result;
    }

    private static String toXhttpEmail(String wsEmail) {
        if (wsEmail.endsWith("_config")) {
            return wsEmail.substring(0, wsEmail.length() - "_config".length()) + "_xhttp";
        }
        return wsEmail + "_xhttp";
    }

    private static String normalizeBaseEmail(String email) {
        if (email == null || email.isBlank()) {
            return email;
        }
        if (email.endsWith("_xhttp")) {
            return email.substring(0, email.length() - "_xhttp".length()) + "_config";
        }
        return email;
    }

    private String findClientUuid(String inboundId, String email) {
        try {
            String body = api.getAllConfigsRequest().body();
            JsonNode root = mapper.readTree(body);
            if (!root.path("success").asBoolean(false)) {
                return null;
            }

            int targetId = Integer.parseInt(inboundId);
            for (JsonNode inboundNode : root.path("obj")) {
                if (inboundNode.path("id").asInt(-1) != targetId) {
                    continue;
                }

                JsonNode settings = mapper.readTree(inboundNode.path("settings").asText("{}"));
                for (JsonNode c : settings.path("clients")) {
                    if (email.equals(c.path("email").asText(""))) {
                        String uuid = c.path("id").asText(null);
                        return uuid != null && !uuid.isEmpty() ? uuid : null;
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("[PanelGerm] findClientUuid failed for " + email + ": " + e.getMessage());
        }
        return null;
    }

    private String createWsLink(String clientName, UUID uuid) {
        return "vless://" + uuid + "@" + serverHost + ":" + wsPort
                + "?type=ws&encryption=none&path=%2F&security=none#Germany-" + clientName;
    }

    private String createXhttpLink(String clientName, UUID uuid) {
        String encodedPath = URLEncoder.encode(xhttpPath, StandardCharsets.UTF_8);
        String encodedSpx = URLEncoder.encode(xhttpSpx, StandardCharsets.UTF_8);

        return "vless://" + uuid + "@" + serverHost + ":" + xhttpPort
                + "?type=xhttp&encryption=none"
                + "&path=" + encodedPath
                + "&host=" + xhttpHost
                + "&mode=auto"
                + "&security=reality"
                + "&pbk=" + xhttpPbk
                + "&fp=" + xhttpFp
                + "&sni=" + xhttpSni
                + "&sid=" + xhttpSid
                + "&spx=" + encodedSpx
                + "#Germany-xhttp-" + clientName;
    }

    private String createSubLink(UUID uuid) {
        if (subBaseUrl.endsWith("/")) {
            return subBaseUrl + uuid;
        }
        return subBaseUrl + "/" + uuid;
    }

    private static class MutableClient {
        long tgId;
        String configName;
        String vlessLink;
        String subLink;
        String xhttpLink;
    }
}
