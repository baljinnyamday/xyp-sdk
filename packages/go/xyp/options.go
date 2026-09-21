package xyp

import (
	"crypto"
	"crypto/rsa"
	"crypto/x509"
	"log/slog"
	"os"
	"strings"
	"time"
)

// Environment variables the client falls back to when Options leaves a
// credential empty.
const (
	AccessTokenEnv = "XYP_ACCESS_TOKEN"
	PrivateKeyEnv  = "XYP_PRIVATE_KEY"
)

const (
	// DefaultBaseURL is where XYP answers from inside the National Data Center VPN.
	DefaultBaseURL = "https://xyp.gov.mn"
	// DefaultTimeout is how long one HTTP request to XYP may take.
	DefaultTimeout = 30 * time.Second
)

// Options configures a Client. The zero value works as long as XYP_ACCESS_TOKEN
// and XYP_PRIVATE_KEY are set.
type Options struct {
	// AccessToken is the token issued by the National Data Center. Falls back to
	// the XYP_ACCESS_TOKEN environment variable.
	AccessToken string
	// PrivateKey signs every request; it must be RSA. When nil, the key is read
	// from the file named by XYP_PRIVATE_KEY. It is a crypto.Signer so a key that
	// never leaves an HSM or a KMS works too.
	PrivateKey crypto.Signer
	// BaseURL defaults to DefaultBaseURL. Change it only when you reach XYP
	// through your own proxy; it must start with https:// or http://.
	BaseURL string
	// Timeout defaults to DefaultTimeout and covers one whole HTTP request,
	// including reading the response body.
	Timeout time.Duration
	// RootCAs defaults to BundledCAs(): the Mongolian national CAs and nothing else.
	RootCAs *x509.CertPool
	// InsecureSkipVerify turns certificate verification off. Read docs/tls.md
	// first: on XYP the certificate is the only thing proving you reached XYP.
	InsecureSkipVerify bool
	// Logger receives the one warning this SDK writes, when a response does not
	// fit the SDK's model. Defaults to slog.Default().
	Logger *slog.Logger
}

// settings is Options after defaults and validation, so the Client never has to
// second-guess its own configuration.
type settings struct {
	accessToken string
	key         crypto.Signer
	baseURL     string
	timeout     time.Duration
	roots       *x509.CertPool
	insecure    bool
	logger      *slog.Logger
}

func (o Options) resolve() (settings, error) {
	accessToken := firstNonEmpty(o.AccessToken, os.Getenv(AccessTokenEnv))
	if accessToken == "" {
		return settings{}, configErrorf(
			"set Options.AccessToken or the %s environment variable", AccessTokenEnv,
		)
	}
	key, err := resolveKey(o.PrivateKey)
	if err != nil {
		return settings{}, err
	}
	baseURL := firstNonEmpty(o.BaseURL, DefaultBaseURL)
	scheme := strings.ToLower(baseURL)
	if !strings.HasPrefix(scheme, "https://") && !strings.HasPrefix(scheme, "http://") {
		return settings{}, configErrorf(
			"BaseURL must start with https:// (or http://), got %q", baseURL,
		)
	}
	timeout := o.Timeout
	if timeout <= 0 {
		timeout = DefaultTimeout
	}
	roots := o.RootCAs
	if roots == nil {
		roots = BundledCAs()
	}
	logger := o.Logger
	if logger == nil {
		logger = slog.Default()
	}
	return settings{
		accessToken: accessToken,
		key:         key,
		// A trailing slash would otherwise produce "https://xyp.gov.mn//citizen-1.5.0/ws".
		baseURL:  strings.TrimRight(baseURL, "/"),
		timeout:  timeout,
		roots:    roots,
		insecure: o.InsecureSkipVerify,
		logger:   logger,
	}, nil
}

func resolveKey(key crypto.Signer) (crypto.Signer, error) {
	if key == nil {
		path := os.Getenv(PrivateKeyEnv)
		if path == "" {
			return nil, configErrorf(
				"set Options.PrivateKey or point %s at the key file", PrivateKeyEnv,
			)
		}
		loaded, err := LoadPrivateKey(path)
		if err != nil {
			return nil, err
		}
		return loaded, nil
	}
	// A caller-supplied signer can be anything; XYP only accepts RSA signatures.
	if _, isRSA := key.Public().(*rsa.PublicKey); !isRSA {
		return nil, notAnRSAKey()
	}
	return key, nil
}

func firstNonEmpty(values ...string) string {
	for _, value := range values {
		if value != "" {
			return value
		}
	}
	return ""
}
