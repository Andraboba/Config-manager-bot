package com.petr.panel.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.petr.exception.DuplicateEmailException;
import com.petr.panel.ApiRequests;
import com.petr.panel.ApiRequestsLatvImpl;
import com.petr.panel.dto.PanelClient;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class PanelServiceLatvImpl implements PanelService {
    private final ApiRequests api = new ApiRequestsLatvImpl();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String inbound;
    private final String xhttpInbound;

    private final String xhttpPort;
    private final String xhttpPath;
    private final String xhttpPbk;
    private final String xhttpFp;
    private final String xhttpSni;
    private final String xhttpSid;
    private final String xhttpSpx;

    public PanelServiceLatvImpl() {
        String env = System.getProperty("app.env");
        if ("dev".equals(env)) {
            inbound = "3";
            xhttpInbound = System.getenv("LATV_XHTTP_INBOUND_DEV");
        } else {
            inbound = "2";
            xhttpInbound = System.getenv("LATV_XHTTP_INBOUND_PROD");
        }

        xhttpPort = nvl(System.getenv("LATV_XHTTP_PORT"), "443");
        xhttpPath = nvl(System.getenv("LATV_XHTTP_PATH"), "/api/v1/sync");
        xhttpPbk = nvl(System.getenv("LATV_XHTTP_PBK"), "");
        xhttpFp = nvl(System.getenv("LATV_XHTTP_FP"), "chrome");
        xhttpSni = nvl(System.getenv("LATV_XHTTP_SNI"), "");
        xhttpSid = nvl(System.getenv("LATV_XHTTP_SID"), "");
        xhttpSpx = nvl(System.getenv("LATV_XHTTP_SPX"), "/");

        System.out.println("[PanelLatv] WS inbound=" + inbound + ", XHTTP inbound=" + xhttpInbound);
    }

    private static String nvl(String v, String def) {
        return (v != null && !v.isBlank()) ? v : def;
    }

    @Override
    public String listClients() throws IOException, InterruptedException {
        return api.getAllConfigsRequest().body();
    }

    @Override
    public String deleteClient(String clientId) throws IOException, InterruptedException {
        String result = api.deleteClient(inbound, clientId);
        if (xhttpInbound != null && !xhttpInbound.isBlank()) {
            try {
                api.deleteClient(xhttpInbound, toXhttpEmail(clientId));
            } catch (Exception e) {
                System.out.println("[PanelLatv] xhttp delete skipped for " + clientId + ": " + e.getMessage());
            }
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
        boolean doXhttp = ("xhttp".equals(configType) || "both".equals(configType))
                && xhttpInbound != null && !xhttpInbound.isBlank();

        String wsLink = null;
        String xhttpLink = null;

        if (doWs) {
            UUID wsUuid = UUID.randomUUID();
            wsLink = createVlessLink(clientName, wsUuid);
            try {
                api.addClientRequest(inbound, wsUuid, subUuid, clientName, tgId);
            } catch (DuplicateEmailException e) {
                String existingUuid = findClientUuid(inbound, clientName);
                if (existingUuid != null) {
                    wsLink = createVlessLink(clientName, UUID.fromString(existingUuid));
                    System.out.println("[PanelLatv] WS клиент уже на панели, UUID=" + existingUuid);
                }
            }
        }

        if (doXhttp) {
            String xhttpEmail = toXhttpEmail(clientName);
            UUID xhttpUuid = UUID.randomUUID();
            xhttpLink = createXhttpLink(clientName, xhttpUuid);
            try {
                api.addClientRequest(xhttpInbound, xhttpUuid, subUuid, xhttpEmail, tgId);
            } catch (DuplicateEmailException e) {
                String existingUuid = findClientUuid(xhttpInbound, xhttpEmail);
                if (existingUuid != null) {
                    xhttpLink = createXhttpLink(clientName, UUID.fromString(existingUuid));
                    System.out.println("[PanelLatv] xhttp клиент уже на панели, UUID=" + existingUuid);
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

        int targetInbound = Integer.parseInt(inbound);
        for (JsonNode inboundNode : objArray) {
            if (inboundNode.path("id").asInt(-1) != targetInbound) {
                continue;
            }

            String settingsStr = inboundNode.path("settings").asText("");
            if (settingsStr.isEmpty()) {
                return Collections.emptyList();
            }

            JsonNode settings = mapper.readTree(settingsStr);
            JsonNode clients = settings.path("clients");
            if (!clients.isArray()) {
                return Collections.emptyList();
            }

            List<PanelClient> result = new ArrayList<>();
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

                String vlessLink = createVlessLink(email, UUID.fromString(uuid));
                String subLink = createSubLink(UUID.fromString(subUuidStr));

                result.add(new PanelClient(tgId, email, vlessLink, subLink, null));
            }

            return result;
        }

        return Collections.emptyList();
    }

    private static String toXhttpEmail(String wsEmail) {
        if (wsEmail.endsWith("_config")) {
            return wsEmail.substring(0, wsEmail.length() - "_config".length()) + "_xhttp";
        }
        return wsEmail + "_xhttp";
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
            System.out.println("[PanelLatv] findClientUuid failed for " + email + ": " + e.getMessage());
        }
        return null;
    }

    private String createVlessLink(String clientName, UUID uuid) {
        return "vless://" + uuid + "@petromerzlikino.site:1235?type=ws&encryption=none"
                + "&path=%2F&host=&security=none#riga-" + clientName;
    }

    private String createXhttpLink(String clientName, UUID uuid) {
        String encodedPath = URLEncoder.encode(xhttpPath, StandardCharsets.UTF_8);
        String encodedSpx = URLEncoder.encode(xhttpSpx, StandardCharsets.UTF_8);

        return "vless://" + uuid + "@petromerzlikino.site:" + xhttpPort
                + "?type=xhttp&encryption=none"
                + "&path=" + encodedPath
                + "&host=&mode=stream-one&security=reality"
                + "&pbk=" + xhttpPbk
                + "&fp=" + xhttpFp
                + "&sni=" + xhttpSni
                + "&sid=" + xhttpSid
                + "&spx=" + encodedSpx
                + "#riga-xhttp-" + clientName;
    }

    private String createSubLink(UUID uuid) {
        return "https://petromerzlikino.site:2096/sub/" + uuid;
    }
}
