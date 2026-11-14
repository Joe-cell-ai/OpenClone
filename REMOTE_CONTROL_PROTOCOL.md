# OpenClone Remote Control Protocol v1

Remote Control is disabled until the user starts a session in Extensions. The session runs a TLS 1.2 server on TCP port 38473 and exposes only the endpoints below.

## Pairing

The app creates an `openclone://pair` URI containing:
- `host` and `port`: the device's selected IPv4 address and listening port.
- `fingerprint`: lowercase SHA-256 fingerprint of the device's Android Keystore TLS certificate (DER).
- `secret`: 32-byte random HMAC key encoded as unpadded Base64URL.

Treat the complete URI as a password. Share it only with a client you control. The server certificate is self-signed; clients MUST compare the peer certificate's SHA-256 fingerprint to the value in the pairing URI and abort on mismatch. Do not use a trust-all TLS manager.

The AES-GCM encrypted copy of the pairing key is stored under an Android Keystore AES key. Stopping the service deletes the pairing key and its Keystore encryption key. A new session requires new pairing.

## Request authentication

Use TLS 1.2 and send one HTTP/1.1 request per connection. Request bodies and query strings are not supported.

Required headers:
- `X-OpenClone-Timestamp`: Unix epoch milliseconds.
- `X-OpenClone-Nonce`: fresh random Base64URL nonce, 16–80 characters.
- `X-OpenClone-Signature`: unpadded Base64URL HMAC-SHA256.

The HMAC input is the UTF-8 bytes of four lines, joined with LF and no trailing LF:

```text
METHOD
PATH
TIMESTAMP
NONCE
```

The key is the 32-byte decoded pairing secret. The server accepts timestamps within ±60 seconds and rejects a nonce reused during the running session. TLS protects request integrity and confidentiality; the HMAC additionally proves possession of the pairing secret.

## Endpoints

### Read service status

`GET /v1/status`

Returns a small JSON status object after authentication.

### Launch an OpenClone-created clone

`POST /v1/launch/{packageName}`

The package must currently be detected as a clone created by OpenClone and have a launch activity. The server does not launch arbitrary installed packages.

All other paths/methods are rejected. There is no shell, file browser, package installer, clone-private-data access, or arbitrary intent endpoint in protocol v1.

## Operational notes

- The Android foreground notification remains visible while the server is active.
- Stop the session from the app or its notification to close the socket and revoke the pairing credential.
- The address may be unreachable on carrier networks, guest Wi-Fi or networks that isolate clients. Use a trusted LAN.
- This is a local-network protocol, not an internet relay. Do not expose port 38473 through router port-forwarding.

## Companion client

OpenClone now includes a companion mode in Extensions. Paste the pairing URI on the device that will control the host, then use Check connection or Launch clone. The client validates the URI version and fields, pins the TLS peer to the supplied certificate SHA-256 fingerprint, and signs each request with a fresh HMAC-SHA256 nonce and timestamp. The client exposes only the v1 status and clone-launch operations.

Pair over a trusted local Wi-Fi/Ethernet network. The pairing URI contains a bearer secret; never send it through public chat, screenshots or untrusted QR scanners. The service is not an internet relay and must not be exposed through router port forwarding.
