--liquibase formatted sql

--changeset petrm:make-vless-link-nullable
ALTER TABLE config ALTER COLUMN vless_link DROP NOT NULL;
--rollback ALTER TABLE config ALTER COLUMN vless_link SET NOT NULL;
