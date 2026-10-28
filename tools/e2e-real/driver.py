"""Tier 5 driver: your Telegram account (MTProto via Telethon) talks to the bot
and reads the chat back, exactly as you would. The brain is observed THROUGH
the bot (/memory, /brain status): bot and brain live on the Mac, the driver
may run anywhere (local `make e2e-real`, or the nightly workflow in the cloud).

    python3 driver.py selfcheck          env + login sanity, no messages sent
    python3 driver.py p1                 a message lands as bus events (/brain status before/after)
    python3 driver.py p3                 a stated preference is recalled by /memory on a later turn

Env: AB_TG_API_ID, AB_TG_API_HASH, AB_TG_SESSION (from login.py), AB_TG_BOT
(the bot's @username), optional AB_TG_TIMEOUT_S (default 300).
Exit 0 = scenario asserted end to end; the JSON report goes to stdout.
"""
import asyncio
import json
import os
import re
import sys
import time
import uuid

from telethon import TelegramClient
from telethon.sessions import StringSession

TIMEOUT_S = int(os.environ.get("AB_TG_TIMEOUT_S", "300"))
POLL_S = 3


class Driver:
    def __init__(self) -> None:
        self.client = TelegramClient(StringSession(os.environ["AB_TG_SESSION"]), int(os.environ["AB_TG_API_ID"]), os.environ["AB_TG_API_HASH"])
        self.bot = os.environ["AB_TG_BOT"]
        self.report = {"scenario": None, "steps": []}

    def step(self, name, **data):
        self.report["steps"].append({"name": name, **data})
        print(json.dumps({"step": name, **data}, ensure_ascii=False), file=sys.stderr)

    async def send_and_wait(self, text, want=None, timeout=TIMEOUT_S):
        """Sends `text` and waits for bot messages newer than it; with `want`
        (regex) waits until one matches. Returns the matching/last reply text."""
        sent = await self.client.send_message(self.bot, text)
        deadline = time.time() + timeout
        seen = []
        while time.time() < deadline:
            msgs = await self.client.get_messages(self.bot, min_id=sent.id, limit=50)
            replies = sorted((m for m in msgs if not m.out and m.message), key=lambda m: m.id)
            seen = [m.message for m in replies]
            if want is None and seen:
                return seen[-1]
            for body in seen:
                if want and re.search(want, body, re.I | re.S):
                    return body
            await asyncio.sleep(POLL_S)
        raise TimeoutError("no reply matching %r within %ds; saw %d message(s): %s" % (want, timeout, len(seen), [s[:80] for s in seen]))

    async def selfcheck(self):
        me = await self.client.get_me()
        entity = await self.client.get_entity(self.bot)
        self.step("login", user=me.username or me.first_name, bot=getattr(entity, "username", None))
        return True

    async def p1(self):
        before = await self.send_and_wait("/brain status", want=r"bus:\s*(\d+) events")
        n0 = int(re.search(r"bus:\s*(\d+) events", before, re.I).group(1))
        self.step("status_before", events=n0)
        token = "e2e-" + uuid.uuid4().hex[:8]
        reply = await self.send_and_wait("Reply with exactly this token and nothing else: %s" % token, want=re.escape(token))
        self.step("turn_reply", preview=reply[:120])
        for _ in range(20):
            after = await self.send_and_wait("/brain status", want=r"bus:\s*(\d+) events")
            n1 = int(re.search(r"bus:\s*(\d+) events", after, re.I).group(1))
            if n1 > n0:
                self.step("status_after", events=n1, delta=n1 - n0)
                return True
            await asyncio.sleep(POLL_S)
        raise AssertionError("bus events did not grow after a real turn (%d)" % n0)

    async def p3(self):
        marker = "zebra-" + uuid.uuid4().hex[:6]
        pref = "Remember this preference for later: when I say %s I mean the Amazon Corretto 25 JDK installed via brew cask." % marker
        reply = await self.send_and_wait(pref)
        self.step("preference_turn", preview=reply[:120])
        deadline = time.time() + TIMEOUT_S
        while time.time() < deadline:
            mem = await self.send_and_wait("/memory %s" % marker)
            if marker in mem or "Corretto" in mem:
                self.step("memory_recalled", preview=mem[:200])
                follow = await self.send_and_wait("One line: what do I mean when I say %s?" % marker)
                self.step("second_turn", preview=follow[:160], mentions_corretto=("corretto" in follow.lower()))
                return True
            await asyncio.sleep(10)
        raise AssertionError("the preference never showed up in /memory within %ds" % TIMEOUT_S)

    async def run(self, scenario):
        self.report["scenario"] = scenario
        async with self.client:
            ok = await getattr(self, scenario)()
        self.report["ok"] = bool(ok)
        return ok


def main(argv):
    scenario = argv[0] if argv else "selfcheck"
    if scenario not in ("selfcheck", "p1", "p3"):
        print(__doc__, file=sys.stderr)
        return 2
    driver = Driver()
    try:
        ok = asyncio.run(driver.run(scenario))
    except Exception as e:  # noqa: BLE001 - the report must say what broke
        driver.report["ok"] = False
        driver.report["error"] = "%s: %s" % (type(e).__name__, e)
        ok = False
    print(json.dumps(driver.report, ensure_ascii=False, indent=2))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
