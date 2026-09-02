CREATE TABLE github_webhook_deliveries (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    github_delivery_id VARCHAR(128) NOT NULL,
    event_name VARCHAR(64) NOT NULL,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL,
    payload TEXT NOT NULL,
    CONSTRAINT uq_github_webhook_deliveries_delivery_id UNIQUE (github_delivery_id),
    CONSTRAINT ck_github_webhook_deliveries_delivery_id_not_blank
        CHECK (github_delivery_id !~ '[[:space:]]' AND char_length(github_delivery_id) > 0),
    CONSTRAINT ck_github_webhook_deliveries_event_name_not_blank
        CHECK (event_name !~ '[[:space:]]' AND char_length(event_name) > 0)
);
