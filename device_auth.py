#!/usr/bin/env python3
"""Create an independent Codex device session without printing its tokens."""

import argparse
import base64
import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request

CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
AUTH = "https://auth.openai.com"


def request(url, body, content_type="application/json"):
    data = json.dumps(body).encode() if content_type == "application/json" else urllib.parse.urlencode(body).encode()
    req = urllib.request.Request(
        url,
        data=data,
        headers={"Content-Type": content_type, "User-Agent": "codex_cli_rs/0.152.0"},
    )
    with urllib.request.urlopen(req, timeout=30) as response:
        return json.load(response)


def find_account_id(value):
    if isinstance(value, dict):
        if isinstance(value.get("chatgpt_account_id"), str):
            return value["chatgpt_account_id"]
        for child in value.values():
            found = find_account_id(child)
            if found:
                return found
    return None


def account_id(id_token):
    payload = id_token.split(".")[1]
    claims = json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))
    return find_account_id(claims)


def login(output):
    device = request(f"{AUTH}/api/accounts/deviceauth/usercode", {"client_id": CLIENT_ID})
    code = device.get("user_code") or device["usercode"]
    print(f"OPEN {AUTH}/codex/device", flush=True)
    print(f"CODE {code}", flush=True)

    deadline = time.monotonic() + 15 * 60
    while time.monotonic() < deadline:
        try:
            exchange = request(
                f"{AUTH}/api/accounts/deviceauth/token",
                {"device_auth_id": device["device_auth_id"], "user_code": code},
            )
            break
        except urllib.error.HTTPError as error:
            if error.code not in (403, 404):
                raise
            time.sleep(int(device.get("interval", 5)))
    else:
        raise TimeoutError("device authorization timed out")

    tokens = request(
        f"{AUTH}/oauth/token",
        {
            "grant_type": "authorization_code",
            "code": exchange["authorization_code"],
            "redirect_uri": f"{AUTH}/deviceauth/callback",
            "client_id": CLIENT_ID,
            "code_verifier": exchange["code_verifier"],
        },
        "application/x-www-form-urlencoded",
    )
    result = {
        "access_token": tokens["access_token"],
        "refresh_token": tokens["refresh_token"],
        "account_id": account_id(tokens["id_token"]),
    }
    if not result["account_id"]:
        raise ValueError("account ID missing from login token")
    fd = os.open(output, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as file:
        json.dump(result, file)
    print("AUTHORIZED", flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", default="/tmp/codex-meter-device-auth.json")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        assert find_account_id({"nested": {"chatgpt_account_id": "account-1"}}) == "account-1"
        print("ok")
    else:
        login(args.output)


if __name__ == "__main__":
    main()
