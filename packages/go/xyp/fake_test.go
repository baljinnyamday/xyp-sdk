package xyp_test

import (
	"crypto/rand"
	"crypto/rsa"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strconv"
	"sync"
	"testing"
	"time"

	"github.com/baljinnyamday/xyp-sdk/packages/go/xyp"
)

const (
	testToken = "test-access-token"
	regnum    = "РД00000000"
	idCard    = "<firstname>Бат</firstname><regnum>" + regnum + "</regnum>"
	xsi       = `xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"`
)

// soapResponse is shaped like the sample on developer.xyp.gov.mn/docs/result-code.
func soapResponse(inner string, code int, message string) string {
	return `<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
  <soap:Body>
    <ns2:WS100101_getCitizenIDCardInfoResponse xmlns:ns2="http://citizen.xyp.gov.mn/">
      <return>
        <requestId>4fd9aa5f-1984-4b61-b379-13c1bcbd29c7</requestId>
        <response ` + xsi + ` xsi:type="ns2:citizenData">` + inner + `</response>
        <resultCode>` + strconv.Itoa(code) + `</resultCode>
        <resultMessage>` + message + `</resultMessage>
      </return>
    </ns2:WS100101_getCitizenIDCardInfoResponse>
  </soap:Body>
</soap:Envelope>`
}

type recorded struct {
	method string
	url    string
	header http.Header
	body   string
}

type reply struct {
	status int
	body   string
	delay  time.Duration
}

// fakeXYP is a local stand-in for xyp.gov.mn; answer decides what each request gets.
type fakeXYP struct {
	baseURL string

	mu       sync.Mutex
	requests []recorded
}

func newFakeXYP(t *testing.T, answer func(recorded) reply) *fakeXYP {
	t.Helper()
	fake := &fakeXYP{}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		request := recorded{
			method: r.Method, url: r.URL.String(), header: r.Header.Clone(), body: string(body),
		}
		fake.mu.Lock()
		fake.requests = append(fake.requests, request)
		fake.mu.Unlock()

		answered := answer(request)
		if answered.delay > 0 {
			time.Sleep(answered.delay)
		}
		if answered.status == 0 {
			answered.status = http.StatusOK
		}
		w.Header().Set("Content-Type", "text/xml; charset=utf-8")
		w.WriteHeader(answered.status)
		_, _ = io.WriteString(w, answered.body)
	}))
	t.Cleanup(server.Close)
	fake.baseURL = server.URL
	return fake
}

func (f *fakeXYP) recorded() []recorded {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]recorded(nil), f.requests...)
}

// testKey is a throwaway key generated for the test run.
func testKey(t *testing.T) *rsa.PrivateKey {
	t.Helper()
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatalf("generate key: %v", err)
	}
	return key
}

func connect(t *testing.T, fake *fakeXYP, timeout time.Duration) *xyp.Client {
	t.Helper()
	client, err := xyp.NewClient(xyp.Options{
		AccessToken: testToken,
		PrivateKey:  testKey(t),
		BaseURL:     fake.baseURL,
		Timeout:     timeout,
		// The mismatch warning is asserted in decode_test.go, not here.
		Logger: slog.New(slog.NewTextHandler(io.Discard, nil)),
	})
	if err != nil {
		t.Fatalf("NewClient: %v", err)
	}
	t.Cleanup(client.Close)
	return client
}
