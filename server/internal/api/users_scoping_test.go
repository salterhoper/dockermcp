package api

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/sirtheprogrammer/docker-deployments/server/internal/auth"
	"github.com/sirtheprogrammer/docker-deployments/server/internal/secrets"
	"github.com/sirtheprogrammer/docker-deployments/server/internal/sshx"
	"github.com/sirtheprogrammer/docker-deployments/server/internal/store"
)

func seedSessionCookie(t *testing.T, s *Server, st *store.Store, userID string) *http.Cookie {
	t.Helper()
	token, hash, err := s.Hasher.NewToken()
	if err != nil {
		t.Fatalf("NewToken: %v", err)
	}
	if _, err := st.CreateSession(context.Background(), userID, hash, time.Now().Add(time.Hour), "127.0.0.1", "test"); err != nil {
		t.Fatalf("CreateSession: %v", err)
	}
	return &http.Cookie{Name: s.cookieName(), Value: token}
}

func TestUserCreationAndServerScoping(t *testing.T) {
	s, handler, st := newOAuthTestServer(t)
	sealer, err := secrets.NewSealer(make([]byte, 32))
	if err != nil {
		t.Fatal(err)
	}
	s.Sealer = sealer
	ctx := context.Background()

	adminHash, _ := auth.HashPassword("admin-secret-password-123")
	admin, err := st.CreateFirstAdmin(ctx, "admin@example.com", "Admin", adminHash)
	if err != nil {
		t.Fatalf("CreateFirstAdmin: %v", err)
	}

	srvA, err := st.CreateServer(ctx, s.Sealer, store.NewServer{
		Name: "server-alpha", Host: "10.0.0.1", Port: 22, Username: "root",
		AuthMethod: sshx.AuthPassword, Password: "pw", HostKeyFingerprint: "SHA256:aaa", CreatedBy: admin.ID,
	})
	if err != nil {
		t.Fatalf("CreateServer A: %v", err)
	}
	srvB, err := st.CreateServer(ctx, s.Sealer, store.NewServer{
		Name: "server-beta", Host: "10.0.0.2", Port: 22, Username: "root",
		AuthMethod: sshx.AuthPassword, Password: "pw", HostKeyFingerprint: "SHA256:bbb", CreatedBy: admin.ID,
	})
	if err != nil {
		t.Fatalf("CreateServer B: %v", err)
	}

	adminCookie := seedSessionCookie(t, s, st, admin.ID)

	// 1. Admin creates a member scoped ONLY to server-alpha (with operate permission)
	body, _ := json.Marshal(map[string]any{
		"email":    "alice@example.com",
		"name":     "Alice",
		"password": "alice-password-1234",
		"role":     "member",
		"server_grants": []map[string]string{
			{"server_id": srvA.ID, "permission": "operate"},
		},
	})
	req := httptest.NewRequest(http.MethodPost, "/api/users", bytes.NewReader(body))
	req.AddCookie(adminCookie)
	req.Header.Set("Origin", testAppURL)
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)
	if rec.Code != http.StatusCreated {
		t.Fatalf("POST /api/users status = %d, body = %s", rec.Code, rec.Body.String())
	}
	var alice store.User
	if err := json.Unmarshal(rec.Body.Bytes(), &alice); err != nil {
		t.Fatalf("decode user: %v", err)
	}

	aliceCookie := seedSessionCookie(t, s, st, alice.ID)

	// Alice sees ONLY server-alpha; server-beta is hidden.
	listReq := httptest.NewRequest(http.MethodGet, "/api/servers", nil)
	listReq.AddCookie(aliceCookie)
	listRec := httptest.NewRecorder()
	handler.ServeHTTP(listRec, listReq)
	if listRec.Code != http.StatusOK {
		t.Fatalf("GET /api/servers status = %d", listRec.Code)
	}
	var listResp struct {
		Servers []store.Server `json:"servers"`
	}
	_ = json.Unmarshal(listRec.Body.Bytes(), &listResp)
	if len(listResp.Servers) != 1 || listResp.Servers[0].ID != srvA.ID {
		t.Fatalf("expected only server A, got %+v", listResp.Servers)
	}

	// Direct GET to server-beta returns 404 (hidden).
	getBReq := httptest.NewRequest(http.MethodGet, "/api/servers/"+srvB.ID, nil)
	getBReq.AddCookie(aliceCookie)
	getBRec := httptest.NewRecorder()
	handler.ServeHTTP(getBRec, getBReq)
	if getBRec.Code != http.StatusNotFound {
		t.Fatalf("GET hidden server B = %d, want 404", getBRec.Code)
	}

	// 2. Admin updates Alice's scope to read-only on server-beta (revoking alpha).
	putBody, _ := json.Marshal(map[string]any{
		"grants": []map[string]string{
			{"server_id": srvB.ID, "permission": "read"},
		},
	})
	putReq := httptest.NewRequest(http.MethodPut, "/api/users/"+alice.ID+"/servers", bytes.NewReader(putBody))
	putReq.AddCookie(adminCookie)
	putReq.Header.Set("Origin", testAppURL)
	putRec := httptest.NewRecorder()
	handler.ServeHTTP(putRec, putReq)
	if putRec.Code != http.StatusOK {
		t.Fatalf("PUT /api/users/{id}/servers status = %d, body = %s", putRec.Code, putRec.Body.String())
	}

	// Alice can read server-beta, cannot mutate it (403), and cannot see server-alpha (404).
	getB2 := httptest.NewRequest(http.MethodGet, "/api/servers/"+srvB.ID, nil)
	getB2.AddCookie(aliceCookie)
	recB2 := httptest.NewRecorder()
	handler.ServeHTTP(recB2, getB2)
	if recB2.Code != http.StatusOK {
		t.Fatalf("GET granted server B = %d, want 200", recB2.Code)
	}

	patchB := httptest.NewRequest(http.MethodPatch, "/api/servers/"+srvB.ID, bytes.NewBufferString(`{"name":"hacked"}`))
	patchB.AddCookie(aliceCookie)
	patchB.Header.Set("Origin", testAppURL)
	recPatchB := httptest.NewRecorder()
	handler.ServeHTTP(recPatchB, patchB)
	if recPatchB.Code != http.StatusForbidden {
		t.Fatalf("PATCH read-only server B = %d, want 403", recPatchB.Code)
	}

	getA2 := httptest.NewRequest(http.MethodGet, "/api/servers/"+srvA.ID, nil)
	getA2.AddCookie(aliceCookie)
	recA2 := httptest.NewRecorder()
	handler.ServeHTTP(recA2, getA2)
	if recA2.Code != http.StatusNotFound {
		t.Fatalf("GET revoked server A = %d, want 404", recA2.Code)
	}

	// 3. Invitation with pre-scoped server grant applies on accept.
	invBody, _ := json.Marshal(map[string]any{
		"email": "bob@example.com",
		"name":  "Bob",
		"role":  "member",
		"server_grants": []map[string]string{
			{"server_id": srvA.ID, "permission": "operate"},
		},
	})
	invReq := httptest.NewRequest(http.MethodPost, "/api/invitations", bytes.NewReader(invBody))
	invReq.AddCookie(adminCookie)
	invReq.Header.Set("Origin", testAppURL)
	invRec := httptest.NewRecorder()
	handler.ServeHTTP(invRec, invReq)
	if invRec.Code != http.StatusCreated {
		t.Fatalf("POST /api/invitations status = %d, body = %s", invRec.Code, invRec.Body.String())
	}
	var createdInv struct {
		URL string `json:"url"`
	}
	_ = json.Unmarshal(invRec.Body.Bytes(), &createdInv)
	idx := bytes.LastIndex([]byte(createdInv.URL), []byte("/invite/"))
	token := createdInv.URL[idx+len("/invite/"):]

	accBody, _ := json.Marshal(map[string]string{
		"name":     "Bob",
		"password": "bob-password-1234",
	})
	accReq := httptest.NewRequest(http.MethodPost, "/api/auth/invitations/"+token+"/accept", bytes.NewReader(accBody))
	accRec := httptest.NewRecorder()
	handler.ServeHTTP(accRec, accReq)
	if accRec.Code != http.StatusCreated {
		t.Fatalf("accept invitation status = %d, body = %s", accRec.Code, accRec.Body.String())
	}
	var bob store.User
	_ = json.Unmarshal(accRec.Body.Bytes(), &bob)

	bobServers, err := st.ListServers(ctx, bob.ID, false)
	if err != nil {
		t.Fatalf("ListServers for Bob: %v", err)
	}
	if len(bobServers) != 1 || bobServers[0].ID != srvA.ID {
		t.Fatalf("Bob should only see server A, got %+v", bobServers)
	}
}
