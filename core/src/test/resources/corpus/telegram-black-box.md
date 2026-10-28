# Black-box tiers and Telegram

Tier four runs the installed distribution as a subprocess against the real
database and the real model, with only the Telegram wire virtualized. Tier five
drives the author's real Telegram account over MTProto: a development-only
Python driver built on Telethon sends the message, the whole stack reacts, and
the same driver reads the chat back to assert what a human would have seen.
