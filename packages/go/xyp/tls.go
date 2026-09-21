package xyp

import (
	"crypto/tls"
	"crypto/x509"
	_ "embed"
	"net/http"
)

// XYP's certificate is issued by the Mongolian national PKI, which no operating
// system or Go trust store ships. Instead of turning verification off (what most
// integrations do, including the official sample), the SDK trusts exactly the
// national root and issuing CA, and only for its own requests. The two files are
// byte-for-byte the ones published at https://esign.gov.mn/MNRCA.zip and
// https://esign.gov.mn/MNICA.zip. See docs/tls.md in the repository.
var (
	//go:embed certs/MNRCA-2021.pem
	nationalRootCA string
	//go:embed certs/MNICA-2022.pem
	nationalIssuingCA string
)

// BundledCAs returns a fresh pool holding MNRCA-2021 and MNICA-2022 and nothing
// else. Add your own certificates to it and pass it as Options.RootCAs when you
// reach XYP through a proxy that re-terminates TLS.
func BundledCAs() *x509.CertPool {
	pool := x509.NewCertPool()
	pool.AppendCertsFromPEM([]byte(nationalRootCA))
	pool.AppendCertsFromPEM([]byte(nationalIssuingCA))
	return pool
}

// newTransport clones the default transport so connection pooling, proxy support
// and HTTP/2 behave as they do everywhere else, and only replaces the trust.
func newTransport(roots *x509.CertPool, insecure bool) *http.Transport {
	transport, isDefault := http.DefaultTransport.(*http.Transport)
	if !isDefault {
		transport = &http.Transport{}
	}
	cloned := transport.Clone()
	cloned.TLSClientConfig = &tls.Config{
		MinVersion:         tls.VersionTLS12,
		RootCAs:            roots,
		InsecureSkipVerify: insecure, //nolint:gosec // opt-in only, via Options.InsecureSkipVerify
	}
	return cloned
}
