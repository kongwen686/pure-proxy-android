#!/usr/bin/env python3
"""Restore pinned public native inputs without storing binaries in Git."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import tempfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()


def verified_input(local, url, expected, cache_name):
    path = local or ROOT / ".cache" / "native-downloads" / cache_name
    if path.is_file() and digest(path) == expected:
        return path
    if local:
        raise ValueError("Local input missing or checksum mismatch: " + local.name)
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(dir=path.parent, prefix="download-")
    try:
        request = urllib.request.Request(url, headers={"User-Agent": "PureProxy-native-setup"})
        with os.fdopen(descriptor, "wb") as output:
            with urllib.request.urlopen(request, timeout=120) as response:
                while True:
                    block = response.read(1024 * 1024)
                    if not block:
                        break
                    output.write(block)
        temporary = Path(temporary)
        if digest(temporary) != expected:
            raise ValueError("Downloaded input checksum mismatch: " + cache_name)
        temporary.replace(path)
        return path
    finally:
        Path(temporary).unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk-file", type=Path, help="Reuse a checksum-verified upstream universal APK")
    parser.add_argument("--aar-file", type=Path, help="Reuse a checksum-verified upstream AAR")
    args = parser.parse_args()
    provenance = json.loads((ROOT / "NATIVE_PROVENANCE.json").read_text())
    apk = verified_input(args.apk_file, provenance["upstream_apk_url"],
                         provenance["apk_sha256"], "upstream-universal.apk")
    aar = verified_input(args.aar_file, provenance["xray_aar_url"],
                         provenance["xray_aar_sha256"], "libv2ray.aar")

    # Validate every member before modifying any build input. Never extract arbitrary archive paths.
    members = []
    with zipfile.ZipFile(apk) as archive:
        for entry in provenance["native_extraction"]:
            relative = Path(entry["path"])
            name = "lib/" + "/".join(relative.parts[-2:])
            content = archive.read(name)
            if hashlib.sha256(content).hexdigest() != entry["sha256"]:
                raise ValueError("Native member checksum mismatch: " + name)
            members.append((relative, content))
        for entry in provenance["routing_assets"]:
            relative = Path(entry["path"])
            name = "assets/" + relative.name
            content = archive.read(name)
            if hashlib.sha256(content).hexdigest() != entry["sha256"]:
                raise ValueError("Routing member checksum mismatch: " + name)
            members.append((relative, content))
    members.append((Path("V2rayNG/app/libs/libv2ray.aar"), aar.read_bytes()))
    allowed = ("V2rayNG/app/libs/", "V2rayNG/app/src/main/assets/")
    for relative, _ in members:
        if relative.is_absolute() or ".." in relative.parts or not relative.as_posix().startswith(allowed):
            raise ValueError("Unexpected output path in provenance")
    for relative, content in members:
        target = ROOT / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        descriptor, temporary = tempfile.mkstemp(dir=target.parent, prefix="native-")
        try:
            with os.fdopen(descriptor, "wb") as output:
                output.write(content)
            Path(temporary).replace(target)
        finally:
            Path(temporary).unlink(missing_ok=True)
    print("Prepared", len(members), "checksum-verified public native/routing inputs.")


if __name__ == "__main__":
    main()
