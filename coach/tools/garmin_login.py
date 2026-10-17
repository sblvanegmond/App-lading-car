#!/usr/bin/env python3
"""
Log in to Garmin Connect once, on your own computer, and print the tokens
for the GitHub secret GARMIN_TOKENS.

    npm run coach:garmin-login
    (or: python3 coach/tools/garmin_login.py)

Needs:  pip install garminconnect curl_cffi   (Python 3.12 or newer)

Logging in from your own machine is what makes two-step verification work:
you type the code here, once. The sync job then only refreshes the tokens and
never needs your password. Treat the printed text like a password: anyone who
has it can read your Garmin account until you sign out everywhere in the
Garmin Connect account settings.
"""

import getpass
import sys
import tempfile
from pathlib import Path


def main():
    try:
        from garminconnect import Garmin
    except ImportError:
        print("Installeer eerst de Garmin-bibliotheek:\n\n    pip install garminconnect curl_cffi\n")
        return 1

    print("Inloggen bij Garmin Connect. Je wachtwoord wordt nergens bewaard.\n")
    email = input("E-mailadres: ").strip()
    password = getpass.getpass("Wachtwoord: ")

    with tempfile.TemporaryDirectory(prefix="garmin-login-") as tmp:
        client = Garmin(email, password, prompt_mfa=lambda: input("Code uit de Garmin-app of e-mail: ").strip())
        try:
            client.login(tmp)
        except Exception as err:  # noqa: BLE001 - the library raises several unrelated types
            print(f"\nInloggen mislukt: {err}")
            return 1
        tokens = client.client.dumps()
        name = getattr(client, "full_name", None) or getattr(client, "display_name", None) or email
        # The token file in tmp disappears with the directory.
        Path(tmp, "garmin_tokens.json").unlink(missing_ok=True)

    print(f"\nIngelogd als {name}.")
    print("\nMaak in GitHub het secret GARMIN_TOKENS aan met precies deze regel als waarde:\n")
    print(tokens)
    print("\nDaarna kun je dit venster sluiten. Zet de tekst nergens anders neer.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
