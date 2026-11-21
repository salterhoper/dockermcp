package auth

import (
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
)

// PKCEMethodS256 is the only code challenge method the OAuth endpoints accept.
// The plain method is deliberately unsupported: it offers no protection if the
// authorization code is intercepted.
const PKCEMethodS256 = "S256"

// VerifyPKCE reports whether codeVerifier satisfies codeChallenge under the
// S256 method (RFC 7636): BASE64URL(SHA256(codeVerifier)) == codeChallenge.
// Any other method, or an empty input, fails closed.
func VerifyPKCE(codeChallenge, method, codeVerifier string) bool {
	if method != PKCEMethodS256 || codeChallenge == "" || codeVerifier == "" {
		return false
	}
	sum := sha256.Sum256([]byte(codeVerifier))
	computed := base64.RawURLEncoding.EncodeToString(sum[:])
	return subtle.ConstantTimeCompare([]byte(computed), []byte(codeChallenge)) == 1
}
