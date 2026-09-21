package xyp

// AuthType is how a person proved they approve of the request.
type AuthType int

const (
	// AuthSMSOTP is a one-time code the person received by SMS.
	AuthSMSOTP AuthType = 1
	// AuthDigitalSignature is the person's own digital signature.
	AuthDigitalSignature AuthType = 2
	// AuthFingerprint is a freshly scanned fingerprint image.
	AuthFingerprint AuthType = 3
	// AuthSSOOTP is a one-time code issued through the government SSO.
	AuthSSOOTP AuthType = 4
	// AuthDanApp is approval through the ДАН mobile app.
	AuthDanApp AuthType = 5
)

// Auth mirrors the WSDL's authorizationEntity. Prefer the constructors below;
// build one by hand only for a combination they do not cover.
type Auth struct {
	Regnum          string
	CivilID         string
	AuthType        AuthType
	OTP             int
	Fingerprint     []byte
	Signature       string
	CertFingerprint string
	AppAuthToken    string
	AuthAppName     string
}

// OTPAuth is approval by a one-time code the person received by SMS.
func OTPAuth(regnum string, otp int) Auth {
	return Auth{Regnum: regnum, OTP: otp, AuthType: AuthSMSOTP}
}

// SSOOTPAuth is approval by a one-time code issued through the government SSO.
func SSOOTPAuth(regnum string, otp int) Auth {
	return Auth{Regnum: regnum, OTP: otp, AuthType: AuthSSOOTP}
}

// SignatureAuth is approval by the person's digital signature and the
// fingerprint of the certificate that produced it.
func SignatureAuth(regnum, signature, certFingerprint string) Auth {
	return Auth{
		Regnum:          regnum,
		Signature:       signature,
		CertFingerprint: certFingerprint,
		AuthType:        AuthDigitalSignature,
	}
}

// FingerprintAuth is approval by a freshly scanned fingerprint image.
func FingerprintAuth(regnum string, fingerprint []byte) Auth {
	return Auth{Regnum: regnum, Fingerprint: fingerprint, AuthType: AuthFingerprint}
}

// DanAppAuth is approval through the ДАН mobile app.
func DanAppAuth(regnum string) Auth {
	return Auth{Regnum: regnum, AuthType: AuthDanApp}
}

// toWire lists the fields in the order the WSDL declares them. Unset fields are
// omitted by the encoder, with two exceptions this function handles itself:
// authType is left out when it is zero (no approval type was chosen), and otp is
// always sent, because some XYP WSDLs declare it as a required int and the
// official samples send 0 when there is no code.
func (a Auth) toWire() Params {
	var authType any
	if a.AuthType != 0 {
		authType = int(a.AuthType)
	}
	return Params{
		{Name: "appAuthToken", Value: a.AppAuthToken},
		{Name: "authAppName", Value: a.AuthAppName},
		{Name: "authType", Value: authType},
		{Name: "certFingerprint", Value: a.CertFingerprint},
		{Name: "civilId", Value: a.CivilID},
		{Name: "fingerprint", Value: a.Fingerprint},
		{Name: "otp", Value: a.OTP},
		{Name: "regnum", Value: a.Regnum},
		{Name: "signature", Value: a.Signature},
	}
}
