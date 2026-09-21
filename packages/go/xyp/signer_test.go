package xyp

import (
	"crypto"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/pem"
	"errors"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"
)

const (
	testToken     = "test-access-token"
	fixedUnixTime = 1_700_000_000
)

// testKey is a throwaway key generated for the test run. Real keys never belong
// in a repository, not even in tests.
func testKey(t *testing.T) *rsa.PrivateKey {
	t.Helper()
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatalf("generate key: %v", err)
	}
	return key
}

func pemOf(t *testing.T, blockType string, der []byte) []byte {
	t.Helper()
	return pem.EncodeToMemory(&pem.Block{Type: blockType, Bytes: der})
}

func pkcs8PEM(t *testing.T, key crypto.PrivateKey) []byte {
	t.Helper()
	der, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		t.Fatalf("marshal key: %v", err)
	}
	return pemOf(t, pemPrivateKey, der)
}

func signingClient(t *testing.T, key crypto.Signer) *Client {
	t.Helper()
	client, err := NewClient(Options{AccessToken: testToken, PrivateKey: key})
	if err != nil {
		t.Fatalf("NewClient: %v", err)
	}
	t.Cleanup(client.Close)
	client.now = func() time.Time { return time.Unix(fixedUnixTime, 0) }
	return client
}

func TestSignHeadersSignsTokenDotTimestampWithRSASHA256(t *testing.T) {
	t.Parallel()
	key := testKey(t)
	header := http.Header{}
	if err := signingClient(t, key).signHeaders(header); err != nil {
		t.Fatalf("signHeaders: %v", err)
	}

	// The header names must keep XYP's mixed case, which Header.Get would hide.
	if got := rawHeader(header, accessTokenHeader); len(got) != 1 || got[0] != testToken {
		t.Errorf("accessToken header = %v", got)
	}
	want := strconv.Itoa(fixedUnixTime)
	if got := rawHeader(header, timeStampHeader); len(got) != 1 || got[0] != want {
		t.Errorf("timeStamp header = %v", got)
	}
	sent := rawHeader(header, signatureHeader)[0]
	signature, err := base64.StdEncoding.DecodeString(sent)
	if err != nil {
		t.Fatalf("the signature is not base64: %v", err)
	}
	digest := sha256.Sum256([]byte(testToken + "." + want))
	if verifyErr := rsa.VerifyPKCS1v15(
		&key.PublicKey, crypto.SHA256, digest[:], signature,
	); verifyErr != nil {
		t.Fatalf("the signature does not verify: %v", verifyErr)
	}
	// PKCS#1 v1.5 is deterministic: the same bytes as any other correct implementation.
	reference, err := rsa.SignPKCS1v15(nil, key, crypto.SHA256, digest[:])
	if err != nil {
		t.Fatalf("reference signature: %v", err)
	}
	if base64.StdEncoding.EncodeToString(reference) != sent {
		t.Error("the signature differs from the reference PKCS#1 v1.5 signature")
	}
}

// rawHeader reads the map key verbatim. These three names are deliberately not
// canonical, which is the whole point: XYP rejects "Accesstoken".
func rawHeader(header http.Header, name string) []string {
	return header[name] //nolint:staticcheck // SA1008: the mixed case is required on the wire
}

func TestSignHeadersUsesWholeSecondsOfNowByDefault(t *testing.T) {
	t.Parallel()
	client, err := NewClient(Options{AccessToken: testToken, PrivateKey: testKey(t)})
	if err != nil {
		t.Fatalf("NewClient: %v", err)
	}
	t.Cleanup(client.Close)

	header := http.Header{}
	if signErr := client.signHeaders(header); signErr != nil {
		t.Fatalf("signHeaders: %v", signErr)
	}
	stamp, err := strconv.ParseInt(rawHeader(header, timeStampHeader)[0], 10, 64)
	if err != nil {
		t.Fatalf("timeStamp is not an integer: %v", err)
	}
	if drift := time.Since(time.Unix(stamp, 0)); drift > 5*time.Second || drift < -5*time.Second {
		t.Errorf("timeStamp is %v away from now", drift)
	}
}

func TestSignHeadersIsFreshOnEveryRequest(t *testing.T) {
	t.Parallel()
	client := signingClient(t, testKey(t))
	first, second := http.Header{}, http.Header{}
	ticks := 0
	client.now = func() time.Time {
		ticks++
		return time.Unix(fixedUnixTime+int64(ticks), 0)
	}

	if err := client.signHeaders(first); err != nil {
		t.Fatalf("signHeaders: %v", err)
	}
	if err := client.signHeaders(second); err != nil {
		t.Fatalf("signHeaders: %v", err)
	}
	if rawHeader(first, signatureHeader)[0] == rawHeader(second, signatureHeader)[0] {
		t.Error("XYP rejects stale timestamps, so a signature must never be reused")
	}
}

func TestParsePrivateKeyAcceptsPEMAndDERInBothRSAEncodings(t *testing.T) {
	t.Parallel()
	key := testKey(t)
	pkcs1DER := x509.MarshalPKCS1PrivateKey(key)
	pkcs8DER, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		t.Fatalf("marshal pkcs8: %v", err)
	}

	for name, data := range map[string][]byte{
		"PKCS#8 PEM": pemOf(t, pemPrivateKey, pkcs8DER),
		"PKCS#1 PEM": pemOf(t, pemRSAPrivateKey, pkcs1DER),
		"PKCS#8 DER": pkcs8DER,
		"PKCS#1 DER": pkcs1DER,
	} {
		t.Run(name, func(t *testing.T) {
			t.Parallel()
			parsed, err := ParsePrivateKey(data)
			if err != nil {
				t.Fatalf("ParsePrivateKey: %v", err)
			}
			if !key.PublicKey.Equal(parsed.Public()) {
				t.Error("a different key came back")
			}
		})
	}
}

func TestLoadPrivateKeyReadsAFile(t *testing.T) {
	t.Parallel()
	key := testKey(t)
	path := filepath.Join(t.TempDir(), "private.key")
	if err := os.WriteFile(path, pkcs8PEM(t, key), 0o600); err != nil {
		t.Fatalf("write key: %v", err)
	}

	loaded, err := LoadPrivateKey(path)
	if err != nil {
		t.Fatalf("LoadPrivateKey: %v", err)
	}
	if !key.PublicKey.Equal(loaded.Public()) {
		t.Error("a different key came back")
	}
}

func TestLoadPrivateKeyNamesOnlyTheFailureClass(t *testing.T) {
	t.Parallel()
	_, err := LoadPrivateKey(filepath.Join(t.TempDir(), "absent.key"))

	var configErr *ConfigError
	if !errors.As(err, &configErr) {
		t.Fatalf("err = %v, want a *ConfigError", err)
	}
	if !strings.Contains(configErr.Message, "no such file") {
		t.Errorf("message = %q", configErr.Message)
	}
}

func TestParsePrivateKeyRejectsBadInputWithoutLeakingKeyMaterial(t *testing.T) {
	t.Parallel()
	ecKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatalf("generate EC key: %v", err)
	}
	legacy := pem.EncodeToMemory(&pem.Block{
		Type:    pemRSAPrivateKey,
		Headers: map[string]string{procTypeHeader: "4,ENCRYPTED", "DEK-Info": "AES-128-CBC,00"},
		Bytes:   []byte("secret-material"),
	})

	for _, test := range []struct {
		name string
		data []byte
		want string
	}{
		{"garbage PEM", []byte(
			"-----BEGIN PRIVATE KEY-----\nsecret-material\n-----END PRIVATE KEY-----"),
			"not a valid PEM or DER private key"},
		{"garbage DER", []byte("secret-material"), "not a valid PEM or DER private key"},
		{"an EC key", pkcs8PEM(t, ecKey), "must be an RSA key"},
		{"a PKCS#8 encrypted key",
			pemOf(t, pemEncryptedPrivateKey, []byte("secret-material")), "openssl pkey"},
		{"a legacy encrypted key", legacy, "openssl pkey"},
	} {
		t.Run(test.name, func(t *testing.T) {
			t.Parallel()
			_, err := ParsePrivateKey(test.data)
			var configErr *ConfigError
			if !errors.As(err, &configErr) {
				t.Fatalf("err = %v, want a *ConfigError", err)
			}
			if !strings.Contains(configErr.Message, test.want) {
				t.Errorf("message = %q, want it to contain %q", configErr.Message, test.want)
			}
			if strings.Contains(configErr.Message, "secret-material") {
				t.Errorf("key material reached the message: %q", configErr.Message)
			}
		})
	}
}

func TestNewClientRejectsANonRSASigner(t *testing.T) {
	t.Parallel()
	ecKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatalf("generate EC key: %v", err)
	}

	_, err = NewClient(Options{AccessToken: testToken, PrivateKey: ecKey})
	var configErr *ConfigError
	if !errors.As(err, &configErr) || !strings.Contains(configErr.Message, "RSA") {
		t.Errorf("err = %v, want a *ConfigError about RSA", err)
	}
}
