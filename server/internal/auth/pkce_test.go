package auth

import (
	"crypto/sha256"
	"encoding/base64"
	"testing"
)

func challengeFor(verifier string) string {
	sum := sha256.Sum256([]byte(verifier))
	return base64.RawURLEncoding.EncodeToString(sum[:])
}

func TestVerifyPKCE(t *testing.T) {
	const verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
	challenge := challengeFor(verifier)

	tests := []struct {
		name      string
		challenge string
		method    string
		verifier  string
		want      bool
	}{
		{"valid S256", challenge, "S256", verifier, true},
		{"wrong verifier", challenge, "S256", "not-the-verifier", false},
		{"plain method rejected", verifier, "plain", verifier, false},
		{"empty method rejected", challenge, "", verifier, false},
		{"empty challenge rejected", "", "S256", verifier, false},
		{"empty verifier rejected", challenge, "S256", "", false},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			if got := VerifyPKCE(tc.challenge, tc.method, tc.verifier); got != tc.want {
				t.Errorf("VerifyPKCE(%q, %q, %q) = %v, want %v", tc.challenge, tc.method, tc.verifier, got, tc.want)
			}
		})
	}
}
