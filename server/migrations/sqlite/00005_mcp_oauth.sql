-- +goose Up

-- Dynamically registered OAuth clients (RFC 7591). MCP clients such as Gemini
-- self-register here before starting the authorization-code + PKCE flow.
CREATE TABLE oauth_clients (
    id            TEXT PRIMARY KEY,
    client_name   TEXT NOT NULL DEFAULT '',
    redirect_uris TEXT NOT NULL,            -- JSON array of allowed redirect URIs
    created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- A pending authorization request, parked while the human authenticates with
-- Google. The id is the opaque state we hand to Google and expect back.
CREATE TABLE oauth_auth_requests (
    id                    TEXT PRIMARY KEY,
    client_id             TEXT NOT NULL,
    redirect_uri          TEXT NOT NULL,
    client_state          TEXT NOT NULL DEFAULT '',
    code_challenge        TEXT NOT NULL,
    code_challenge_method TEXT NOT NULL,
    scope                 TEXT NOT NULL DEFAULT '',
    resource              TEXT NOT NULL DEFAULT '',
    created_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at            DATETIME NOT NULL
);

-- A single-use authorization code, issued after a successful Google login and
-- redeemed at the token endpoint. Stored hashed, like every other credential.
CREATE TABLE oauth_auth_codes (
    code_hash             BLOB PRIMARY KEY,
    client_id             TEXT NOT NULL,
    user_id               TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    redirect_uri          TEXT NOT NULL,
    code_challenge        TEXT NOT NULL,
    code_challenge_method TEXT NOT NULL,
    scope                 TEXT NOT NULL DEFAULT '',
    created_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at            DATETIME NOT NULL,
    consumed_at           DATETIME
);

CREATE INDEX oauth_auth_codes_user_id_idx ON oauth_auth_codes (user_id);

-- +goose Down

DROP TABLE oauth_auth_codes;
DROP TABLE oauth_auth_requests;
DROP TABLE oauth_clients;
