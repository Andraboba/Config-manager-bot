--liquibase formatted sql

--changeset petrm:add-country-to-config
ALTER TABLE config ADD COLUMN IF NOT EXISTS country VARCHAR(10) NOT NULL DEFAULT 'latv';
--rollback ALTER TABLE config DROP COLUMN IF EXISTS country;
