/*
Package xyp is a Go SDK for XYP (ХУР), Mongolia's government data exchange
system. It signs every request, builds the SOAP envelope, verifies XYP's
certificate against the bundled national CAs, and decodes the XML into your
structs.

# Quick start

	client, err := xyp.NewClient(xyp.Options{
		AccessToken: os.Getenv("XYP_ACCESS_TOKEN"),
		PrivateKey:  key, // from xyp.LoadPrivateKey("private.key")
	})
	if err != nil {
		return err
	}
	defer client.Close()

	card, err := citizen.New(client).GetCitizenIDCardInfo(ctx,
		citizen.GetCitizenIDCardInfoParams{Regnum: "РД00000000"})
	if err != nil {
		return err
	}
	fmt.Println(card.Firstname, card.Lastname)

Services are grouped by XYP endpoint, one Go package each (citizen, health,
insurance, ...), and keep XYP's own names minus the code:
WS100101_getCitizenIDCardInfo is citizen.Service.GetCitizenIDCardInfo.

One Client is meant to be shared: it is safe for concurrent use and keeps
connections alive.

# Request parameters

[Client.Call] and [Client.Invoke] take params in any of these shapes:

  - nil, for a service without inputs;
  - a struct, or a pointer to one, whose exported fields carry `xyp:"wireName"`
    tags and are sent in declaration order (`xyp:"-"` skips a field, and a field
    without a tag uses its Go name);
  - [Params], when you want to choose the order yourself;
  - a map with string keys, whose keys are sorted, because a Go map has no
    order. Use a struct or [Params] when the schema order matters.

A value is left out of the request when it is nil, a nil pointer, slice or map,
an empty string, or a zero [Date] or [time.Time]. Otherwise: a pointer is followed, a bool
becomes "1" or "0", a []byte becomes base64, a [time.Time] and a [Date] without
Raw become UTC RFC 3339, a [Date] with Raw is sent as it stands, any other slice
is repeated once per item, and a nested struct, map or [Params] becomes a nested
element. Anything else is a *[ConfigError], and so is an element name that is
not a valid XML name: names cannot be escaped, so they are checked instead.

# Responses

A response field that does not fit the SDK's model never fails a call: it keeps
its zero value, and its raw value is listed in the [Extras] the call returns.
The models are generated from XYP's hand-typed public catalog, so this is a gap
in the SDK, not in XYP's answer or in your code — please report one.

Response fields are typed so that "absent" is always distinguishable where it
can be: string is plain (XML cannot tell an empty string from a missing
element), while an integer, float or bool is a pointer, a nested object is a
pointer to a struct, and a list is a slice.

# Errors

Every error this package returns carries an [Origin], and the ones from XYP
carry a result code. Compare with [errors.Is] and unpack with [errors.As]:

	if errors.Is(err, xyp.ErrNotFound) {
		return nil, nil
	}
	var apiErr *xyp.APIError
	if errors.As(err, &apiErr) {
		log.Println(apiErr.ResultCode, apiErr.RequestID)
	}

# TLS

XYP's certificate is issued by the Mongolian national PKI, which no operating
system trusts by default. The SDK trusts exactly the two bundled national CAs,
for its own requests only; see docs/tls.md in the repository.
*/
package xyp
