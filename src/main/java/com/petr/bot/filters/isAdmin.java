package com.petr.bot.filters;

import io.github.natanimn.telebof.filters.CustomFilter;
import io.github.natanimn.telebof.types.updates.Update;

public class isAdmin implements CustomFilter {

    @Override
    public boolean check(Update update) {
        long chatId;
        if (update.message != null && update.message.text != null) {
            chatId = update.message.chat.id;
        } else if (update.callback_query != null && update.callback_query.from != null) {
            chatId = update.callback_query.from.id;
        } else {
            return false;
        }

        String env = System.getenv("ADMIN_CHATS");
        System.out.println("[isAdmin] ADMIN_CHATS env = '" + env + "', chatId=" + chatId);
        if (env == null || env.isBlank()) {
            return false;
        }
        for (String part : env.split(",")) {
            try {
                if (Long.parseLong(part.trim()) == chatId) {
                    System.out.println("[isAdmin] Доступ разрешён для chatId=" + chatId);
                    return true;
                }
            } catch (NumberFormatException ignored) {}
        }
        System.out.println("[isAdmin] chatId=" + chatId + " не найден в ADMIN_CHATS");
        return false;
    }
}
