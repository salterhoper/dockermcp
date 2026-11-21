// Package googleoauth is a minimal Google OAuth 2.0 / OpenID Connect client
// used to authenticate the human during the MCP OAuth flow.
//
// It deliberately avoids any third-party dependency: the flow needs only two
// server-to-server round trips -- exchanging the authorization code for tokens,
// then reading the userinfo endpoint for the verified email -- both of which are
// plain HTTPS calls.
package googleoauth

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

const (
	authEndpoint     = "https://accounts.google.com/o/oauth2/v2/auth"
	tokenEndpoint    = "https://oauth2.googleapis.com/token"
	userinfoEndpoint = "https://openidconnect.googleapis.com/v1/userinfo"

	// maxBody caps how much we read from Google's responses, which are small.
	maxBody = 1 << 20
)

// Client talks to Google's OAuth endpoints with a single registered web client.
type Client struct {
	clientID     string
	clientSecret string
	http         *http.Client
}

// New builds a client. The caller is responsible for only constructing one when
// both credentials are present (see config.OAuthEnabled).
func New(clientID, clientSecret string) *Client {
	return &Client{
		clientID:     clientID,
		clientSecret: clientSecret,
		http:         &http.Client{Timeout: 15 * time.Second},
	}
}

// AuthCodeURL builds the Google consent URL the user's browser is redirected to.
// state round-trips back to our callback; redirectURI must be registered in the
// Google Cloud console.
func (c *Client) AuthCodeURL(state, redirectURI string) string {
	q := url.Values{}
	q.Set("client_id", c.clientID)
	q.Set("redirect_uri", redirectURI)
	q.Set("response_type", "code")
	q.Set("scope", "openid email profile")
	q.Set("state", state)
	q.Set("access_type", "online")
	q.Set("prompt", "select_account")
	return authEndpoint + "?" + q.Encode()
}

// Identity is the subset of the Google profile the MCP flow relies on.
type Identity struct {
	Email         string
	EmailVerified bool
}

// Exchange trades an authorization code for the user's verified email.
func (c *Client) Exchange(ctx context.Context, code, redirectURI string) (*Identity, error) {
	form := url.Values{}
	form.Set("code", code)
	form.Set("client_id", c.clientID)
	form.Set("client_secret", c.clientSecret)
	form.Set("redirect_uri", redirectURI)
	form.Set("grant_type", "authorization_code")

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, tokenEndpoint, strings.NewReader(form.Encode()))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.Header.Set("Accept", "application/json")

	body, err := c.do(req, "token")
	if err != nil {
		return nil, err
	}
	var tok struct {
		AccessToken string `json:"access_token"`
		IDToken     string `json:"id_token"`
	}
	if err := json.Unmarshal(body, &tok); err != nil {
		return nil, fmt.Errorf("googleoauth: decode token response: %w", err)
	}
	if tok.AccessToken == "" {
		return nil, fmt.Errorf("googleoauth: token response had no access_token")
	}
	return c.userinfo(ctx, tok.AccessToken)
}

func (c *Client) userinfo(ctx context.Context, accessToken string) (*Identity, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, userinfoEndpoint, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Authorization", "Bearer "+accessToken)
	req.Header.Set("Accept", "application/json")

	body, err := c.do(req, "userinfo")
	if err != nil {
		return nil, err
	}
	// Google returns email_verified either as a JSON bool or, on some paths, as
	// the string "true"; accept both.
	var info struct {
		Email         string          `json:"email"`
		EmailVerified json.RawMessage `json:"email_verified"`
	}
	if err := json.Unmarshal(body, &info); err != nil {
		return nil, fmt.Errorf("googleoauth: decode userinfo: %w", err)
	}
	verified := string(info.EmailVerified) == "true" || string(info.EmailVerified) == `"true"`
	return &Identity{Email: info.Email, EmailVerified: verified}, nil
}

func (c *Client) do(req *http.Request, what string) ([]byte, error) {
	resp, err := c.http.Do(req)
	if err != nil {
		return nil, fmt.Errorf("googleoauth: %s request: %w", what, err)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, maxBody))
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("googleoauth: %s endpoint returned %d: %s", what, resp.StatusCode, string(body))
	}
	return body, nil
}
