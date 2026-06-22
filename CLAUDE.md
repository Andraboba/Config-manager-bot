# CLAUDE.md

Guidance for Claude Code when working in this repository.

## Build & Run

```powershell
# IntelliJ bundled Maven (not in PATH — always use full path)
$mvn = "C:\Users\andre\AppData\Local\Programs\IntelliJ IDEA\plugins\maven\lib\maven3\bin\mvn.cmd"
& $mvn clean package -DskipTests

# Run locally (dev mode) — env vars must be set first
java -Dapp.env=dev -jar target/Config_bot-2.0-SNAPSHOT.jar

# Run in production
java -Dapp.env=prod -jar target/Config_bot-2.0-SNAPSHOT.jar
```

`app.env` (`prod` | `dev`) controls DB connection string and 3x-ui inbound IDs.

## Docker

```bash
docker compose up --build -d   # build + start
docker compose down --remove-orphans
docker compose logs -f bot     # follow logs
```

Copy `.env.example` → `.env` and fill all values before running.

## Environment Variables

| Variable | Description |
|---|---|
| `BOT_TOKEN` | Telegram bot token |
| `LATV_PANEL_HOME_URL` | Base URL for the Latvian 3x-ui panel (must end with `/`) |
| `GERMAN_PANEL_HOME_URL` | Base URL for the German panel (unused currently) |
| `XUI_USERNAME_LATV` / `XUI_PASSWORD_LATV` | Latvian panel credentials |
| `XUI_USERNAME` / `XUI_PASSWORD` | German panel credentials (unused) |
| `DB_LINK_PROD` / `DB_LINK_DEV` | JDBC URLs for prod/dev PostgreSQL |
| `DB_USER` / `DB_PASSWORD` / `DB_NAME` | PostgreSQL credentials |
| `ADMIN_CHATS` | Comma-separated Telegram chat IDs of admins (1 or more) |
| `LATV_XHTTP_INBOUND_PROD` | 3x-ui inbound ID for XHTTP in prod (optional) |
| `LATV_XHTTP_INBOUND_DEV` | 3x-ui inbound ID for XHTTP in dev (optional) |
| `GERM_SERVER_HOST` | Hostname/IP used in Germany VLESS links |
| `GERM_WS_PORT` | Germany WS inbound port (default `1235`) |
| `GERM_XHTTP_PORT` | Germany XHTTP inbound port (default `54228`) |
| `GERM_SUB_BASE_URL` | Germany subscription base URL (e.g. `https://host:2096/sub/`) |
| `GERM_XHTTP_PBK` | Germany REALITY public key |
| `GERM_XHTTP_SNI` | Germany XHTTP SNI (default `www.sap.com`) |
| `GERM_XHTTP_SID` | Germany XHTTP shortId (e.g. `11ba67e3b7`) |
| `GERM_XHTTP_FP` | Germany XHTTP fingerprint (default `chrome`) |
| `GERM_XHTTP_PATH` | Germany XHTTP path (default `/`) |
| `GERM_XHTTP_HOST` | Germany XHTTP host header (default `www.sap.com`) |
| `GERM_XHTTP_SPX` | Germany XHTTP spiderX (default `/`) |

## Architecture

```
Bot.java  (telebof: @MessageHandler + @CallbackHandler)
  └── ConfigManager / ConfigManagerImpl
        ├── PanelService → ApiRequests → 3x-ui HTTP API (HTTP/1.1, cookie auth)
        └── DbService → UserDao / ConfigDao → PostgreSQL (Hibernate 7)
```

### Key classes

**`Bot` / `BotHandler`** — All Telegram routing. Uses inline keyboards for all interactions (no plain-text command flows). State machine via `bot.setState` / `bot.clearState`. Two in-memory maps track message IDs for editing:
- `awaitingMessageId: Map<Long, Integer>` — message to edit after user types config name
- `deleteMessageId: Map<Long, Integer>` — admin panel message to edit after typing config name to delete

**`isAdmin` filter** — `CustomFilter` that checks `ADMIN_CHATS` env var for both `Message` updates and `CallbackQuery` updates (handles both types). Logs every check.

**`ConfigManagerImpl`** — Holds both `PanelServiceLatvImpl` and `PanelServiceGermImpl`. Dispatches to the correct panel based on `country` param ("latv" / "germ"). Country stored in DB `config.country` column.

**`PanelServiceLatvImpl`** — Latvian panel implementation.
- WS inbound: prod=`"2"`, dev=`"3"` (hardcoded)
- XHTTP inbound: from `LATV_XHTTP_INBOUND_PROD` / `LATV_XHTTP_INBOUND_DEV` env vars (optional; if absent, XHTTP is skipped)
- `createClient()` returns `String[]{wsLink, subLink, xhttpLink}` (wsLink/xhttpLink may be null)

**`PanelServiceGermImpl`** — German panel implementation.
- WS inbound: hardcoded `"3"`, XHTTP inbound: hardcoded `"2"`
- All connection params via `GERM_*` env vars (see table above)
- Both inbounds always attempted for delete (xhttp failure is logged and ignored)

**`ApiRequestsLatvImpl`** — HTTP client (forced HTTP/1.1, CookieManager). Calls `login()` eagerly at construction. `executeWithRetry` retries up to 2 times on `RequestException`, re-logging in between. Login validates JSON `success` field, not just HTTP status.

**`DbService`** — Facade over `UserDao` + `ConfigDao`. User status: `"w"` = waiting/no config, `"a"` = approved.

**`HibernateSessionFactoryUtil`** — Singleton `SessionFactory`, reads `app.env` to pick `hibernate-prod.properties` or `hibernate-dev.properties`. JDBC credentials injected from env vars.

**`DatabaseMigration`** — Runs Liquibase at startup before polling begins.

### Database schema

**`tg_user`**
| Column | Type | Notes |
|---|---|---|
| `id` | BIGINT PK | Telegram chat ID |
| `tg_name` | TEXT | @username (nullable) |
| `has_config` | BOOLEAN | true once config created on panel |
| `wait_accept` | TEXT | `"w"` = pending, `"a"` = approved |

**`config`**
| Column | Type | Notes |
|---|---|---|
| `tg_id` | BIGINT PK FK | References `tg_user.id` |
| `config_name` | TEXT | Panel email / config name (e.g. `petr_config`) |
| `vless_link` | TEXT | WS VLESS link |
| `sub_link` | TEXT | Subscription URL |
| `xhttp_link` | TEXT | XHTTP VLESS link (nullable — old configs don't have it) |
| `country` | VARCHAR(10) | `"latv"` or `"germ"` — determines which panel to use for delete |

Liquibase changesets: `src/main/resources/db/changeset/` (SQL format, master: `db.changelog-master.yaml`).

### Config name convention

User configs are named `{base}_config` (e.g. `petr_config`). The `_config` suffix is always added by the bot; users/admins provide only the base name.

## Bot UX (inline keyboard flows)

### User flow
1. `/start` → welcome message + **[Получить конфиг]** button
2. **[Получить конфиг]** callback → edits message to country selection keyboard:
   ```
   [ 🇱🇻 Латвия ] [ 🇩🇪 Германия ]
   [     Отмена     ]
   ```
3. **[🇱🇻 Латвия]** → directly shows config type keyboard
   **[🇩🇪 Германия]** → shows torrent warning with **[✅ Принимаю условия]** / **[❌ Отмена]**
4. **[Принимаю условия]** → shows config type keyboard (WS / xHTTP / Оба)
5. Config type chosen:
   - Has valid @username → attempt config creation (edits the message)
   - No valid @username → edits message to prompt text input + **[Отмена]**; state = `awaiting_config_name`
6. User types config base name → bot deletes user's message, edits stored message with result
7. Config pending approval → shows **[Проверить статус]** button
8. **[Проверить статус]** → edits message: shows links if approved, or "ещё ждёт" + button again

### Admin flow
1. `/admin` → sends admin panel message + keyboard:
   ```
   [ Синхронизировать с панелью ]
   [   Список пользователей     ]
   [      Удалить конфиг        ]
   [    Выйти из режима         ]
   ```
2. Every button **edits the same message** — result appears above the buttons
3. **[Удалить конфиг]** → edits message to prompt for config name + **[Отмена]**; state = `admin_delete_state`; stores `message_id` in `deleteMessageId`. Admin types name → bot deletes from panel + DB, resets user status, edits message with result
4. Config request notification to admins includes inline **[Одобрить] [Отклонить]** buttons — pressing edits the notification message

### Admin commands (text fallback)
`/syncPanel`, `/users`, `/exAdmin` — still work as text commands for convenience.

### State machine
| State | Who | Meaning |
|---|---|---|
| `awaiting_config_name` | user | waiting for user to type config base name |
| `admin_state` | admin | admin panel mode active |
| `admin_delete_state` | admin | waiting for admin to type config name to delete |

## Config approval flow

1. User clicks **[Получить конфиг]** → bot registers client on WS (and optionally XHTTP) inbound, saves to DB (`hasConfig=true`, status=`"w"`), returns `String[]{}` (empty = pending)
2. Bot edits user message: "ожидает одобрения" + **[Проверить статус]**; sends notification to each admin in `ADMIN_CHATS` with **[Одобрить] [Отклонить]** buttons
3. Admin clicks **[Одобрить]** → `setUserStatusAccepted` sets status=`"a"`, bot sends config links to user, edits admin's notification message
4. User clicks **[Проверить статус]** → `getConfigs(userId)` checks `userHasAcceptedConfig`; if true, shows links

## Config delete flow

1. Admin clicks **[Удалить конфиг]** → prompts for base name (e.g. `petr`)
2. Bot constructs full name (`petr_config`), calls `ConfigManagerImpl.deleteConfig(configName)`
3. `deleteConfig`: finds config in DB by name, deletes DB record, resets user `hasConfig=false`/`waitAccept="w"`, calls panel `deleteClient`
4. Note: `deleteClient` on panel deletes from WS inbound only; XHTTP inbound entry must be deleted manually if needed

## Panel sync (`/syncPanel`)

Reads all clients from WS inbound via `GET /panel/api/inbounds/list`, iterates clients with non-zero `tgId`, upserts users and configs in DB. Sets status=`"a"` and `hasConfig=true` for synced users. Does **not** reconstruct XHTTP links for synced users (xhttp_link stays null).

## 3x-ui API notes

- `POST /login` — form-encoded `username=&password=`. Response: `{"success": true/false, "msg": "..."}`. **Must check JSON body**, not just HTTP status (server returns 200 even on bad credentials).
- All API calls use session cookie obtained from login. `HttpClient` forced to HTTP/1.1 (HTTP/2 causes issues with some 3x-ui setups).
- `POST /panel/api/inbounds/addClient` — form fields: `id` (inbound ID) + `settings` (JSON string with client array)
- `GET /panel/api/inbounds/list` — returns all inbounds with their client lists
- `POST /panel/api/inbounds/{inboundId}/delClientByEmail/{email}` — delete client

## CI/CD

GitHub Actions (`.github/workflows/push_workflow.yml`) on push to `master` via self-hosted runner: rebuilds Docker image, waits for health check, verifies DB connectivity (`COUNT(*) FROM tg_user`).
