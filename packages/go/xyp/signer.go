package xyp

import (
	"crypto"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/pem"
	"errors"
	"io/fs"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"
)

// XYP requires these three headers on every call. Their names are written in
// mixed case on the wire, and Go's http package would canonicalise them to
// "Accesstoken" and friends, so they are assigned to the header map directly.
const (
	accessTokenHeader = "accessToken"
	timeStampHeader   = "timeStamp"
	signatureHeader   = "signature"
)

const (
	pemPrivateKey          = "PRIVATE KEY"
	pemRSAPrivateKey       = "RSA PRIVATE KEY"
	pemEncryptedPrivateKey = "ENCRYPTED PRIVATE KEY"
	// procTypeHeader marks a legacy OpenSSL key encrypted with a passphrase.
	procTypeHeader = "Proc-Type"
	pemMarker      = "-----BEGIN"
)

// LoadPrivateKey reads an RSA private key from a PEM or DER file.
func LoadPrivateKey(path string) (crypto.Signer, error) {
	data, err := os.ReadFile(path) //nolint:gosec // the path is the caller's own configuration
	if err != nil {
		// Only the failure class, never the path's contents or the key itself.
		return nil, configErrorf("cannot read the private key file (%s)", fileProblem(err))
	}
	return ParsePrivateKey(data)
}

// ParsePrivateKey reads an RSA private key from PEM text or DER bytes.
func ParsePrivateKey(data []byte) (crypto.Signer, error) {
	if strings.Contains(string(data), pemMarker) {
		return parsePEM(data)
	}
	// DER names no format, so both common RSA encodings are tried.
	return firstRSAKey(parsePKCS8(data), parsePKCS1(data))
}

func parsePEM(data []byte) (crypto.Signer, error) {
	block, _ := pem.Decode(data)
	if block == nil {
		return nil, notAPrivateKey()
	}
	if block.Type == pemEncryptedPrivateKey || block.Headers[procTypeHeader] != "" {
		return nil, configErrorf(
			"the private key is encrypted, which this SDK cannot decrypt. Decrypt it once with " +
				"`openssl pkey -in encrypted.key -out private.key`, or pass a crypto.Signer of " +
				"your own (an HSM or KMS key works the same way) as Options.PrivateKey",
		)
	}
	switch block.Type {
	case pemPrivateKey:
		return firstRSAKey(parsePKCS8(block.Bytes))
	case pemRSAPrivateKey:
		return firstRSAKey(parsePKCS1(block.Bytes))
	default:
		return firstRSAKey(parsePKCS8(block.Bytes), parsePKCS1(block.Bytes))
	}
}

func parsePKCS8(der []byte) crypto.PrivateKey {
	key, err := x509.ParsePKCS8PrivateKey(der)
	if err != nil {
		return nil
	}
	return key
}

func parsePKCS1(der []byte) crypto.PrivateKey {
	key, err := x509.ParsePKCS1PrivateKey(der)
	if err != nil {
		return nil
	}
	return key
}

// firstRSAKey takes the first attempt that parsed. The underlying parse errors
// are deliberately dropped: they can echo key material into a log.
func firstRSAKey(attempts ...crypto.PrivateKey) (crypto.Signer, error) {
	for _, key := range attempts {
		if key == nil {
			continue
		}
		rsaKey, isRSA := key.(*rsa.PrivateKey)
		if !isRSA {
			return nil, notAnRSAKey()
		}
		return rsaKey, nil
	}
	return nil, notAPrivateKey()
}

func notAPrivateKey() *ConfigError {
	return configErrorf("PrivateKey is not a valid PEM or DER private key")
}

func notAnRSAKey() *ConfigError {
	return configErrorf("PrivateKey must be an RSA key")
}

func fileProblem(err error) string {
	switch {
	case errors.Is(err, fs.ErrNotExist):
		return "no such file"
	case errors.Is(err, fs.ErrPermission):
		return "permission denied"
	default:
		return "unreadable"
	}
}

// signHeaders builds the three credentials headers for one request. XYP rejects
// stale timestamps, so they are never reused between requests.
func (c *Client) signHeaders(header http.Header) error {
	timestamp := strconv.FormatInt(c.now().Unix(), 10)
	digest := sha256.Sum256([]byte(c.accessToken + "." + timestamp))
	signature, err := c.key.Sign(rand.Reader, digest[:], crypto.SHA256)
	if err != nil {
		return configErrorf("the private key could not sign the request")
	}
	// Assigned directly, not through Header.Set, so Go keeps the mixed case XYP expects.
	header[accessTokenHeader] = []string{c.accessToken}
	header[timeStampHeader] = []string{timestamp}
	header[signatureHeader] = []string{base64.StdEncoding.EncodeToString(signature)}
	return nil
}

// defaultClock is injectable through Client.now so tests can pin the timestamp.
func defaultClock() time.Time { return time.Now() }
