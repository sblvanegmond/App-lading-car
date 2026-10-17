"""
Encryption for the data the sync job publishes.

The coach data ends up on a branch of a public repository, so it is only ever
written encrypted. The format is deliberately boring and mirrored exactly in
coach/js/crypto.js, which decrypts it on the phone with WebCrypto:

    PBKDF2-HMAC-SHA256(passphrase, salt, iterations) -> 256-bit key
    gzip(JSON) -> AES-256-GCM(key, iv) -> ciphertext || tag

Everything is stored as one small JSON envelope with base64 fields.
"""

import base64
import gzip
import hashlib
import json
import os
from datetime import datetime, timezone

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

FORMAT = "coach-enc"
VERSION = 1
ITERATIONS = 600_000
# A short passphrase on a public branch can be brute-forced offline. The app
# generates a random 24-character key by default; this is the floor.
MIN_KEY_LENGTH = 16


class CryptoError(Exception):
    pass


def normalise_key(passphrase):
    key = (passphrase or "").strip()
    if len(key) < MIN_KEY_LENGTH:
        raise CryptoError(
            f"De synchronisatiesleutel moet minstens {MIN_KEY_LENGTH} tekens lang zijn."
        )
    return key


def _derive(passphrase, salt, iterations):
    return hashlib.pbkdf2_hmac("sha256", passphrase.encode("utf-8"), salt, iterations, dklen=32)


def _b64(data):
    return base64.b64encode(data).decode("ascii")


def encrypt_json(value, passphrase, *, iterations=ITERATIONS, now=None):
    key = normalise_key(passphrase)
    salt = os.urandom(16)
    iv = os.urandom(12)
    plaintext = gzip.compress(
        json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8"),
        mtime=0,
    )
    ciphertext = AESGCM(_derive(key, salt, iterations)).encrypt(iv, plaintext, None)
    stamp = (now or datetime.now(timezone.utc)).strftime("%Y-%m-%dT%H:%M:%SZ")
    return {
        "format": FORMAT,
        "v": VERSION,
        # Not secret: lets the app see that new data arrived without paying
        # for the key derivation on every poll.
        "t": stamp,
        "kdf": "PBKDF2-SHA256",
        "iter": iterations,
        "salt": _b64(salt),
        "iv": _b64(iv),
        "z": "gzip",
        "ct": _b64(ciphertext),
    }


def decrypt_json(envelope, passphrase):
    key = normalise_key(passphrase)
    if not isinstance(envelope, dict) or envelope.get("format") != FORMAT:
        raise CryptoError("Dit is geen versleuteld coachbestand.")
    if envelope.get("v") != VERSION:
        raise CryptoError(f"Onbekende versie {envelope.get('v')!r}.")
    try:
        salt = base64.b64decode(envelope["salt"])
        iv = base64.b64decode(envelope["iv"])
        ciphertext = base64.b64decode(envelope["ct"])
        plaintext = AESGCM(_derive(key, salt, int(envelope["iter"]))).decrypt(iv, ciphertext, None)
    except Exception as err:
        raise CryptoError("Ontsleutelen mislukt: verkeerde sleutel of beschadigd bestand.") from err
    if envelope.get("z") == "gzip":
        plaintext = gzip.decompress(plaintext)
    return json.loads(plaintext.decode("utf-8"))


def fingerprint(secret):
    """Short, non-reversible tag to notice that a secret was replaced."""
    return hashlib.sha256((secret or "").strip().encode("utf-8")).hexdigest()[:16]
