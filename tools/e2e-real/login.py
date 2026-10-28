"""One-time: create the StringSession the tier-5 driver uses to act as YOUR account.

    AB_TG_API_ID=... AB_TG_API_HASH=... python3 tools/e2e-real/login.py

Prompts for your phone and the code Telegram sends; prints the session string.
Store it as AB_TG_SESSION (local shell) and as a repository secret for the
nightly workflow. It grants full access to the account: scope it to a
dedicated Telegram app (my.telegram.org), rotate it like a password.
"""
import os

from telethon import TelegramClient
from telethon.sessions import StringSession


def main() -> None:
    api_id = int(os.environ["AB_TG_API_ID"])
    api_hash = os.environ["AB_TG_API_HASH"]
    with TelegramClient(StringSession(), api_id, api_hash) as client:
        print("\nAB_TG_SESSION=" + client.session.save())


if __name__ == "__main__":
    main()
