-- +goose Up

CREATE TABLE invitation_server_grants (
    invitation_id TEXT NOT NULL REFERENCES invitations(id) ON DELETE CASCADE,
    server_id     TEXT NOT NULL REFERENCES servers(id)     ON DELETE CASCADE,
    permission    TEXT NOT NULL DEFAULT 'operate',
    PRIMARY KEY (invitation_id, server_id),
    CONSTRAINT invitation_server_grants_perm_valid
        CHECK (permission IN ('read', 'operate'))
);

-- +goose Down
DROP TABLE invitation_server_grants;
