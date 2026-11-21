"""Prepare verified Android release assets and check their GitHub delivery."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time
from urllib.error import HTTPError
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[2]
REPO = "yuchen0x1/LUMORA-Camera"
PACKAGE = "com.lumora.camera"
UPDATE = "android-update.json"
SUMS = "SHA256SUMS.txt"
VERSION_PART = r"(?:0|[1-9][0-9]{0,8})"
VERSION = rf"{VERSION_PART}\.{VERSION_PART}\.{VERSION_PART}"
MAX_VERSION_CODE = 2_100_000_000
MAX_SDK = 2_147_483_647
MAX_APK_BYTES = 512 * 1024 * 1024
MAX_JSON_BYTES = 8_192


def run(*command):
    return subprocess.check_output(command, text=True, encoding="utf-8")


def require(condition, message):
    if not condition:
        raise SystemExit(message)


def integer_in_range(value, minimum, maximum):
    # bool subclasses int in Python, but is not an Android JSON integer.
    return type(value) is int and minimum <= value <= maximum


def valid_asset_size(value, maximum):
    # GitHub asset sizes, unlike metadata integers, may be integral JSON floats.
    # The bounds also reject NaN and infinities before conversion to int.
    return type(value) in (int, float) and 1 <= value <= maximum and value == int(value)


def digest(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def version(tag):
    values = dict(line.split("=", 1) for line in
                  (ROOT / "version.properties").read_text().splitlines())
    name, code, minimum = values["versionName"], int(values["versionCode"]), int(values["minSdk"])
    require(re.fullmatch(VERSION, name) and tag == f"v{name}", "Tag and version.properties must agree")
    require(integer_in_range(code, 1, MAX_VERSION_CODE) and integer_in_range(minimum, 29, MAX_SDK),
            "Invalid Android version or minimum SDK")
    return name, code, minimum


def prepare(args):
    name, code, minimum = version(args.tag)
    expected = args.certificate.lower()
    require(re.fullmatch(r"[a-f0-9]{64}", expected), "Configure ANDROID_SIGNER_SHA256")
    require(integer_in_range(args.apk.stat().st_size, 1, MAX_APK_BYTES), "Invalid APK size")
    tools = Path(os.environ["ANDROID_HOME"]) / "build-tools" / "36.0.0"
    windows = os.name == "nt"
    signer = str(tools / ("apksigner.bat" if windows else "apksigner"))
    aapt = str(tools / ("aapt.exe" if windows else "aapt"))
    sig = run(signer, "verify", "--verbose", "--print-certs", str(args.apk))
    certs = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([a-f0-9]{64})$", sig, re.M)
    require(certs == [expected] and "CN=Android Debug" not in sig, "Unexpected Android release signer")
    # apksigner writes APK Signature Scheme v3 instead of v2 when v3 is enabled
    # (verified across build-tools 34/35/36), and every Android release from API
    # 28 upward verifies v3 directly. With minSdk 29, requiring v3 covers all
    # supported devices; demanding v2 too would reject every signed artifact.
    require(any(line.startswith("Verified using v3 scheme ") and line.endswith(": true")
                for line in sig.splitlines()), "APK must have a valid v3 signature")
    badging = run(aapt, "dump", "badging", str(args.apk))
    require(f"package: name='{PACKAGE}' versionCode='{code}' versionName='{name}'" in badging,
            "Unexpected APK identity or version")
    require(f"sdkVersion:'{minimum}'" in badging and "application-debuggable" not in badging,
            "Incorrect API level or debuggable APK")
    release_notes = (ROOT / "CHANGELOG.md").read_text(encoding="utf-8")
    entry = re.search(rf"(?ms)^## {re.escape(name)} - [^\n]+\n(.+?)(?=^## |\Z)", release_notes)
    require(entry and entry[1].strip(), "Add the current changelog entry before releasing")

    args.output.mkdir(parents=True, exist_ok=True)
    filename = f"LUMORA-Camera-{name}-Android-universal.apk"
    apk = args.output / filename
    apk.write_bytes(args.apk.read_bytes())
    apk_digest = digest(apk)
    metadata = {
        "schema": 1, "platform": "android", "architecture": "universal", "channel": "stable",
        "packageName": PACKAGE, "versionName": name, "versionCode": code, "minSdk": minimum,
        "asset": filename, "size": apk.stat().st_size, "sha256": apk_digest,
        "signerSha256": expected,
    }
    update = args.output / UPDATE
    update.write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    (args.output / SUMS).write_text(
        f"{apk_digest}  {filename}\n{digest(update)}  {UPDATE}\n", encoding="utf-8")
    (args.output / "notes.md").write_text(entry[1].strip() + "\n", encoding="utf-8")
    print(f"Verified {filename}, Android API {minimum}+, versionCode {code}, signer {expected}")


def github_json(endpoint, token=None):
    headers = {"Accept": "application/vnd.github+json", "User-Agent": "LUMORA-Camera-release-check"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    url = Request(f"https://api.github.com/repos/{REPO}/{endpoint}", headers=headers)
    for attempt in range(3):
        try:
            with urlopen(url, timeout=30) as response:
                return json.load(response)
        except HTTPError as error:
            # Shared runner IPs often exhaust the unauthenticated API budget.
            if error.code not in (403, 429) or attempt == 2:
                raise
            time.sleep((attempt + 1) * 5)


def check_draft(args):
    tag, folder = args.tag, args.assets
    releases = github_json("releases?per_page=30", os.environ["GH_TOKEN"])
    release = next((r for r in releases if r["tag_name"] == tag), None)
    require(release and release["draft"] and not release["prerelease"], "Expected an unpublished stable release")
    expected = {p.name: f"sha256:{digest(p)}" for p in folder.iterdir() if p.name != "notes.md"}
    actual = {a["name"]: a["digest"] for a in release["assets"] if a["state"] == "uploaded"}
    require(actual == expected and len(actual) == 3, "Draft assets are incomplete or their digests differ")


def check_public(args):
    route = "releases/latest" if not args.tag else f"releases/tags/{args.tag}"
    try:
        release = github_json(route, os.environ.get("GH_TOKEN"))
    except HTTPError as error:
        if error.code == 404 and not args.tag:
            print("No public stable release yet")
            return
        raise
    tag = release["tag_name"]
    require(re.fullmatch(f"v{VERSION}", tag) and not release["draft"] and not release["prerelease"],
            "Unexpected stable release")
    name = tag[1:]
    filename = f"LUMORA-Camera-{name}-Android-universal.apk"
    assets = {a["name"]: a for a in release["assets"] if a["state"] == "uploaded"}
    for asset, maximum in ((filename, MAX_APK_BYTES), (UPDATE, MAX_JSON_BYTES), (SUMS, MAX_JSON_BYTES)):
        require(asset in assets and valid_asset_size(assets[asset].get("size"), maximum),
                f"Invalid asset size for {asset}")

    def open_asset(asset):
        url = f"https://github.com/{REPO}/releases/download/{tag}/{asset}"
        require(assets[asset]["browser_download_url"] == url, f"Unrecognized URL for {asset}")
        return urlopen(Request(url, headers={"User-Agent": "LUMORA-Camera-release-check"}), timeout=60)

    def read_small(asset):
        with open_asset(asset) as stream:
            data = stream.read(MAX_JSON_BYTES + 1)
        require(1 <= len(data) <= MAX_JSON_BYTES and len(data) == assets[asset]["size"] and
                assets[asset]["digest"] == f"sha256:{hashlib.sha256(data).hexdigest()}", f"Invalid {asset}")
        return data

    metadata = json.loads(read_small(UPDATE))
    require(isinstance(metadata, dict) and integer_in_range(metadata.get("schema"), 1, 1) and
            metadata.get("packageName") == PACKAGE and metadata.get("asset") == filename and
            metadata.get("versionName") == name and metadata.get("platform") == "android" and
            metadata.get("architecture") == "universal" and metadata.get("channel") == "stable" and
            integer_in_range(metadata.get("versionCode"), 1, MAX_VERSION_CODE) and
            integer_in_range(metadata.get("minSdk"), 29, MAX_SDK) and
            integer_in_range(metadata.get("size"), 1, MAX_APK_BYTES) and
            metadata["size"] == assets[filename]["size"] and
            isinstance(metadata.get("sha256"), str) and re.fullmatch("[a-f0-9]{64}", metadata["sha256"]) and
            isinstance(metadata.get("signerSha256"), str) and
            re.fullmatch("[a-f0-9]{64}", metadata["signerSha256"]), "Invalid public update metadata")
    with open_asset(filename) as stream:
        apk_digest = hashlib.file_digest(stream, "sha256").hexdigest()
    require(assets[filename]["digest"] == f"sha256:{apk_digest}" and metadata["sha256"] == apk_digest,
            "Public APK digest mismatch")
    require(read_small(SUMS).decode().splitlines() == [
        f"{apk_digest}  {filename}", f"{assets[UPDATE]['digest'].removeprefix('sha256:')}  {UPDATE}",
    ], "Incorrect SHA256SUMS")
    print(f"Verified public release {tag}: metadata, checksums and APK download")
    return metadata


def verify_new_tag(args):
    name, code, _ = version(args.tag)
    latest = check_public(argparse.Namespace(tag=None))
    if latest:
        numeric = lambda value: tuple(map(int, value.split(".")))
        require(numeric(name) > numeric(latest["versionName"]) and code > latest["versionCode"],
                "Stable versionName and versionCode must increase")
        require(latest["signerSha256"] == args.certificate.lower(), "Stable release signing identity changed")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    check = commands.add_parser("version")
    check.add_argument("tag")
    new = commands.add_parser("new-tag")
    new.add_argument("tag")
    new.add_argument("--certificate", required=True)
    package = commands.add_parser("prepare")
    package.add_argument("tag")
    package.add_argument("apk", type=Path)
    package.add_argument("output", type=Path)
    package.add_argument("--certificate", required=True)
    draft = commands.add_parser("draft")
    draft.add_argument("tag")
    draft.add_argument("assets", type=Path)
    public = commands.add_parser("public")
    public.add_argument("--tag")
    args = parser.parse_args()
    if args.command == "version":
        print(*version(args.tag))
    elif args.command == "new-tag":
        verify_new_tag(args)
    elif args.command == "prepare":
        prepare(args)
    elif args.command == "draft":
        check_draft(args)
    else:
        check_public(args)


if __name__ == "__main__":
    main()
