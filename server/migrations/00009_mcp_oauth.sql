-- +goose Up

-- Dynamically registered OAuth clients (RFC 7591). MCP clients such as Gemini
-- self-register here before starting the authorization-code + PKCE flow.
CREATE TABLE oauth_clients (
    id            text        PRIMARY KEY,
    client_name   text        NOT NULL DEFAULT '',
    redirect_uris text        NOT NULL,            -- JSON array of allowed redirect URIs
    created_at    timestamptz NOT NULL DEFAULT now()
);

-- A pending authorization request, parked while the human authenticates with
-- Google. The id is the opaque state we hand to Google and expect back.
CREATE TABLE oauth_auth_requests (
    id                    text        PRIMARY KEY,
    client_id             text        NOT NULL,
    redirect_uri          text        NOT NULL,
    client_state          text        NOT NULL DEFAULT '',
    code_challenge        text        NOT NULL,
    code_challenge_method text        NOT NULL,
    scope                 text        NOT NULL DEFAULT '',
    resource              text        NOT NULL DEFAULT '',
    created_at            timestamptz NOT NULL DEFAULT now(),
    expires_at            timestamptz NOT NULL
);

-- A single-use authorization code, issued after a successful Google login and
-- redeemed at the token endpoint. Stored hashed, like every other credential.
CREATE TABLE oauth_auth_codes (
    code_hash             bytea       PRIMARY KEY,
    client_id             text        NOT NULL,
    user_id               uuid        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    redirect_uri          text        NOT NULL,
    code_challenge        text        NOT NULL,
    code_challenge_method text        NOT NULL,
    scope                 text        NOT NULL DEFAULT '',
    created_at            timestamptz NOT NULL DEFAULT now(),
    expires_at            timestamptz NOT NULL,
    consumed_at           timestamptz
);

CREATE INDEX oauth_auth_codes_user_id_idx ON oauth_auth_codes (user_id);

-- +goose Down

DROP TABLE oauth_auth_codes;
DROP TABLE oauth_auth_requests;
DROP TABLE oauth_clients;
