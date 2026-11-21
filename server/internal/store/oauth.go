package store

import (
	"context"
	"encoding/json"
	"time"
)

// OAuthClient is a dynamically registered MCP client (RFC 7591).
type OAuthClient struct {
	ID           string
	ClientName   string
	RedirectURIs []string
	CreatedAt    time.Time
}

// AuthRequest is a pending authorization parked while the user logs in with
// Google. ID is the opaque state handed to Google and expected back.
type AuthRequest struct {
	ID                  string
	ClientID            string
	RedirectURI         string
	ClientState         string
	CodeChallenge       string
	CodeChallengeMethod string
	Scope               string
	Resource            string
	ExpiresAt           time.Time
}

// AuthCode is a single-use authorization code, stored hashed, redeemed at the
// token endpoint.
type AuthCode struct {
	ClientID            string
	UserID              string
	RedirectURI         string
	CodeChallenge       string
	CodeChallengeMethod string
	Scope               string
	ExpiresAt           time.Time
}

// --- clients ------------------------------------------------------------

func (s *Store) CreateOAuthClient(ctx context.Context, id, name string, redirectURIs []string) (*OAuthClient, error) {
	if s.sqlite != nil {
		return s.sqlite.CreateOAuthClient(ctx, id, name, redirectURIs)
	}
	uris, err := json.Marshal(redirectURIs)
	if err != nil {
		return nil, wrap("store: create oauth client", err)
	}
	row := s.pool.QueryRow(ctx, `
		INSERT INTO oauth_clients (id, client_name, redirect_uris)
		VALUES ($1, $2, $3)
		RETURNING created_at`, id, name, string(uris))
	var created time.Time
	if err := row.Scan(&created); err != nil {
		return nil, wrap("store: create oauth client", err)
	}
	return &OAuthClient{ID: id, ClientName: name, RedirectURIs: redirectURIs, CreatedAt: created}, nil
}

func (s *Store) OAuthClientByID(ctx context.Context, id string) (*OAuthClient, error) {
	if s.sqlite != nil {
		return s.sqlite.OAuthClientByID(ctx, id)
	}
	row := s.pool.QueryRow(ctx,
		`SELECT id, client_name, redirect_uris, created_at FROM oauth_clients WHERE id = $1`, id)
	c, err := scanOAuthClient(row)
	return c, wrap("store: oauth client", err)
}

// --- authorization requests --------------------------------------------

func (s *Store) CreateAuthRequest(ctx context.Context, req AuthRequest) error {
	if s.sqlite != nil {
		return s.sqlite.CreateAuthRequest(ctx, req)
	}
	_, err := s.pool.Exec(ctx, `
		INSERT INTO oauth_auth_requests
			(id, client_id, redirect_uri, client_state, code_challenge, code_challenge_method, scope, resource, expires_at)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)`,
		req.ID, req.ClientID, req.RedirectURI, req.ClientState,
		req.CodeChallenge, req.CodeChallengeMethod, req.Scope, req.Resource, req.ExpiresAt)
	return wrap("store: create auth request", err)
}

// TakeAuthRequest fetches and deletes a pending request in one step, so a
// replayed Google callback cannot reuse it. ErrNotFound covers missing/expired.
// Expiry is enforced in Go rather than in SQL so the check is identical and
// reliable across both backends (SQLite's datetime comparison is affinity
// dependent); the row is deleted either way, which also cleans up stale entries.
func (s *Store) TakeAuthRequest(ctx context.Context, id string) (*AuthRequest, error) {
	var (
		req *AuthRequest
		err error
	)
	if s.sqlite != nil {
		req, err = s.sqlite.TakeAuthRequest(ctx, id)
	} else {
		row := s.pool.QueryRow(ctx, `
			DELETE FROM oauth_auth_requests
			WHERE id = $1
			RETURNING id, client_id, redirect_uri, client_state, code_challenge, code_challenge_method, scope, resource, expires_at`, id)
		req, err = scanAuthRequest(row)
		err = wrap("store: auth request", err)
	}
	if err != nil {
		return nil, err
	}
	if time.Now().After(req.ExpiresAt) {
		return nil, ErrNotFound
	}
	return req, nil
}

// --- authorization codes ------------------------------------------------

func (s *Store) CreateAuthCode(ctx context.Context, codeHash []byte, code AuthCode) error {
	if s.sqlite != nil {
		return s.sqlite.CreateAuthCode(ctx, codeHash, code)
	}
	_, err := s.pool.Exec(ctx, `
		INSERT INTO oauth_auth_codes
			(code_hash, client_id, user_id, redirect_uri, code_challenge, code_challenge_method, scope, expires_at)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8)`,
		codeHash, code.ClientID, code.UserID, code.RedirectURI,
		code.CodeChallenge, code.CodeChallengeMethod, code.Scope, code.ExpiresAt)
	return wrap("store: create auth code", err)
}

// ConsumeAuthCode atomically marks a code consumed and returns it, so a code
// can be redeemed exactly once. ErrNotFound covers missing/expired/already-used.
// The single-use guard (consumed_at IS NULL) stays in SQL; expiry is checked in
// Go for the same cross-backend reliability reason as TakeAuthRequest. Marking
// an expired code consumed is harmless -- it was unusable regardless.
func (s *Store) ConsumeAuthCode(ctx context.Context, codeHash []byte) (*AuthCode, error) {
	var (
		code *AuthCode
		err  error
	)
	if s.sqlite != nil {
		code, err = s.sqlite.ConsumeAuthCode(ctx, codeHash)
	} else {
		row := s.pool.QueryRow(ctx, `
			UPDATE oauth_auth_codes SET consumed_at = now()
			WHERE code_hash = $1 AND consumed_at IS NULL
			RETURNING client_id, user_id, redirect_uri, code_challenge, code_challenge_method, scope, expires_at`, codeHash)
		code, err = scanAuthCode(row)
		err = wrap("store: auth code", err)
	}
	if err != nil {
		return nil, err
	}
	if time.Now().After(code.ExpiresAt) {
		return nil, ErrNotFound
	}
	return code, nil
}

// --- row scanning (shared shape for pgx and database/sql) ---------------

type scannable interface {
	Scan(dest ...any) error
}

// The scanners below return the raw error so each dialect's caller can wrap it
// with the matching helper (wrap for pgx, wrapSQLite for database/sql), which is
// what maps a no-rows result to ErrNotFound.

func scanOAuthClient(row scannable) (*OAuthClient, error) {
	var (
		c    OAuthClient
		uris string
	)
	if err := row.Scan(&c.ID, &c.ClientName, &uris, &c.CreatedAt); err != nil {
		return nil, err
	}
	if err := json.Unmarshal([]byte(uris), &c.RedirectURIs); err != nil {
		return nil, err
	}
	return &c, nil
}

func scanAuthRequest(row scannable) (*AuthRequest, error) {
	var req AuthRequest
	if err := row.Scan(&req.ID, &req.ClientID, &req.RedirectURI, &req.ClientState,
		&req.CodeChallenge, &req.CodeChallengeMethod, &req.Scope, &req.Resource, &req.ExpiresAt); err != nil {
		return nil, err
	}
	return &req, nil
}

func scanAuthCode(row scannable) (*AuthCode, error) {
	var code AuthCode
	if err := row.Scan(&code.ClientID, &code.UserID, &code.RedirectURI,
		&code.CodeChallenge, &code.CodeChallengeMethod, &code.Scope, &code.ExpiresAt); err != nil {
		return nil, err
	}
	return &code, nil
}
