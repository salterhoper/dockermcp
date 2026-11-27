-- +goose Up

CREATE TABLE invitation_server_grants (
    invitation_id uuid NOT NULL REFERENCES invitations(id) ON DELETE CASCADE,
    server_id     uuid NOT NULL REFERENCES servers(id)     ON DELETE CASCADE,
    permission    text NOT NULL DEFAULT 'operate',
    PRIMARY KEY (invitation_id, server_id),
    CONSTRAINT invitation_server_grants_perm_valid
        CHECK (permission IN ('read', 'operate'))
);

-- +goose Down
DROP TABLE invitation_server_grants;
