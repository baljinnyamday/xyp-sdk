package xyp_test

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/baljinnyamday/xyp-sdk/packages/go/xyp"
	"github.com/baljinnyamday/xyp-sdk/packages/go/xyp/citizen"
	"github.com/baljinnyamday/xyp-sdk/packages/go/xyp/insurance"
)

const testTimeout = 5 * time.Second

func TestCallsATypedServiceEndToEnd(t *testing.T) {
	t.Parallel()
	fake := newFakeXYP(t, func(recorded) reply { return reply{body: soapResponse(idCard, 0, "ok")} })
	client := connect(t, fake, testTimeout)

	card, err := citizen.New(client).GetCitizenIDCardInfo(context.Background(),
		citizen.GetCitizenIDCardInfoParams{Regnum: regnum},
		xyp.WithAuth(xyp.OTPAuth(regnum, 1234)))
	if err != nil {
		t.Fatalf("GetCitizenIDCardInfo: %v", err)
	}

	if card.Firstname != "Бат" {
		t.Errorf("Firstname = %q", card.Firstname)
	}
	if len(card.Xyp.Mismatches) != 0 {
		t.Errorf("Mismatches = %v", card.Xyp.Mismatches)
	}
	if raw, isObject := card.Xyp.Raw.(map[string]any); !isObject || raw["firstname"] != "Бат" {
		t.Errorf("Raw = %#v, want the whole response", card.Xyp.Raw)
	}

	requests := fake.recorded()
	if len(requests) != 1 {
		t.Fatalf("made %d requests, want 1", len(requests))
	}
	request := requests[0]
	if request.method != http.MethodPost || request.url != "/citizen-1.5.0/ws" {
		t.Errorf("%s %s, want POST /citizen-1.5.0/ws", request.method, request.url)
	}
	// Go canonicalises incoming header names, so only the values can be asserted here;
	// signer_test.go checks that the outgoing names keep XYP's mixed case.
	if got := request.header.Get("accessToken"); got != testToken {
		t.Errorf("accessToken = %q", got)
	}
	if got := request.header.Get("timeStamp"); !isDigits(got) {
		t.Errorf("timeStamp = %q, want whole seconds", got)
	}
	if request.header.Get("signature") == "" {
		t.Error("no signature header")
	}
	if got := request.header.Get("Content-Type"); got != "text/xml; charset=utf-8" {
		t.Errorf("Content-Type = %q", got)
	}
	if got := request.header.Get("SOAPAction"); got != `""` {
		t.Errorf("SOAPAction = %q", got)
	}
	for _, want := range []string{
		`xmlns:tns="http://citizen.xyp.gov.mn/"`,
		"<tns:WS100101_getCitizenIDCardInfo><request><auth><citizen>",
		"<otp>1234</otp><regnum>" + regnum + "</regnum>",
	} {
		if !strings.Contains(request.body, want) {
			t.Errorf("request body does not contain %q:\n%s", want, request.body)
		}
	}
}

func TestSendsInputsInSchemaOrderWhateverOrderTheCallerUsed(t *testing.T) {
	t.Parallel()
	fake := newFakeXYP(t, func(recorded) reply { return reply{body: soapResponse(idCard, 0, "ok")} })
	client := connect(t, fake, testTimeout)

	// Named fields, so the caller cannot even express an order; the struct holds it.
	_, err := citizen.New(client).GetCitizenIDCardInfo(context.Background(),
		citizen.GetCitizenIDCardInfoParams{Regnum: regnum, CivilId: "1"})
	if err != nil {
		t.Fatalf("GetCitizenIDCardInfo: %v", err)
	}

	want := "<civilId>1</civilId><regnum>" + regnum + "</regnum>"
	if body := fake.recorded()[0].body; !strings.Contains(body, want) {
		t.Errorf("request body does not contain %q:\n%s", want, body)
	}
}

func TestCallByOriginalNameReturnsRawData(t *testing.T) {
	t.Parallel()
	fake := newFakeXYP(t, func(recorded) reply { return reply{body: soapResponse(idCard, 0, "ok")} })
	client := connect(t, fake, testTimeout)

	data, err := client.Call(context.Background(), "WS100101_getCitizenIDCardInfo",
		xyp.Params{{Name: "regnum", Value: regnum}})
	if err != nil {
		t.Fatalf("Call: %v", err)
	}
	fields, isObject := data.(map[string]any)
	if !isObject || fields["firstname"] != "Бат" || fields["regnum"] != regnum {
		t.Errorf("data = %#v", data)
	}

	_, err = client.Call(context.Background(), "WS999999_doesNotExist", nil)
	var configErr *xyp.ConfigError
	if !errors.As(err, &configErr) || !strings.Contains(configErr.Message, "unknown operation") {
		t.Errorf("err = %v, want a *ConfigError about an unknown operation", err)
	}
	if !strings.Contains(configErr.Message, "WithEndpoint") {
		t.Errorf("message = %q, want it to point at WithEndpoint", configErr.Message)
	}
}

func TestCallAcceptsAnEndpointTheRegistryDoesNotKnow(t *testing.T) {
	t.Parallel()
	fake := newFakeXYP(t, func(request recorded) reply {
		if request.method == http.MethodGet {
			return reply{body: `<wsdl:definitions targetNamespace="http://brand.new/">`}
		}
		return reply{body: soapResponse(idCard, 0, "ok")}
	})
	client := connect(t, fake, testTimeout)

	_, err := client.Call(context.Background(), "WS109999_brandNew", nil,
		xyp.WithEndpoint("citizen-9.9.9"))
	if err != nil {
		t.Fatalf("Call: %v", err)
	}
	if url := fake.recorded()[0].url; url != "/citizen-9.9.9/ws?WSDL" {
		t.Errorf("first request = %q", url)
	}
}

func TestReadsAnUnverifiedNamespaceFromTheWSDLOnce(t *testing.T) {
	t.Parallel()
	fake := newFakeXYP(t, func(request recorded) reply {
		if request.method == http.MethodGet {
			return reply{body: `<wsdl:definitions targetNamespace="http://insurance.example/">`}
		}
		return reply{body: soapResponse("<isPensioner>true</isPensioner>", 0, "ok")}
	})
	service := insurance.New(connect(t, fake, testTimeout))
	params := insurance.GetCitizenPensionInquiryParams{Regnum: regnum}

	first, err := service.GetCitizenPensionInquiry(context.Background(), params)
	if err != nil {
		t.Fatalf("GetCitizenPensionInquiry: %v", err)
	}
	if _, err := service.GetCitizenPensionInquiry(context.Background(), params); err != nil {
		t.Fatalf("GetCitizenPensionInquiry: %v", err)
	}

	if first.IsPensioner == nil || !*first.IsPensioner {
		t.Errorf("IsPensioner = %v", first.IsPensioner)
	}
	gets := []string{}
	for _, request := range fake.recorded() {
		if request.method == http.MethodGet {
			gets = append(gets, request.url)
		}
	}
	if len(gets) != 1 || gets[0] != "/insurance-1.5.0/ws?WSDL" {
		t.Errorf("WSDL reads = %v, want exactly one", gets)
	}
	last := fake.recorded()[len(fake.recorded())-1]
	if !strings.Contains(last.body, `xmlns:tns="http://insurance.example/"`) {
		t.Errorf("the learned namespace was not reused:\n%s", last.body)
	}
}

func TestTheNamespaceCacheIsSafeForConcurrentUse(t *testing.T) {
	t.Parallel()
	fake := newFakeXYP(t, func(request recorded) reply {
		if request.method == http.MethodGet {
			return reply{body: `<wsdl:definitions targetNamespace="http://insurance.example/">`}
		}
		return reply{body: soapResponse("<isPensioner>true</isPensioner>", 0, "ok")}
	})
	service := insurance.New(connect(t, fake, testTimeout))

	var group sync.WaitGroup
	for range 16 {
		group.Add(1)
		go func() {
			defer group.Done()
			_, err := service.GetCitizenPensionInquiry(context.Background(),
				insurance.GetCitizenPensionInquiryParams{Regnum: regnum})
			if err != nil {
				t.Errorf("GetCitizenPensionInquiry: %v", err)
			}
		}()
	}
	group.Wait()
}

func TestResultCodesBecomeErrorsThatSayWhoseSideItIsOn(t *testing.T) {
	t.Parallel()
	fake := newFakeXYP(t, func(recorded) reply {
		return reply{body: soapResponse("", 1, "олдсонгүй")}
	})
	client := connect(t, fake, testTimeout)

	_, err := citizen.New(client).GetCitizenIDCardInfo(context.Background(),
		citizen.GetCitizenIDCardInfoParams{Regnum: regnum})

	if !errors.Is(err, xyp.ErrNotFound) {
		t.Fatalf("err = %v, want ErrNotFound", err)
	}
	var apiErr *xyp.APIError
	if !errors.As(err, &apiErr) {
		t.Fatalf("err = %v, want an *APIError", err)
	}
	if apiErr.ResultCode != 1 || apiErr.ResultMessage != "олдсонгүй" {
		t.Errorf("apiErr = %#v", apiErr)
	}
	if apiErr.RequestID != "4fd9aa5f-1984-4b61-b379-13c1bcbd29c7" {
		t.Errorf("RequestID = %q", apiErr.RequestID)
	}
	if apiErr.Error() != "[1] олдсонгүй" {
		t.Errorf("Error() = %q", apiErr.Error())
	}
	if xyp.OriginOf(err) != xyp.OriginXYP {
		t.Errorf("origin = %q", xyp.OriginOf(err))
	}
}

func TestEveryDocumentedCodeMapsToASentinel(t *testing.T) {
	t.Parallel()
	for code, want := range map[int]error{
		1: xyp.ErrNotFound, 2: xyp.ErrInternal, 3: xyp.ErrInvalidRequest,
		200: xyp.ErrAuthRequired, 201: xyp.ErrAuthRequired, 202: xyp.ErrAuthRequired,
		203: xyp.ErrAccessDenied, 501: xyp.ErrAccessDenied,
		301: xyp.ErrFingerprint, 302: xyp.ErrFingerprint, 303: xyp.ErrFingerprint,
		304: xyp.ErrFingerprint, 401: xyp.ErrCitizenData, 402: xyp.ErrCitizenData,
		601: xyp.ErrSignature, 602: xyp.ErrSignature, 603: xyp.ErrSignature,
		604: xyp.ErrSignature, 605: xyp.ErrSignature,
		801: xyp.ErrProvider, 802: xyp.ErrProvider,
	} {
		err := error(&xyp.APIError{ResultCode: code, ResultMessage: "m"})
		if !errors.Is(err, want) {
			t.Errorf("code %d does not match its sentinel", code)
		}
		if errors.Is(err, xyp.ErrInternal) && !errors.Is(want, xyp.ErrInternal) {
			t.Errorf("code %d matches an unrelated sentinel", code)
		}
	}
	unknown := error(&xyp.APIError{ResultCode: 9999, ResultMessage: "m"})
	for _, sentinel := range []error{xyp.ErrNotFound, xyp.ErrInternal, xyp.ErrProvider} {
		if errors.Is(unknown, sentinel) {
			t.Errorf("an undocumented code matched %v", sentinel)
		}
	}
	var apiErr *xyp.APIError
	if !errors.As(unknown, &apiErr) {
		t.Error("an undocumented code must still be an *APIError")
	}
}

func TestUnreachableXYPBecomesAConnectionError(t *testing.T) {
	t.Parallel()
	client, err := xyp.NewClient(xyp.Options{
		AccessToken: testToken,
		PrivateKey:  testKey(t),
		// Port 1 refuses connections on every platform CI runs on.
		BaseURL: "http://127.0.0.1:1",
	})
	if err != nil {
		t.Fatalf("NewClient: %v", err)
	}
	t.Cleanup(client.Close)

	_, err = citizen.New(client).GetCitizenIDCardInfo(context.Background(),
		citizen.GetCitizenIDCardInfoParams{})

	var connectionErr *xyp.ConnectionError
	if !errors.As(err, &connectionErr) {
		t.Fatalf("err = %v, want a *ConnectionError", err)
	}
	if xyp.OriginOf(err) != xyp.OriginNetwork {
		t.Errorf("origin = %q", xyp.OriginOf(err))
	}
	if !strings.Contains(connectionErr.Error(), "VPN") {
		t.Errorf("message = %q, want it to mention the VPN", connectionErr.Error())
	}
	if connectionErr.Timeout() || errors.Is(err, xyp.ErrTimeout) {
		t.Error("a refused connection is not a timeout")
	}
}

func TestATimeoutIsAConnectionErrorThatSaysSo(t *testing.T) {
	t.Parallel()
	fake := newFakeXYP(t, func(recorded) reply {
		return reply{body: soapResponse(idCard, 0, "ok"), delay: 500 * time.Millisecond}
	})
	client := connect(t, fake, 50*time.Millisecond)

	_, err := citizen.New(client).GetCitizenIDCardInfo(context.Background(),
		citizen.GetCitizenIDCardInfoParams{})

	if !errors.Is(err, xyp.ErrTimeout) {
		t.Fatalf("err = %v, want ErrTimeout", err)
	}
	var connectionErr *xyp.ConnectionError
	if !errors.As(err, &connectionErr) || !connectionErr.Timeout() {
		t.Errorf("err = %v, want a *ConnectionError that reports Timeout()", err)
	}
	if !errors.Is(err, context.DeadlineExceeded) {
		t.Error("the cause must stay reachable through Unwrap")
	}
}

func TestACancelledContextStaysReachableThroughUnwrap(t *testing.T) {
	t.Parallel()
	fake := newFakeXYP(t, func(recorded) reply {
		return reply{body: soapResponse(idCard, 0, "ok"), delay: 500 * time.Millisecond}
	})
	client := connect(t, fake, testTimeout)

	ctx, cancel := context.WithCancel(context.Background())
	go func() {
		time.Sleep(20 * time.Millisecond)
		cancel()
	}()
	_, err := citizen.New(client).GetCitizenIDCardInfo(ctx, citizen.GetCitizenIDCardInfoParams{})

	if !errors.Is(err, context.Canceled) {
		t.Fatalf("err = %v, want context.Canceled in the chain", err)
	}
	if errors.Is(err, xyp.ErrTimeout) {
		t.Error("a cancellation is not a timeout")
	}
}

func TestAGatewayErrorPageIsReportedWithItsStatus(t *testing.T) {
	t.Parallel()
	fake := newFakeXYP(t, func(recorded) reply {
		return reply{status: http.StatusBadGateway, body: "<html>Bad Gateway</html>"}
	})
	client := connect(t, fake, testTimeout)

	_, err := citizen.New(client).GetCitizenIDCardInfo(context.Background(),
		citizen.GetCitizenIDCardInfoParams{})

	var responseErr *xyp.ResponseError
	if !errors.As(err, &responseErr) {
		t.Fatalf("err = %v, want a *ResponseError", err)
	}
	if responseErr.StatusCode != http.StatusBadGateway {
		t.Errorf("StatusCode = %d", responseErr.StatusCode)
	}
	if xyp.OriginOf(err) != xyp.OriginXYP {
		t.Errorf("origin = %q", xyp.OriginOf(err))
	}
}

func TestASOAPFaultWithHTTP500KeepsItsText(t *testing.T) {
	t.Parallel()
	fault := `<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"><soap:Body>` +
		`<soap:Fault><faultcode>soap:Client</faultcode><faultstring>Unmarshalling Error: ` +
		`unexpected element</faultstring></soap:Fault></soap:Body></soap:Envelope>`
	fake := newFakeXYP(t, func(recorded) reply {
		return reply{status: http.StatusInternalServerError, body: fault}
	})
	client := connect(t, fake, testTimeout)

	_, err := citizen.New(client).GetCitizenIDCardInfo(context.Background(),
		citizen.GetCitizenIDCardInfoParams{})

	var responseErr *xyp.ResponseError
	if !errors.As(err, &responseErr) {
		t.Fatalf("err = %v, want a *ResponseError", err)
	}
	if responseErr.StatusCode != http.StatusInternalServerError {
		t.Errorf("StatusCode = %d", responseErr.StatusCode)
	}
	for _, want := range []string{"HTTP 500", "Unmarshalling Error: unexpected element"} {
		if !strings.Contains(responseErr.Message, want) {
			t.Errorf("message = %q, want it to contain %q", responseErr.Message, want)
		}
	}
}

func TestNewClientRejectsABaseURLWithoutAScheme(t *testing.T) {
	t.Parallel()
	_, err := xyp.NewClient(xyp.Options{
		AccessToken: testToken, PrivateKey: testKey(t), BaseURL: "xyp.gov.mn",
	})

	var configErr *xyp.ConfigError
	if !errors.As(err, &configErr) || !strings.Contains(configErr.Message, "https://") {
		t.Errorf("err = %v, want a *ConfigError naming the scheme", err)
	}
}

func TestNewClientReadsCredentialsFromTheEnvironment(t *testing.T) {
	t.Setenv(xyp.AccessTokenEnv, "")
	t.Setenv(xyp.PrivateKeyEnv, "")

	_, err := xyp.NewClient(xyp.Options{PrivateKey: testKey(t)})
	if err == nil || !strings.Contains(err.Error(), xyp.AccessTokenEnv) {
		t.Errorf("err = %v, want it to name %s", err, xyp.AccessTokenEnv)
	}
	_, err = xyp.NewClient(xyp.Options{AccessToken: testToken})
	if err == nil || !strings.Contains(err.Error(), xyp.PrivateKeyEnv) {
		t.Errorf("err = %v, want it to name %s", err, xyp.PrivateKeyEnv)
	}

	t.Setenv(xyp.AccessTokenEnv, testToken)
	client, err := xyp.NewClient(xyp.Options{PrivateKey: testKey(t)})
	if err != nil {
		t.Fatalf("NewClient: %v", err)
	}
	client.Close()
}

func TestPrintingTheClientNeverRevealsCredentials(t *testing.T) {
	t.Parallel()
	fake := newFakeXYP(t, func(recorded) reply { return reply{body: soapResponse(idCard, 0, "ok")} })
	client := connect(t, fake, testTimeout)

	for _, format := range []string{"%v", "%+v", "%#v", "%s"} {
		printed := fmt.Sprintf(format, client)
		if strings.Contains(printed, testToken) {
			t.Errorf("%s revealed the access token: %s", format, printed)
		}
		if !strings.Contains(printed, fake.baseURL) {
			t.Errorf("%s says nothing useful: %s", format, printed)
		}
	}
}

func TestInvokeChecksTheResponseStructBeforeSendingAnything(t *testing.T) {
	t.Parallel()
	fake := newFakeXYP(t, func(recorded) reply { return reply{body: soapResponse(idCard, 0, "ok")} })
	client := connect(t, fake, testTimeout)

	var notDecodable struct {
		Age int `xyp:"age"` // an int must be a *int64: 0 is a real answer
	}
	_, err := client.Invoke(context.Background(), "WS100101_getCitizenIDCardInfo", nil,
		&notDecodable)

	var configErr *xyp.ConfigError
	if !errors.As(err, &configErr) {
		t.Fatalf("err = %v, want a *ConfigError", err)
	}
	if len(fake.recorded()) != 0 {
		t.Error("a struct the SDK cannot fill must not cost a request to XYP")
	}
}

func TestClientStaysUsableAfterClose(t *testing.T) {
	t.Parallel()
	fake := newFakeXYP(t, func(recorded) reply { return reply{body: soapResponse(idCard, 0, "ok")} })
	client := connect(t, fake, testTimeout)

	client.Close() // only drops idle connections
	if _, err := client.Call(context.Background(), "WS100101_getCitizenIDCardInfo", nil); err != nil {
		t.Errorf("Call after Close: %v", err)
	}
}

func isDigits(text string) bool {
	if text == "" {
		return false
	}
	return strings.IndexFunc(text, func(r rune) bool { return r < '0' || r > '9' }) < 0
}

func TestCallRejectsAnEndpointThatIsNotAPathSegment(t *testing.T) {
	t.Parallel()
	fake := newFakeXYP(t, func(recorded) reply { return reply{body: soapResponse("", 0, "ok")} })
	client := connect(t, fake, testTimeout)

	for _, endpoint := range []string{"../admin", "citizen-1.5.0/ws?x=", "a b", ".."} {
		_, err := client.Call(context.Background(), "WS100101_getCitizenIDCardInfo", nil, xyp.WithEndpoint(endpoint))
		var configErr *xyp.ConfigError
		if !errors.As(err, &configErr) {
			t.Errorf("endpoint %q: err = %v, want a *ConfigError", endpoint, err)
		}
	}
	if got := len(fake.recorded()); got != 0 {
		t.Errorf("%d requests reached the server, want 0", got)
	}
}
