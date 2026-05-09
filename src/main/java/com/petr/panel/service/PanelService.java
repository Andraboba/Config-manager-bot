package com.petr.panel.service;

import com.petr.panel.dto.PanelClient;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

public interface PanelService {
    String listClients() throws IOException, InterruptedException;
    String deleteClient(String clientId) throws IOException, InterruptedException;

    /**
     * Создаёт клиента(ов) на панели.
     *
     * @param configType "ws", "xhttp" или "both"
     * @param reusedSubUuid UUID подписки для повторного использования (null — генерировать новый)
     * @return [wsVlessLink, subLink, xhttpVlessLink] — null для неиспользованных типов
     */
    String[] createClient(String clientName, long tgId, String configType, UUID reusedSubUuid)
            throws IOException, InterruptedException;

    List<PanelClient> getClients() throws IOException, InterruptedException;
}
