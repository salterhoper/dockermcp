package api

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"net/url"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/sirtheprogrammer/docker-deployments/server/internal/auth"
	"github.com/sirtheprogrammer/docker-deployments/server/internal/config"
	"github.com/sirtheprogrammer/docker-deployments/server/internal/db"
	"github.com/sirtheprogrammer/docker-deployments/server/internal/googleoauth"
	"github.com/sirtheprogrammer/docker-deployments/server/internal/store"
)

const testAppURL = "https://mcp.example.com"

func newOAuthTestServer(t *testing.T) (*Server, http.Handler, *store.Store) {
	t.Helper()
	ctx := context.Background()
	log := slog.New(slog.NewTextHandler(io.Discard, nil))

	dbPath := filepath.Join(t.TempDir(), "oauth_api_test.db")
	sqlDB, err := db.ConnectSQLite(ctx, dbPath, log)
	if err != nil {
		t.Fatalf("ConnectSQLite: %v", err)
	}
	if err := db.MigrateSQLite(ctx, sqlDB, log); err != nil {
		t.Fatalf("MigrateSQLite: %v", err)
	}
	t.Cleanup(func() { sqlDB.Close() })
	st := store.NewSQLite(sqlDB)

	var sessSecret [32]byte
	_, _ = rand.Read(sessSecret[:])

	s := &Server{
		Config: &config.Config{
			Env:                "development",
			AppURL:             testAppURL,
			GoogleClientID:     "test-client-id",
			GoogleClientSecret: "test-client-secret",
			MCPTokenTTL:        90 * 24 * time.Hour,
		},
		Log:     log,
		Store:   st,
		Hasher:  auth.NewHasher(sessSecret[:]),
		Google:  googleoauth.New("test-client-id", "test-client-secret"),
		Started: time.Now(),
	}
	h, err := s.Routes()
	if err != nil {
		t.Fatalf("Routes: %v", err)
	}
	return s, h, st
}

func pkceChallenge(verifier string) string {
	sum := sha256.Sum256([]byte(verifier))
	return base64.RawURLEncoding.EncodeToString(sum[:])
}

func TestOAuthMetadataDocuments(t *testing.T) {
	_, h, _ := newOAuthTestServer(t)

	t.Run("authorization server", func(t *testing.T) {
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/.well-known/oauth-authorization-server", nil))
		if rec.Code != http.StatusOK {
			t.Fatalf("status = %d, want 200", rec.Code)
		}
		var doc map[string]any
		if err := json.Unmarshal(rec.Body.Bytes(), &doc); err != nil {
			t.Fatalf("decode: %v", err)
		}
		if doc["issuer"] != testAppURL {
			t.Errorf("issuer = %v, want %s", doc["issuer"], testAppURL)
		}
		if doc["authorization_endpoint"] != testAppURL+"/oauth/authorize" {
			t.Errorf("authorization_endpoint = %v", doc["authorization_endpoint"])
		}
		if doc["token_endpoint"] != testAppURL+"/oauth/token" {
			t.Errorf("token_endpoint = %v", doc["token_endpoint"])
		}
		if doc["registration_endpoint"] != testAppURL+"/oauth/register" {
			t.Errorf("registration_endpoint = %v", doc["registration_endpoint"])
		}
	})

	t.Run("protected resource", func(t *testing.T) {
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/.well-known/oauth-protected-resource", nil))
		if rec.Code != http.StatusOK {
			t.Fatalf("status = %d, want 200", rec.Code)
		}
		var doc map[string]any
		if err := json.Unmarshal(rec.Body.Bytes(), &doc); err != nil {
			t.Fatalf("decode: %v", err)
		}
		if doc["resource"] != testAppURL+"/api/mcp" {
			t.Errorf("resource = %v", doc["resource"])
		}
	})
}

func TestMCPUnauthenticatedChallenge(t *testing.T) {
	_, h, _ := newOAuthTestServer(t)
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/api/mcp", nil))

	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want 401", rec.Code)
	}
	challenge := rec.Header().Get("WWW-Authenticate")
	want := `resource_metadata="` + testAppURL + `/.well-known/oauth-protected-resource"`
	if !strings.Contains(challenge, want) {
		t.Errorf("WWW-Authenticate = %q, want it to contain %q", challenge, want)
	}
}

func TestOAuthRegister(t *testing.T) {
	_, h, _ := newOAuthTestServer(t)

	body := strings.NewReader(`{"redirect_uris":["https://client.example/cb"],"client_name":"Gemini"}`)
	req := httptest.NewRequest(http.MethodPost, "/oauth/register", body)
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)

	if rec.Code != http.StatusCreated {
		t.Fatalf("status = %d, want 201; body=%s", rec.Code, rec.Body.String())
	}
	var resp map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if id, _ := resp["client_id"].(string); !strings.HasPrefix(id, "mcpc_") {
		t.Errorf("client_id = %v, want mcpc_ prefix", resp["client_id"])
	}

	// No redirect URIs must be rejected.
	rec = httptest.NewRecorder()
	req = httptest.NewRequest(http.MethodPost, "/oauth/register", strings.NewReader(`{"client_name":"x"}`))
	req.Header.Set("Content-Type", "application/json")
	h.ServeHTTP(rec, req)
	if rec.Code != http.StatusBadRequest {
		t.Errorf("empty redirect_uris: status = %d, want 400", rec.Code)
	}
}

func TestOAuthAuthorizeRejectsBadPKCE(t *testing.T) {
	_, h, st := newOAuthTestServer(t)
	ctx := context.Background()

	redirect := "https://client.example/cb"
	if _, err := st.CreateOAuthClient(ctx, "mcpc_test", "Gemini", []string{redirect}); err != nil {
		t.Fatalf("CreateOAuthClient: %v", err)
	}

	// Missing PKCE challenge must bounce back to the client with an error.
	q := url.Values{}
	q.Set("response_type", "code")
	q.Set("client_id", "mcpc_test")
	q.Set("redirect_uri", redirect)
	q.Set("state", "xyz")
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/oauth/authorize?"+q.Encode(), nil))

	if rec.Code != http.StatusFound {
		t.Fatalf("status = %d, want 302", rec.Code)
	}
	loc, err := url.Parse(rec.Header().Get("Location"))
	if err != nil {
		t.Fatalf("parse Location: %v", err)
	}
	if loc.Query().Get("error") != "invalid_request" {
		t.Errorf("error = %q, want invalid_request", loc.Query().Get("error"))
	}

	// Unknown client must not redirect anywhere: it renders an HTML error.
	rec = httptest.NewRecorder()
	q.Set("client_id", "nope")
	q.Set("code_challenge", "whatever")
	q.Set("code_challenge_method", "S256")
	h.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/oauth/authorize?"+q.Encode(), nil))
	if rec.Code != http.StatusBadRequest {
		t.Errorf("unknown client: status = %d, want 400", rec.Code)
	}
}

func TestOAuthConfidentialClientFlow(t *testing.T) {
	s, h, st := newOAuthTestServer(t)
	// Provision a fixed confidential client, as used by Gemini's connector.
	const (
		cid       = "gemini-client"
		csecret   = "super-secret-value-123456"
		cRedirect = "https://oauth-redirect.googleusercontent.com/r/user_bound_custom-mcp-abc"
	)
	s.Config.StaticClientID = cid
	s.Config.StaticClientSecret = csecret
	s.Config.StaticRedirectURIs = []string{cRedirect}

	ctx := context.Background()
	admin, err := st.CreateFirstAdmin(ctx, "admin@example.com", "Admin", "hash")
	if err != nil {
		t.Fatalf("CreateFirstAdmin: %v", err)
	}

	t.Run("authorize without PKCE is accepted and redirects to Google", func(t *testing.T) {
		q := url.Values{}
		q.Set("response_type", "code")
		q.Set("client_id", cid)
		q.Set("redirect_uri", cRedirect)
		q.Set("state", "st")
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/oauth/authorize?"+q.Encode(), nil))
		if rec.Code != http.StatusFound {
			t.Fatalf("status = %d, want 302; body=%s", rec.Code, rec.Body.String())
		}
		if loc := rec.Header().Get("Location"); !strings.HasPrefix(loc, "https://accounts.google.com/") {
			t.Errorf("Location = %q, want redirect to Google", loc)
		}
	})

	post := func(form url.Values) *httptest.ResponseRecorder {
		req := httptest.NewRequest(http.MethodPost, "/oauth/token", strings.NewReader(form.Encode()))
		req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, req)
		return rec
	}

	// An authorization code issued to the confidential client, with no PKCE.
	writeCode := func() string {
		plain := randToken()
		if err := st.CreateAuthCode(ctx, s.Hasher.Hash(plain), store.AuthCode{
			ClientID:    cid,
			UserID:      admin.ID,
			RedirectURI: cRedirect,
			Scope:       "mcp",
			ExpiresAt:   time.Now().Add(5 * time.Minute),
		}); err != nil {
			t.Fatalf("CreateAuthCode: %v", err)
		}
		return plain
	}

	t.Run("token with client secret succeeds", func(t *testing.T) {
		code := writeCode()
		rec := post(url.Values{
			"grant_type":    {"authorization_code"},
			"code":          {code},
			"redirect_uri":  {cRedirect},
			"client_id":     {cid},
			"client_secret": {csecret},
		})
		if rec.Code != http.StatusOK {
			t.Fatalf("status = %d, want 200; body=%s", rec.Code, rec.Body.String())
		}
		var resp map[string]any
		_ = json.Unmarshal(rec.Body.Bytes(), &resp)
		if tok, _ := resp["access_token"].(string); !strings.HasPrefix(tok, auth.APITokenPrefix) {
			t.Errorf("access_token = %v", resp["access_token"])
		}
	})

	t.Run("token with wrong secret is rejected", func(t *testing.T) {
		code := writeCode()
		rec := post(url.Values{
			"grant_type":    {"authorization_code"},
			"code":          {code},
			"redirect_uri":  {cRedirect},
			"client_id":     {cid},
			"client_secret": {"wrong"},
		})
		if rec.Code != http.StatusUnauthorized {
			t.Fatalf("status = %d, want 401; body=%s", rec.Code, rec.Body.String())
		}
		var resp map[string]string
		_ = json.Unmarshal(rec.Body.Bytes(), &resp)
		if resp["error"] != "invalid_client" {
			t.Errorf("error = %q, want invalid_client", resp["error"])
		}
	})
}

func TestOAuthTokenFlow(t *testing.T) {
	s, h, st := newOAuthTestServer(t)
	ctx := context.Background()

	admin, err := st.CreateFirstAdmin(ctx, "admin@example.com", "Admin", "hash")
	if err != nil {
		t.Fatalf("CreateFirstAdmin: %v", err)
	}

	const verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
	redirect := "https://client.example/cb"

	// writeCode mints a fresh authorization code each call (the token endpoint
	// marks a code consumed rather than deleting it, so reusing one plaintext
	// across subtests would collide on the primary key).
	writeCode := func() string {
		plainCode := randToken()
		if err := st.CreateAuthCode(ctx, s.Hasher.Hash(plainCode), store.AuthCode{
			ClientID:            "mcpc_test",
			UserID:              admin.ID,
			RedirectURI:         redirect,
			CodeChallenge:       pkceChallenge(verifier),
			CodeChallengeMethod: "S256",
			Scope:               "mcp",
			ExpiresAt:           time.Now().Add(5 * time.Minute),
		}); err != nil {
			t.Fatalf("CreateAuthCode: %v", err)
		}
		return plainCode
	}

	post := func(form url.Values) *httptest.ResponseRecorder {
		req := httptest.NewRequest(http.MethodPost, "/oauth/token", strings.NewReader(form.Encode()))
		req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, req)
		return rec
	}

	goodForm := func(code string) url.Values {
		return url.Values{
			"grant_type":    {"authorization_code"},
			"code":          {code},
			"redirect_uri":  {redirect},
			"code_verifier": {verifier},
			"client_id":     {"mcpc_test"},
		}
	}

	t.Run("happy path mints a usable bearer", func(t *testing.T) {
		code := writeCode()
		rec := post(goodForm(code))
		if rec.Code != http.StatusOK {
			t.Fatalf("status = %d, want 200; body=%s", rec.Code, rec.Body.String())
		}
		var resp map[string]any
		if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil {
			t.Fatalf("decode: %v", err)
		}
		tok, _ := resp["access_token"].(string)
		if !strings.HasPrefix(tok, auth.APITokenPrefix) {
			t.Fatalf("access_token = %q, want %s prefix", tok, auth.APITokenPrefix)
		}
		if resp["token_type"] != "Bearer" {
			t.Errorf("token_type = %v, want Bearer", resp["token_type"])
		}
		// The minted token must authenticate as the mapped user.
		tu, err := st.APITokenByHash(ctx, s.Hasher.Hash(tok))
		if err != nil {
			t.Fatalf("APITokenByHash: %v", err)
		}
		if tu.User.ID != admin.ID {
			t.Errorf("token user = %s, want %s", tu.User.ID, admin.ID)
		}

		// The code is single use: replaying it fails.
		rec = post(goodForm(code))
		if rec.Code != http.StatusBadRequest {
			t.Errorf("replay status = %d, want 400", rec.Code)
		}
	})

	t.Run("wrong verifier is rejected", func(t *testing.T) {
		code := writeCode()
		form := goodForm(code)
		form.Set("code_verifier", "the-wrong-verifier-entirely-abcdef")
		rec := post(form)
		if rec.Code != http.StatusBadRequest {
			t.Fatalf("status = %d, want 400", rec.Code)
		}
		var resp map[string]string
		_ = json.Unmarshal(rec.Body.Bytes(), &resp)
		if resp["error"] != "invalid_grant" {
			t.Errorf("error = %q, want invalid_grant", resp["error"])
		}
	})

	t.Run("mismatched redirect_uri is rejected", func(t *testing.T) {
		code := writeCode()
		form := goodForm(code)
		form.Set("redirect_uri", "https://attacker.example/cb")
		rec := post(form)
		if rec.Code != http.StatusBadRequest {
			t.Errorf("status = %d, want 400", rec.Code)
		}
	})
}
