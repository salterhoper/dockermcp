package api

import (
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"html"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/sirtheprogrammer/docker-deployments/server/internal/auth"
	"github.com/sirtheprogrammer/docker-deployments/server/internal/store"
)

// This file implements the OAuth 2.1 Authorization Server + Protected Resource
// surface that lets a spec MCP client (e.g. Gemini) connect to /api/mcp:
//
//   discovery -> dynamic client registration -> authorize (login via Google)
//   -> authorization code -> token (a ddp_ API token) -> call /api/mcp
//
// The access token we mint is an ordinary API token, so the existing bearer
// validation and per-tool role/grant checks apply unchanged. Google is used
// only to authenticate the human; the email must already map to a dockdeploy
// user, which is the access gate.

const mcpScope = "mcp"

// --- discovery metadata -------------------------------------------------

func (s *Server) handleOAuthMetadata(w http.ResponseWriter, r *http.Request) error {
	base := s.Config.AppURL
	w.Header().Set("Access-Control-Allow-Origin", "*")
	return JSON(w, s.Log, http.StatusOK, map[string]any{
		"issuer":                                base,
		"authorization_endpoint":                base + "/oauth/authorize",
		"token_endpoint":                        base + "/oauth/token",
		"registration_endpoint":                 base + "/oauth/register",
		"response_types_supported":              []string{"code"},
		"grant_types_supported":                 []string{"authorization_code"},
		"code_challenge_methods_supported":      []string{auth.PKCEMethodS256},
		"token_endpoint_auth_methods_supported": []string{"none"},
		"scopes_supported":                      []string{mcpScope},
	})
}

func (s *Server) handleProtectedResourceMetadata(w http.ResponseWriter, r *http.Request) error {
	base := s.Config.AppURL
	w.Header().Set("Access-Control-Allow-Origin", "*")
	return JSON(w, s.Log, http.StatusOK, map[string]any{
		"resource":                 base + "/api/mcp",
		"authorization_servers":    []string{base},
		"scopes_supported":         []string{mcpScope},
		"bearer_methods_supported": []string{"header"},
	})
}

// --- dynamic client registration (RFC 7591) -----------------------------

func (s *Server) handleOAuthRegister(w http.ResponseWriter, r *http.Request) error {
	// Decode leniently: clients send many optional metadata fields we do not
	// model, and DisallowUnknownFields would reject an otherwise valid request.
	var body struct {
		RedirectURIs []string `json:"redirect_uris"`
		ClientName   string   `json:"client_name"`
	}
	dec := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<20))
	if err := dec.Decode(&body); err != nil {
		return s.oauthJSONError(w, http.StatusBadRequest, "invalid_client_metadata", "request body must be JSON")
	}
	if len(body.RedirectURIs) == 0 {
		return s.oauthJSONError(w, http.StatusBadRequest, "invalid_redirect_uri", "at least one redirect_uri is required")
	}
	for _, u := range body.RedirectURIs {
		if parsed, err := url.Parse(u); err != nil || parsed.Scheme == "" {
			return s.oauthJSONError(w, http.StatusBadRequest, "invalid_redirect_uri", "redirect_uri is not a valid absolute URI: "+u)
		}
	}

	client, err := s.Store.CreateOAuthClient(r.Context(), "mcpc_"+randToken(), body.ClientName, body.RedirectURIs)
	if err != nil {
		return Internal(err)
	}
	s.Log.Info("mcp oauth: client registered", "client", client.ID, "name", client.ClientName)

	w.Header().Set("Cache-Control", "no-store")
	return JSON(w, s.Log, http.StatusCreated, map[string]any{
		"client_id":                  client.ID,
		"client_id_issued_at":        client.CreatedAt.Unix(),
		"redirect_uris":              client.RedirectURIs,
		"client_name":                client.ClientName,
		"token_endpoint_auth_method": "none",
		"grant_types":                []string{"authorization_code"},
		"response_types":             []string{"code"},
	})
}

// --- authorize: validate, then hand off to Google -----------------------

func (s *Server) handleOAuthAuthorize(w http.ResponseWriter, r *http.Request) error {
	q := r.URL.Query()
	clientID := q.Get("client_id")
	redirectURI := q.Get("redirect_uri")

	client, err := s.Store.OAuthClientByID(r.Context(), clientID)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			return s.oauthHTMLError(w, http.StatusBadRequest, "Unknown client",
				"This OAuth client is not registered. Reconnect the MCP server from your client.")
		}
		return Internal(err)
	}
	// The redirect target must be pre-registered before we will send anything,
	// including an error, to it.
	if redirectURI == "" || !containsStr(client.RedirectURIs, redirectURI) {
		return s.oauthHTMLError(w, http.StatusBadRequest, "Invalid redirect",
			"The redirect URI does not match this client's registration.")
	}

	state := q.Get("state")
	if q.Get("response_type") != "code" {
		return s.redirectOAuthError(w, r, redirectURI, state, "unsupported_response_type", "only the authorization code flow is supported")
	}
	challenge := q.Get("code_challenge")
	if challenge == "" || q.Get("code_challenge_method") != auth.PKCEMethodS256 {
		return s.redirectOAuthError(w, r, redirectURI, state, "invalid_request", "PKCE with S256 is required")
	}

	googleState := randToken()
	if err := s.Store.CreateAuthRequest(r.Context(), store.AuthRequest{
		ID:                  googleState,
		ClientID:            clientID,
		RedirectURI:         redirectURI,
		ClientState:         state,
		CodeChallenge:       challenge,
		CodeChallengeMethod: auth.PKCEMethodS256,
		Scope:               q.Get("scope"),
		Resource:            q.Get("resource"),
		ExpiresAt:           time.Now().Add(10 * time.Minute),
	}); err != nil {
		return Internal(err)
	}

	http.Redirect(w, r, s.Google.AuthCodeURL(googleState, s.googleRedirectURI()), http.StatusFound)
	return nil
}

// --- Google callback: map email -> user, issue our code -----------------

func (s *Server) handleGoogleCallback(w http.ResponseWriter, r *http.Request) error {
	q := r.URL.Query()
	if gerr := q.Get("error"); gerr != "" {
		return s.oauthHTMLError(w, http.StatusBadRequest, "Google sign-in cancelled",
			"The Google sign-in did not complete. You can close this window and try again.")
	}
	state, code := q.Get("state"), q.Get("code")
	if state == "" || code == "" {
		return s.oauthHTMLError(w, http.StatusBadRequest, "Invalid callback", "Google returned an incomplete response.")
	}

	authReq, err := s.Store.TakeAuthRequest(r.Context(), state)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			return s.oauthHTMLError(w, http.StatusBadRequest, "Session expired",
				"Your sign-in session expired. Start the connection again from your MCP client.")
		}
		return Internal(err)
	}

	identity, err := s.Google.Exchange(r.Context(), code, s.googleRedirectURI())
	if err != nil {
		s.Log.Warn("mcp oauth: google code exchange failed", "error", err)
		return s.oauthHTMLError(w, http.StatusBadGateway, "Google sign-in failed",
			"We could not verify your Google account. Please try again.")
	}

	email := store.NormaliseEmail(identity.Email)
	if !identity.EmailVerified || email == "" {
		return s.oauthHTMLError(w, http.StatusForbidden, "Email not verified", "Your Google email address is not verified.")
	}
	if !s.oauthDomainAllowed(email) {
		return s.oauthHTMLError(w, http.StatusForbidden, "Access denied", "This email domain is not permitted to access this server.")
	}

	user, err := s.Store.UserByEmail(r.Context(), email)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			return s.oauthHTMLError(w, http.StatusForbidden, "Access denied",
				"The Google account "+html.EscapeString(email)+" is not a user on this dockdeploy instance. Ask an administrator to invite you first.")
		}
		return Internal(err)
	}
	if !user.IsActive() {
		return s.oauthHTMLError(w, http.StatusForbidden, "Account suspended", "This account has been suspended.")
	}

	plainCode := randToken()
	if err := s.Store.CreateAuthCode(r.Context(), s.Hasher.Hash(plainCode), store.AuthCode{
		ClientID:            authReq.ClientID,
		UserID:              user.ID,
		RedirectURI:         authReq.RedirectURI,
		CodeChallenge:       authReq.CodeChallenge,
		CodeChallengeMethod: authReq.CodeChallengeMethod,
		Scope:               authReq.Scope,
		ExpiresAt:           time.Now().Add(5 * time.Minute),
	}); err != nil {
		return Internal(err)
	}
	s.Log.Info("mcp oauth: google login authorized", "email", email, "user", user.ID, "client", authReq.ClientID)

	u, err := url.Parse(authReq.RedirectURI)
	if err != nil {
		return s.oauthHTMLError(w, http.StatusBadRequest, "Invalid redirect", "The client's redirect URI is malformed.")
	}
	qq := u.Query()
	qq.Set("code", plainCode)
	if authReq.ClientState != "" {
		qq.Set("state", authReq.ClientState)
	}
	u.RawQuery = qq.Encode()
	http.Redirect(w, r, u.String(), http.StatusFound)
	return nil
}

// --- token: redeem code, mint an API token ------------------------------

func (s *Server) handleOAuthToken(w http.ResponseWriter, r *http.Request) error {
	if err := r.ParseForm(); err != nil {
		return s.oauthJSONError(w, http.StatusBadRequest, "invalid_request", "could not parse request body")
	}
	if r.PostForm.Get("grant_type") != "authorization_code" {
		return s.oauthJSONError(w, http.StatusBadRequest, "unsupported_grant_type", "only authorization_code is supported")
	}
	code := r.PostForm.Get("code")
	redirectURI := r.PostForm.Get("redirect_uri")
	verifier := r.PostForm.Get("code_verifier")
	clientID := r.PostForm.Get("client_id")
	if code == "" || verifier == "" {
		return s.oauthJSONError(w, http.StatusBadRequest, "invalid_request", "code and code_verifier are required")
	}

	authCode, err := s.Store.ConsumeAuthCode(r.Context(), s.Hasher.Hash(code))
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			return s.oauthJSONError(w, http.StatusBadRequest, "invalid_grant", "authorization code is invalid, expired, or already used")
		}
		return Internal(err)
	}
	if redirectURI != authCode.RedirectURI {
		return s.oauthJSONError(w, http.StatusBadRequest, "invalid_grant", "redirect_uri does not match the authorization request")
	}
	if clientID != "" && clientID != authCode.ClientID {
		return s.oauthJSONError(w, http.StatusBadRequest, "invalid_client", "client_id does not match the authorization request")
	}
	if !auth.VerifyPKCE(authCode.CodeChallenge, authCode.CodeChallengeMethod, verifier) {
		return s.oauthJSONError(w, http.StatusBadRequest, "invalid_grant", "PKCE verification failed")
	}

	user, err := s.Store.UserByID(r.Context(), authCode.UserID)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			return s.oauthJSONError(w, http.StatusBadRequest, "invalid_grant", "the account no longer exists")
		}
		return Internal(err)
	}
	if !user.IsActive() {
		return s.oauthJSONError(w, http.StatusBadRequest, "invalid_grant", "the account is no longer active")
	}

	token, hash, prefix, err := s.Hasher.NewAPIToken()
	if err != nil {
		return Internal(err)
	}
	var expiresAt *time.Time
	var expiresIn int
	if s.Config.MCPTokenTTL > 0 {
		at := time.Now().Add(s.Config.MCPTokenTTL)
		expiresAt = &at
		expiresIn = int(s.Config.MCPTokenTTL.Seconds())
	}
	if _, err := s.Store.CreateAPIToken(r.Context(), user.ID, "MCP (OAuth) "+user.Email, hash, prefix, expiresAt); err != nil {
		return Internal(err)
	}
	s.Log.Info("mcp oauth: access token issued", "user", user.ID, "client", authCode.ClientID)

	resp := map[string]any{
		"access_token": token,
		"token_type":   "Bearer",
		"scope":        mcpScope,
	}
	if expiresIn > 0 {
		resp["expires_in"] = expiresIn
	}
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Pragma", "no-cache")
	return JSON(w, s.Log, http.StatusOK, resp)
}

// --- helpers ------------------------------------------------------------

func (s *Server) googleRedirectURI() string {
	return s.Config.AppURL + "/api/auth/google/callback"
}

func (s *Server) oauthDomainAllowed(email string) bool {
	if len(s.Config.OAuthAllowedDomains) == 0 {
		return true
	}
	at := strings.LastIndex(email, "@")
	if at < 0 {
		return false
	}
	return containsStr(s.Config.OAuthAllowedDomains, email[at+1:])
}

// oauthJSONError writes an RFC 6749 §5.2 error object and returns nil, since the
// response is already written.
func (s *Server) oauthJSONError(w http.ResponseWriter, status int, code, desc string) error {
	w.Header().Set("Cache-Control", "no-store")
	return JSON(w, s.Log, status, map[string]string{"error": code, "error_description": desc})
}

// redirectOAuthError bounces an error back to a validated client redirect URI.
func (s *Server) redirectOAuthError(w http.ResponseWriter, r *http.Request, redirectURI, state, code, desc string) error {
	u, err := url.Parse(redirectURI)
	if err != nil {
		return s.oauthHTMLError(w, http.StatusBadRequest, "Invalid redirect", "The redirect URI is malformed.")
	}
	q := u.Query()
	q.Set("error", code)
	if desc != "" {
		q.Set("error_description", desc)
	}
	if state != "" {
		q.Set("state", state)
	}
	u.RawQuery = q.Encode()
	http.Redirect(w, r, u.String(), http.StatusFound)
	return nil
}

// oauthHTMLError renders a minimal browser-facing error page for the authorize
// and callback steps, which are driven by a human's browser rather than code.
func (s *Server) oauthHTMLError(w http.ResponseWriter, status int, title, message string) error {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.WriteHeader(status)
	_, _ = fmt.Fprintf(w, `<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>%s</title>
<style>body{font-family:system-ui,sans-serif;background:#0b0f17;color:#e5e7eb;display:flex;min-height:100vh;align-items:center;justify-content:center;margin:0}
.card{max-width:28rem;padding:2rem;background:#111827;border:1px solid #1f2937;border-radius:12px}
h1{font-size:1.25rem;margin:0 0 .5rem}p{color:#9ca3af;line-height:1.5;margin:0}</style></head>
<body><div class="card"><h1>%s</h1><p>%s</p></div></body></html>`,
		html.EscapeString(title), html.EscapeString(title), html.EscapeString(message))
	return nil
}

func randToken() string {
	b := make([]byte, 32)
	_, _ = rand.Read(b)
	return base64.RawURLEncoding.EncodeToString(b)
}

func containsStr(xs []string, x string) bool {
	for _, v := range xs {
		if v == x {
			return true
		}
	}
	return false
}
