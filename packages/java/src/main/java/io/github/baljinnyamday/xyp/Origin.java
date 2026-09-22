package io.github.baljinnyamday.xyp;

/**
 * Whose side a problem is on, so logs and dashboards can route it. Every {@link XypException}
 * carries one; {@link XypException#originOf(Throwable)} works on any throwable.
 */
public enum Origin {
  /** How the client or the call was set up: a bad key, an unknown operation, a bad parameter. */
  CONFIG,
  /** The VPN, the hosts entry, DNS, TLS or a timeout: XYP could not be reached. */
  NETWORK,
  /** XYP or the data provider answering with an error. */
  XYP,
  /** A gap in this SDK. Please report it. */
  SDK
}
