package store

import (
	"context"
	"errors"
	"testing"
	"time"
)

func TestOAuthClientRoundTrip(t *testing.T) {
	st, _ := setupTestSQLiteStore(t)
	ctx := context.Background()

	uris := []string{"https://client.example/cb", "http://localhost:4000/callback"}
	created, err := st.CreateOAuthClient(ctx, "mcpc_abc", "Gemini", uris)
	if err != nil {
		t.Fatalf("CreateOAuthClient: %v", err)
	}
	if created.ID != "mcpc_abc" || len(created.RedirectURIs) != 2 {
		t.Fatalf("unexpected client: %+v", created)
	}

	got, err := st.OAuthClientByID(ctx, "mcpc_abc")
	if err != nil {
		t.Fatalf("OAuthClientByID: %v", err)
	}
	if got.ClientName != "Gemini" || got.RedirectURIs[0] != uris[0] || got.RedirectURIs[1] != uris[1] {
		t.Fatalf("round trip mismatch: %+v", got)
	}

	if _, err := st.OAuthClientByID(ctx, "missing"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("missing client: got %v, want ErrNotFound", err)
	}
}

func TestAuthRequestTakenOnce(t *testing.T) {
	st, _ := setupTestSQLiteStore(t)
	ctx := context.Background()

	req := AuthRequest{
		ID:                  "state123",
		ClientID:            "mcpc_abc",
		RedirectURI:         "https://client.example/cb",
		ClientState:         "clientstate",
		CodeChallenge:       "challenge",
		CodeChallengeMethod: "S256",
		Scope:               "mcp",
		ExpiresAt:           time.Now().Add(10 * time.Minute),
	}
	if err := st.CreateAuthRequest(ctx, req); err != nil {
		t.Fatalf("CreateAuthRequest: %v", err)
	}

	got, err := st.TakeAuthRequest(ctx, "state123")
	if err != nil {
		t.Fatalf("TakeAuthRequest: %v", err)
	}
	if got.ClientID != "mcpc_abc" || got.RedirectURI != req.RedirectURI || got.ClientState != "clientstate" {
		t.Fatalf("round trip mismatch: %+v", got)
	}

	// A second take must fail: the request is single use.
	if _, err := st.TakeAuthRequest(ctx, "state123"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("second take: got %v, want ErrNotFound", err)
	}
}

func TestAuthRequestExpired(t *testing.T) {
	st, _ := setupTestSQLiteStore(t)
	ctx := context.Background()

	if err := st.CreateAuthRequest(ctx, AuthRequest{
		ID:                  "old",
		ClientID:            "c",
		RedirectURI:         "https://x/cb",
		CodeChallenge:       "ch",
		CodeChallengeMethod: "S256",
		ExpiresAt:           time.Now().Add(-time.Minute),
	}); err != nil {
		t.Fatalf("CreateAuthRequest: %v", err)
	}
	if _, err := st.TakeAuthRequest(ctx, "old"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("expired take: got %v, want ErrNotFound", err)
	}
}

func TestAuthCodeConsumedOnce(t *testing.T) {
	st, _ := setupTestSQLiteStore(t)
	ctx := context.Background()

	admin, err := st.CreateFirstAdmin(ctx, "admin@example.com", "Admin", "hash")
	if err != nil {
		t.Fatalf("CreateFirstAdmin: %v", err)
	}

	hash := []byte("code-hash-bytes-xxxxxxxxxxxxxxxx")
	code := AuthCode{
		ClientID:            "mcpc_abc",
		UserID:              admin.ID,
		RedirectURI:         "https://client.example/cb",
		CodeChallenge:       "ch",
		CodeChallengeMethod: "S256",
		Scope:               "mcp",
		ExpiresAt:           time.Now().Add(5 * time.Minute),
	}
	if err := st.CreateAuthCode(ctx, hash, code); err != nil {
		t.Fatalf("CreateAuthCode: %v", err)
	}

	got, err := st.ConsumeAuthCode(ctx, hash)
	if err != nil {
		t.Fatalf("ConsumeAuthCode: %v", err)
	}
	if got.UserID != admin.ID || got.RedirectURI != code.RedirectURI {
		t.Fatalf("consume mismatch: %+v", got)
	}

	// Replaying the same code must fail.
	if _, err := st.ConsumeAuthCode(ctx, hash); !errors.Is(err, ErrNotFound) {
		t.Fatalf("replay: got %v, want ErrNotFound", err)
	}
}

func TestAuthCodeExpired(t *testing.T) {
	st, _ := setupTestSQLiteStore(t)
	ctx := context.Background()

	admin, err := st.CreateFirstAdmin(ctx, "admin@example.com", "Admin", "hash")
	if err != nil {
		t.Fatalf("CreateFirstAdmin: %v", err)
	}
	hash := []byte("expired-code-hash-yyyyyyyyyyyyyy")
	if err := st.CreateAuthCode(ctx, hash, AuthCode{
		ClientID:            "c",
		UserID:              admin.ID,
		RedirectURI:         "https://x/cb",
		CodeChallenge:       "ch",
		CodeChallengeMethod: "S256",
		ExpiresAt:           time.Now().Add(-time.Minute),
	}); err != nil {
		t.Fatalf("CreateAuthCode: %v", err)
	}
	if _, err := st.ConsumeAuthCode(ctx, hash); !errors.Is(err, ErrNotFound) {
		t.Fatalf("expired consume: got %v, want ErrNotFound", err)
	}
}
