// First-contact check against the real XYP. Run it from a machine on the VPN:
//
//	export XYP_ACCESS_TOKEN=...            # never commit these
//	export XYP_PRIVATE_KEY=/path/to/private.key
//	go run ./examples/smoke
//
// listAccess needs no citizen data, so it is the safest first call. It proves
// four things at once: TLS against the bundled national CAs, the request
// signature, the SOAP envelope, and response parsing.
package main

import (
	"context"
	"fmt"
	"os"

	"github.com/baljinnyamday/xyp-sdk/packages/go/xyp"
	"github.com/baljinnyamday/xyp-sdk/packages/go/xyp/meta"
)

func main() {
	if err := run(); err != nil {
		fmt.Fprintf(os.Stderr, "FAILED (%s): %v\n", xyp.OriginOf(err), err)
		os.Exit(1)
	}
}

func run() error {
	client, err := xyp.NewClient(xyp.Options{})
	if err != nil {
		return err
	}
	defer client.Close()

	access, err := meta.New(client).ListAccess(context.Background(), meta.ListAccessParams{})
	if err != nil {
		return err
	}
	// Deliberately not the whole response: listAccess echoes your access token and
	// certificate, and this output is what people paste into bug reports.
	registered := access.Registered != nil && *access.Registered
	fmt.Printf("OK: organisation=%q registered=%t approved_services=%d model_mismatches=%d\n",
		access.OrgTitle, registered, len(access.ApprovedServices), len(access.Xyp.Mismatches))
	return nil
}
