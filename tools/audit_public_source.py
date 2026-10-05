#!/usr/bin/env python3
"""Fail publication/CI on common secrets and personal build files in tracked source."""
import json
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
PATTERNS = {
    "private key": re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----"),
    "GitHub credential": re.compile(r"\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})"),
    "AWS access key": re.compile(r"\b(?:AKIA|ASIA)[A-Z0-9]{16}\b"),
    "personal absolute path": re.compile(r"(?:/Users/|/home/|[A-Za-z]:\\Users\\)[A-Za-z0-9_.-]+[/\\]"),
    "credential in URL": re.compile(r"https?://[^\s\"<>]*[?&](?:token|password|secret|access_token|api_key)=[^\s\"&<>]+", re.I),
    "embedded proxy URI": re.compile(r"(?:vless|vmess|ss|trojan|socks|wireguard|hysteria2)://[A-Za-z0-9+/]{12,}", re.I),
}
BLOCKED_SUFFIXES = {".jks", ".keystore", ".pem", ".key", ".p12", ".pfx", ".apk", ".aar", ".so", ".dat", ".idsig", ".log"}
BLOCKED_NAMES = {"local.properties", "signing.properties", "AGENTS.md", "CLAUDE.md", "GEMINI.md"}


def main():
    tracked = subprocess.check_output(["git", "ls-files", "--stage", "-z"], cwd=ROOT).decode().split("\0")
    problems = []
    count = 0
    template_data = None
    for entry in filter(None, tracked):
        metadata, relative = entry.split("\t", 1)
        if metadata.split()[0] == "160000":
            continue  # Fixed upstream submodules may be uninitialized in a shallow checkout.
        path = ROOT / relative
        count += 1
        if path.name in BLOCKED_NAMES or path.suffix.lower() in BLOCKED_SUFFIXES or path.name.startswith(".env"):
            problems.append((relative, "excluded local/binary file"))
        # Audit index blobs, not working copies: unstaged cleanup must never hide a staged secret.
        data = subprocess.check_output(["git", "cat-file", "blob", metadata.split()[1]], cwd=ROOT)
        if relative == "V2rayNG/app/src/main/assets/v2ray_config.json":
            template_data = data
        if b"\0" in data:
            continue
        text = data.decode("utf-8", errors="replace")
        for label, pattern in PATTERNS.items():
            if pattern.search(text):
                problems.append((relative, label))
    if template_data is None:
        print("Required configuration template missing from Git index")
        return 1
    template = json.loads(template_data)
    settings = template["outbounds"][0]["settings"]
    if any(item.get("address") != "proxy.example.invalid" for item in settings["vnext"] + settings["servers"]):
        problems.append(("v2ray_config.json", "template has a non-placeholder endpoint"))
    if settings["vnext"][0]["users"][0]["id"] != "00000000-0000-4000-8000-000000000001":
        problems.append(("v2ray_config.json", "template has a non-demo UUID"))
    if settings["servers"][0]["password"] != "example-password-not-a-secret":
        problems.append(("v2ray_config.json", "template has a non-demo password"))
    if problems:
        for relative, label in problems:
            print(relative + ": " + label)  # Deliberately never print matching secret values.
        return 1
    print("Public source audit passed:", count, "tracked files; no findings in configured checks.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
