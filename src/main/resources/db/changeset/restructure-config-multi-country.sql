--liquibase formatted sql

--changeset petrm:restructure-config-multi-country
-- Добавляем суррогатный PK id BIGSERIAL вместо tg_id
ALTER TABLE config ADD COLUMN id BIGSERIAL;
ALTER TABLE config DROP CONSTRAINT IF EXISTS config_pkey;
ALTER TABLE config ADD PRIMARY KEY (id);
ALTER TABLE config ADD CONSTRAINT config_tg_id_country_unique UNIQUE (tg_id, country);

-- Per-config статус одобрения; мигрируем из user-level wait_accept
ALTER TABLE config ADD COLUMN IF NOT EXISTS status VARCHAR(5) NOT NULL DEFAULT 'w';
UPDATE config c SET status = 'a' FROM tg_user u WHERE u.id = c.tg_id AND u.wait_accept = 'a';

--rollback -- manual: DROP CONSTRAINT config_tg_id_country_unique, DROP CONSTRAINT config_pkey, DROP COLUMN id, DROP COLUMN status, ADD PRIMARY KEY (tg_id)
