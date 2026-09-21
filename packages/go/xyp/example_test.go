package xyp_test

import (
	"context"
	"errors"
	"fmt"
	"log"
	"time"

	"github.com/baljinnyamday/xyp-sdk/packages/go/xyp"
	"github.com/baljinnyamday/xyp-sdk/packages/go/xyp/citizen"
)

// Every example but the last needs the National Data Center VPN and real
// credentials, so they compile here rather than run.

func ExampleNewClient() {
	key, err := xyp.LoadPrivateKey("private.key")
	if err != nil {
		log.Fatal(err)
	}
	client, err := xyp.NewClient(xyp.Options{
		AccessToken: "issued-by-the-national-data-center",
		PrivateKey:  key,
		Timeout:     10 * time.Second,
	})
	if err != nil {
		log.Fatal(err)
	}
	defer client.Close()

	card, err := citizen.New(client).GetCitizenIDCardInfo(context.Background(),
		citizen.GetCitizenIDCardInfoParams{Regnum: "РД00000000"})
	if err != nil {
		fmt.Println("xyp:", err)
		return
	}
	fmt.Println(card.Firstname, card.Lastname)
}

// A raw call takes XYP's own names and gives back the response tree, which is
// how to reach a service newer than this SDK version.
func ExampleClient_Call() {
	client, err := xyp.NewClient(xyp.Options{}) // XYP_ACCESS_TOKEN + XYP_PRIVATE_KEY
	if err != nil {
		log.Fatal(err)
	}
	defer client.Close()

	data, err := client.Call(context.Background(), "WS109999_brandNew",
		xyp.Params{{Name: "regnum", Value: "РД00000000"}},
		xyp.WithEndpoint("citizen-1.5.0"))
	if err != nil {
		fmt.Println("xyp:", err)
		return
	}
	fmt.Println(data)
}

// Invoke decodes into a struct of your own, so a service the generated packages
// do not cover yet is still typed.
func ExampleClient_Invoke() {
	type idCard struct {
		Firstname string     `xyp:"firstname"`
		Lastname  string     `xyp:"lastname"`
		BirthDate xyp.Date   `xyp:"birthDate"`
		Extras    xyp.Extras `xyp:"-"`
	}
	client, err := xyp.NewClient(xyp.Options{})
	if err != nil {
		log.Fatal(err)
	}
	defer client.Close()

	var card idCard
	card.Extras, err = client.Invoke(context.Background(), "WS100101_getCitizenIDCardInfo",
		xyp.Params{{Name: "regnum", Value: "РД00000000"}}, &card)
	if err != nil {
		fmt.Println("xyp:", err)
		return
	}
	// Fields the struct does not declare stay reachable through Extras.Raw.
	fmt.Println(card.Firstname, card.BirthDate.Raw, len(card.Extras.Mismatches))
}

// Services that return citizen data need the citizen's approval.
func ExampleWithAuth() {
	client, err := xyp.NewClient(xyp.Options{})
	if err != nil {
		log.Fatal(err)
	}
	defer client.Close()

	card, err := citizen.New(client).GetCitizenIDCardInfo(context.Background(),
		citizen.GetCitizenIDCardInfoParams{Regnum: "РД00000000"},
		xyp.WithAuth(xyp.OTPAuth("РД00000000", 123456)))
	if err != nil {
		fmt.Println("xyp:", err)
		return
	}
	fmt.Println(card.Firstname)
}

// Result codes are sentinels for errors.Is; the details come out with errors.As.
func ExampleClient_Invoke_errors() {
	client, err := xyp.NewClient(xyp.Options{})
	if err != nil {
		log.Fatal(err)
	}
	defer client.Close()

	card, err := citizen.New(client).GetCitizenIDCardInfo(context.Background(),
		citizen.GetCitizenIDCardInfoParams{Regnum: "РД00000000"})
	var apiErr *xyp.APIError
	switch {
	case errors.Is(err, xyp.ErrNotFound):
		fmt.Println("no record for this person")
	case errors.Is(err, xyp.ErrTimeout):
		fmt.Println("XYP did not answer in time; retry later")
	case errors.As(err, &apiErr):
		fmt.Println(apiErr.ResultCode, apiErr.ResultMessage, apiErr.RequestID)
	case err != nil:
		fmt.Println(xyp.OriginOf(err), err)
	default:
		fmt.Println(card.Firstname)
	}
}

// OriginOf says whose side a failure is on, which is what a log line needs.
func ExampleOriginOf() {
	err := error(&xyp.APIError{ResultCode: 1, ResultMessage: "олдсонгүй"})

	fmt.Println(xyp.OriginOf(err), errors.Is(err, xyp.ErrNotFound), err)
	// Output: xyp true [1] олдсонгүй
}
