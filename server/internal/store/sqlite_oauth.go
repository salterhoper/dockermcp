package store

import (
	"context"
	"encoding/json"
	"time"
)

func (s *sqliteStore) CreateOAuthClient(ctx context.Context, id, name string, redirectURIs []string) (*OAuthClient, error) {
	uris, err := json.Marshal(redirectURIs)
	if err != nil {
		return nil, wrapSQLite("store: create oauth client", err)
	}
	now := time.Now().UTC()
	if _, err := s.db.ExecContext(ctx, `
		INSERT INTO oauth_clients (id, client_name, redirect_uris, created_at)
		VALUES ($1, $2, $3, $4)`, id, name, string(uris), now); err != nil {
		return nil, wrapSQLite("store: create oauth client", err)
	}
	return &OAuthClient{ID: id, ClientName: name, RedirectURIs: redirectURIs, CreatedAt: now}, nil
}

func (s *sqliteStore) OAuthClientByID(ctx context.Context, id string) (*OAuthClient, error) {
	row := s.db.QueryRowContext(ctx,
		`SELECT id, client_name, redirect_uris, created_at FROM oauth_clients WHERE id = $1`, id)
	c, err := scanOAuthClient(row)
	return c, wrapSQLite("store: oauth client", err)
}

func (s *sqliteStore) CreateAuthRequest(ctx context.Context, req AuthRequest) error {
	_, err := s.db.ExecContext(ctx, `
		INSERT INTO oauth_auth_requests
			(id, client_id, redirect_uri, client_state, code_challenge, code_challenge_method, scope, resource, created_at, expires_at)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)`,
		req.ID, req.ClientID, req.RedirectURI, req.ClientState,
		req.CodeChallenge, req.CodeChallengeMethod, req.Scope, req.Resource, time.Now().UTC(), req.ExpiresAt)
	return wrapSQLite("store: create auth request", err)
}

func (s *sqliteStore) TakeAuthRequest(ctx context.Context, id string) (*AuthRequest, error) {
	// Expiry is enforced by the Store wrapper (in Go); here we only delete and
	// return the row so it is single use.
	row := s.db.QueryRowContext(ctx, `
		DELETE FROM oauth_auth_requests
		WHERE id = $1
		RETURNING id, client_id, redirect_uri, client_state, code_challenge, code_challenge_method, scope, resource, expires_at`, id)
	req, err := scanAuthRequest(row)
	return req, wrapSQLite("store: auth request", err)
}

func (s *sqliteStore) CreateAuthCode(ctx context.Context, codeHash []byte, code AuthCode) error {
	_, err := s.db.ExecContext(ctx, `
		INSERT INTO oauth_auth_codes
			(code_hash, client_id, user_id, redirect_uri, code_challenge, code_challenge_method, scope, created_at, expires_at)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)`,
		codeHash, code.ClientID, code.UserID, code.RedirectURI,
		code.CodeChallenge, code.CodeChallengeMethod, code.Scope, time.Now().UTC(), code.ExpiresAt)
	return wrapSQLite("store: create auth code", err)
}

func (s *sqliteStore) ConsumeAuthCode(ctx context.Context, codeHash []byte) (*AuthCode, error) {
	// The single-use guard (consumed_at IS NULL) is enforced here; expiry is
	// checked by the Store wrapper in Go.
	row := s.db.QueryRowContext(ctx, `
		UPDATE oauth_auth_codes SET consumed_at = $1
		WHERE code_hash = $2 AND consumed_at IS NULL
		RETURNING client_id, user_id, redirect_uri, code_challenge, code_challenge_method, scope, expires_at`,
		time.Now().UTC(), codeHash)
	code, err := scanAuthCode(row)
	return code, wrapSQLite("store: auth code", err)
}
