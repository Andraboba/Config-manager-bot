--liquibase formatted sql

--changeset petrm:add-xhttp-link-column
ALTER TABLE config ADD COLUMN IF NOT EXISTS xhttp_link TEXT;
--rollback ALTER TABLE config DROP COLUMN IF EXISTS xhttp_link;
