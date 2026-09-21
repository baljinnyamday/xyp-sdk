package xyp

import (
	"crypto/sha256"
	"crypto/x509"
	"encoding/hex"
	"encoding/pem"
	"errors"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// The fingerprints published in docs/tls.md. If one of these ever changes, the
// bundled file changed with it, and that has to be a deliberate act.
const (
	rootFingerprint = "CBE7F3FE1F048037C215DA321E58CAA4F363DE9E54BBC442A3BFD62FAD834482"
	issuingCAPrint  = "9423640DD74561D1AF1EA8A093860BDA7DF5B5620BB617921395DC0D1A1F980D"
)

func TestBundledCAsHoldExactlyTheTwoNationalCertificates(t *testing.T) {
	t.Parallel()
	root := parseCertificate(t, nationalRootCA)
	issuing := parseCertificate(t, nationalIssuingCA)

	both := x509.NewCertPool()
	both.AddCert(root)
	both.AddCert(issuing)
	if !BundledCAs().Equal(both) {
		t.Fatal("BundledCAs() is not exactly MNRCA-2021 plus MNICA-2022")
	}
	rootOnly := x509.NewCertPool()
	rootOnly.AddCert(root)
	if BundledCAs().Equal(rootOnly) {
		t.Fatal("BundledCAs() must not be satisfied by the root alone")
	}
}

func TestBundledCAsAreAFreshPool(t *testing.T) {
	t.Parallel()
	// Callers add their own proxy CA to the pool; that must not reach anyone else.
	first := BundledCAs()
	first.AppendCertsFromPEM([]byte(nationalRootCA))
	if !BundledCAs().Equal(poolOf(t, nationalRootCA, nationalIssuingCA)) {
		t.Fatal("BundledCAs() returns shared state")
	}
}

func TestBundledFingerprintsMatchTheDocumentation(t *testing.T) {
	t.Parallel()
	for _, test := range []struct{ name, pemText, want string }{
		{"MNRCA-2021", nationalRootCA, rootFingerprint},
		{"MNICA-2022", nationalIssuingCA, issuingCAPrint},
	} {
		t.Run(test.name, func(t *testing.T) {
			t.Parallel()
			digest := sha256.Sum256(parseCertificate(t, test.pemText).Raw)
			got := strings.ToUpper(hex.EncodeToString(digest[:]))
			if got != test.want {
				t.Errorf("fingerprint = %s, want %s", got, test.want)
			}
		})
	}
}

func TestBundledCertificatesVerifyAsAChain(t *testing.T) {
	t.Parallel()
	issuing := parseCertificate(t, nationalIssuingCA)
	options := x509.VerifyOptions{
		Roots:       poolOf(t, nationalRootCA),
		CurrentTime: issuing.NotBefore.Add(24 * time.Hour),
		KeyUsages:   []x509.ExtKeyUsage{x509.ExtKeyUsageAny},
	}
	if _, err := issuing.Verify(options); err != nil {
		t.Fatalf("MNICA-2022 does not chain to MNRCA-2021: %v", err)
	}
}

func TestBundledCertificatesAreTheSameFilesAsThePythonPackage(t *testing.T) {
	t.Parallel()
	for name, embedded := range map[string]string{
		"MNRCA-2021.pem": nationalRootCA,
		"MNICA-2022.pem": nationalIssuingCA,
	} {
		path := filepath.Join("..", "..", "python", "src", "xyp", "certs", name)
		published, err := os.ReadFile(path) //nolint:gosec // a fixed path inside the repository
		if errors.Is(err, fs.ErrNotExist) {
			// A downloaded module zip holds only this module's own files.
			t.Skipf("%s is not part of this module", path)
		}
		if err != nil {
			t.Fatalf("read %s: %v", path, err)
		}
		if string(published) != embedded {
			t.Errorf("%s differs from the Python package's copy", name)
		}
	}
}

func TestNewTransportPinsTheTrustAndTheMinimumVersion(t *testing.T) {
	t.Parallel()
	roots := BundledCAs()
	transport := newTransport(roots, true)

	if transport.TLSClientConfig.RootCAs != roots {
		t.Error("RootCAs was not passed through")
	}
	if !transport.TLSClientConfig.InsecureSkipVerify {
		t.Error("InsecureSkipVerify was not passed through")
	}
	if transport.TLSClientConfig.MinVersion != 0x0303 { // tls.VersionTLS12
		t.Errorf("MinVersion = %#x, want TLS 1.2", transport.TLSClientConfig.MinVersion)
	}
}

func parseCertificate(t *testing.T, pemText string) *x509.Certificate {
	t.Helper()
	block, rest := pem.Decode([]byte(pemText))
	if block == nil {
		t.Fatal("not a PEM certificate")
	}
	if strings.TrimSpace(string(rest)) != "" {
		t.Fatal("the bundled file holds more than one certificate")
	}
	certificate, err := x509.ParseCertificate(block.Bytes)
	if err != nil {
		t.Fatalf("parse certificate: %v", err)
	}
	return certificate
}

func poolOf(t *testing.T, pemTexts ...string) *x509.CertPool {
	t.Helper()
	pool := x509.NewCertPool()
	for _, pemText := range pemTexts {
		pool.AddCert(parseCertificate(t, pemText))
	}
	return pool
}
