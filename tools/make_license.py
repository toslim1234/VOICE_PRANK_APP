#!/usr/bin/env python3
"""
Issue / manage signed licence keys for Voice Prank Pro (RSA-2048, SHA-256).

Key format (what Premium.parseLicenseKey / verifyLocal expect):

    <base64url(payload)>.<base64url(signature)>
    payload   = expiryMillis|premiumUntilMillis|deviceId
    signature = RSA PKCS#1 v1.5 / SHA-256 over the ASCII bytes of <base64url(payload)>

The PRIVATE key never goes into the app. Only the public key (base64 DER) is pasted into
Premium.LICENCE_PUBLIC_KEY.

Setup (once):
    python3 tools/make_license.py gen-keys --out license_private.pem
    -> prints the value for Premium.LICENCE_PUBLIC_KEY      (keep the .pem secret, git-ignored)

Issue a key bound to one device (device id is shown in the app's Premium screen):
    python3 tools/make_license.py issue --key license_private.pem --days 365 --device <id>

Unbound keys can be shared between phones; they need an explicit flag:
    python3 tools/make_license.py issue --key license_private.pem --days 30 --allow-unbound

Self-check a key:
    python3 tools/make_license.py verify --key license_private.pem <licence-key>
"""
import argparse
import base64
import sys
import time

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa


def b64u(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).decode().rstrip("=")


def b64u_dec(txt: str) -> bytes:
    return base64.urlsafe_b64decode(txt + "=" * (-len(txt) % 4))


def load_private(path):
    with open(path, "rb") as f:
        return serialization.load_pem_private_key(f.read(), password=None)


def public_b64(priv) -> str:
    der = priv.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo
    )
    return base64.b64encode(der).decode()


def cmd_gen(a):
    priv = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    pem = priv.private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8,
        serialization.NoEncryption(),
    )
    with open(a.out, "wb") as f:
        f.write(pem)
    try:
        import os
        os.chmod(a.out, 0o600)
    except OSError:
        pass
    print(f"private key written to {a.out}  (KEEP SECRET, do not commit)\n")
    print("Premium.LICENCE_PUBLIC_KEY =")
    print(public_b64(priv))


def make_key(priv, days: int, device: str) -> str:
    expiry = int(time.time() * 1000) + days * 24 * 60 * 60 * 1000
    encoded = b64u(f"{expiry}|{expiry}|{device}".encode())
    sig = priv.sign(encoded.encode(), padding.PKCS1v15(), hashes.SHA256())
    return f"{encoded}.{b64u(sig)}"


def cmd_issue(a):
    if not a.device and not a.allow_unbound:
        sys.exit("error: pass --device <id>, or --allow-unbound to issue a shareable key")
    print(make_key(load_private(a.key), a.days, a.device or ""))


def cmd_verify(a):
    pub = load_private(a.key).public_key()
    enc, sig = a.licence.split(".")
    pub.verify(b64u_dec(sig), enc.encode(), padding.PKCS1v15(), hashes.SHA256())
    print("signature OK, payload:", b64u_dec(enc).decode())


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawTextHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    g = sub.add_parser("gen-keys"); g.add_argument("--out", default="license_private.pem"); g.set_defaults(f=cmd_gen)
    i = sub.add_parser("issue")
    i.add_argument("--key", required=True); i.add_argument("--days", type=int, default=365)
    i.add_argument("--device", default=""); i.add_argument("--allow-unbound", action="store_true")
    i.set_defaults(f=cmd_issue)
    v = sub.add_parser("verify"); v.add_argument("--key", required=True); v.add_argument("licence"); v.set_defaults(f=cmd_verify)
    a = ap.parse_args(); a.f(a)


if __name__ == "__main__":
    main()
