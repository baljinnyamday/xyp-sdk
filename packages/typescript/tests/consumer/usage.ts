/**
 * Compiled against the BUILT declaration files (dist/index.d.ts and dist/index.d.cts)
 * with skipLibCheck off, the way a user's project sees the package. The unit tests
 * import from src/, so they cannot notice a declaration bundle that is broken.
 */
import {
  auth,
  type GetCitizenIDCardInfoParams,
  type GetCitizenIDCardInfoResponse,
  NotFoundError,
  Xyp,
  type XypResult,
} from "xyp-sdk";

const xyp = new Xyp({ accessToken: "t", privateKey: "key.pem" });

export async function main(regnum: string): Promise<string | null> {
  const params: GetCitizenIDCardInfoParams = { regnum };
  try {
    const card = await xyp.citizen.getCitizenIDCardInfo(params, {
      auth: auth.otp({ regnum, otp: 1 }),
    });
    const typed: XypResult<GetCitizenIDCardInfoResponse> = card;
    const born: Date | string | null = card.birthDate;
    const photo: Uint8Array | null = card.image;
    const mismatches: number = card.xypMismatches.length;
    await xyp.meta.registerOTPRequest({ regnum, isSms: 1 }, { auth: { regnum } });
    return `${typed.firstname} ${born} ${photo?.length} ${mismatches}`;
  } catch (error) {
    if (error instanceof NotFoundError) return `${error.resultCode} ${error.origin}`;
    throw error;
  }
}

export async function mistakesMustNotCompile(): Promise<void> {
  // @ts-expect-error regnum is a string
  await xyp.citizen.getCitizenIDCardInfo({ regnum: 123 });
  // @ts-expect-error unknown input field
  await xyp.citizen.getCitizenIDCardInfo({ notAField: "x" });
  // @ts-expect-error unknown service
  await xyp.citizen.noSuchService({});
  // @ts-expect-error the WSDL declares isSms as an int, not a boolean
  await xyp.meta.registerOTPRequest({ isSms: true });
  // @ts-expect-error every response field may be null
  (await xyp.citizen.getCitizenIDCardInfo({})).firstname.toUpperCase();
  // @ts-expect-error lists are readonly
  (await xyp.citizen.getCitizenIDCardInfo({})).listAddress?.push(null as never);
}
