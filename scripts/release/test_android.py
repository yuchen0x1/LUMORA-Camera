"""Offline release-script tests; run with Python 3.11+ and -I."""

import argparse
from contextlib import redirect_stdout
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).with_name("android.py")
spec = importlib.util.spec_from_file_location("android_release", SCRIPT)
android = importlib.util.module_from_spec(spec)
with patch.object(sys, "dont_write_bytecode", True):
    spec.loader.exec_module(android)

NAME = "2.1.5"
TAG = f"v{NAME}"
CERTIFICATE = "c" * 64
APK_BYTES = b"x"  # A protocol fixture, not a signed APK.
APK_DIGEST = hashlib.sha256(APK_BYTES).hexdigest()
MAX_APK_BYTES = 512 * 1024 * 1024
MAX_VERSION_CODE = 2_100_000_000
MAX_SDK = 2_147_483_647
UNSET = object()


def metadata(name=NAME):
    return {
        "schema": 1, "platform": "android", "architecture": "universal", "channel": "stable",
        "packageName": android.PACKAGE, "versionName": name, "versionCode": 9, "minSdk": 29,
        "asset": f"LUMORA-Camera-{name}-Android-universal.apk", "size": len(APK_BYTES),
        "sha256": APK_DIGEST, "signerSha256": CERTIFICATE,
    }


class PublicMetadataTest(unittest.TestCase):
    def check_public(self, manifest=UNSET, *, name=NAME, apk_size=UNSET, metadata_size=UNSET):
        if manifest is UNSET:
            manifest = metadata(name)
        filename = f"LUMORA-Camera-{name}-Android-universal.apk"
        update = json.dumps(manifest, indent=2).encode("utf-8") + b"\n"
        update_digest = hashlib.sha256(update).hexdigest()
        sums = f"{APK_DIGEST}  {filename}\n{update_digest}  {android.UPDATE}\n".encode("utf-8")
        payloads = {filename: APK_BYTES, android.UPDATE: update, android.SUMS: sums}
        root = f"https://github.com/{android.REPO}/releases/download/v{name}"
        assets = []
        for asset, data in payloads.items():
            size = len(data)
            if asset == filename and apk_size is not UNSET:
                size = apk_size
            if asset == android.UPDATE and metadata_size is not UNSET:
                size = metadata_size
            assets.append({
                "name": asset, "state": "uploaded", "size": size,
                "digest": f"sha256:{hashlib.sha256(data).hexdigest()}",
                "browser_download_url": f"{root}/{asset}",
            })
        release = {"tag_name": f"v{name}", "draft": False, "prerelease": False, "assets": assets}
        downloads = {f"{root}/{asset}": data for asset, data in payloads.items()}

        def open_asset(request, timeout):
            return io.BytesIO(downloads[request.full_url])

        with patch.object(android, "github_json", return_value=release), \
                patch.object(android, "urlopen", side_effect=open_asset), \
                patch.dict(android.os.environ, {"GH_TOKEN": ""}), redirect_stdout(io.StringIO()):
            return android.check_public(argparse.Namespace(tag=f"v{name}"))

    def test_generated_integer_metadata_round_trips(self):
        expected = metadata()
        self.assertEqual(expected, self.check_public(expected))

    def test_schema_requires_integer_one_not_boolean_or_float(self):
        for value in (True, False, 1.0, "1", None, 0, -1, 2):
            with self.subTest(value=value):
                manifest = metadata()
                manifest["schema"] = value
                with self.assertRaises(SystemExit):
                    self.check_public(manifest)

    def test_version_code_requires_a_bounded_positive_integer(self):
        for value in (True, False, 9.0, "9", None, 0, -1, MAX_VERSION_CODE + 1):
            with self.subTest(value=value):
                manifest = metadata()
                manifest["versionCode"] = value
                with self.assertRaises(SystemExit):
                    self.check_public(manifest)

    def test_min_sdk_requires_an_android_integer_at_least_29(self):
        for value in (True, False, 29.0, "29", None, 0, 28, MAX_SDK + 1):
            with self.subTest(value=value):
                manifest = metadata()
                manifest["minSdk"] = value
                with self.assertRaises(SystemExit):
                    self.check_public(manifest)

    def test_size_requires_a_bounded_positive_integer(self):
        for value in (True, False, 1.0, "1", None, 0, -1, MAX_APK_BYTES + 1):
            with self.subTest(value=value):
                manifest = metadata()
                manifest["size"] = value
                declared = value if type(value) is int else len(APK_BYTES)
                with self.assertRaises(SystemExit):
                    self.check_public(manifest, apk_size=declared)

    def test_numeric_fields_reject_nonfinite_values(self):
        for key in ("schema", "versionCode", "minSdk", "size"):
            for value in (float("nan"), float("inf"), -float("inf")):
                with self.subTest(key=key, value=value):
                    manifest = metadata()
                    manifest[key] = value
                    with self.assertRaises(SystemExit):
                        self.check_public(manifest)

    def test_version_code_and_sdk_upper_bounds_remain_valid(self):
        manifest = metadata()
        manifest.update(versionCode=MAX_VERSION_CODE, minSdk=MAX_SDK)
        self.assertEqual(manifest, self.check_public(manifest))

    def test_exact_stable_identity_fields_must_match(self):
        changes = {
            "platform": "ios", "architecture": "arm64", "channel": "beta",
            "packageName": "com.example.other", "versionName": "2.1.4", "asset": "other.apk",
            "sha256": "a" * 64, "size": 2,
        }
        for key, value in changes.items():
            with self.subTest(key=key):
                manifest = metadata()
                manifest[key] = value
                with self.assertRaises(SystemExit):
                    self.check_public(manifest)

    def test_signer_requires_a_canonical_sha256_string(self):
        for value in ("", "c" * 63, "C" * 64, "g" * 64, f"sha256:{CERTIFICATE}"):
            with self.subTest(value=value):
                manifest = metadata()
                manifest["signerSha256"] = value
                with self.assertRaises(SystemExit):
                    self.check_public(manifest)

    def test_apk_asset_size_cannot_be_a_boolean(self):
        with self.assertRaises(SystemExit):
            self.check_public(apk_size=True)

    def test_metadata_asset_size_requires_a_finite_integral_bounded_number(self):
        for value in (True, False, "439", None, 0, -1, 1.5, 8193, float("nan"), float("inf")):
            with self.subTest(value=value):
                with self.assertRaises(SystemExit):
                    self.check_public(metadata_size=value)

    def test_metadata_asset_size_must_match_downloaded_bytes(self):
        with self.assertRaises(SystemExit):
            self.check_public(metadata_size=1)

    def test_integral_float_asset_sizes_remain_compatible_with_client(self):
        size = len(json.dumps(metadata(), indent=2).encode("utf-8")) + 1
        self.assertEqual(metadata(), self.check_public(apk_size=1.0, metadata_size=float(size)))

    def test_public_version_components_have_the_client_nine_digit_limit(self):
        for name in ("1000000000.0.0", "0.1000000000.0", "0.0.1000000000"):
            with self.subTest(name=name):
                with self.assertRaises(SystemExit):
                    self.check_public(name=name)


class PreparedMetadataTest(unittest.TestCase):
    def setUp(self):
        # All temporary files stay in this worktree and are removed by addCleanup.
        folder = tempfile.TemporaryDirectory(prefix="android-release-test-", dir=SCRIPT.parent)
        self.addCleanup(folder.cleanup)
        self.root = Path(folder.name)
        self.write_version()
        (self.root / "CHANGELOG.md").write_text(f"## {NAME} - 2026-11-21\n\nTest notes.\n", encoding="utf-8")
        self.apk = self.root / "input.apk"
        self.apk.write_bytes(APK_BYTES)
        root = patch.object(android, "ROOT", self.root)
        root.start()
        self.addCleanup(root.stop)

    def write_version(self, name=NAME, code=9, minimum=29):
        (self.root / "version.properties").write_text(
            f"versionName={name}\nversionCode={code}\nminSdk={minimum}\n", encoding="utf-8")

    def prepare(self, apk=None):
        signature = (f"Signer #1 certificate SHA-256 digest: {CERTIFICATE}\n"
                     "Verified using v3 scheme (APK Signature Scheme v3): true\n")
        badging = (f"package: name='{android.PACKAGE}' versionCode='9' versionName='{NAME}'\n"
                   "sdkVersion:'29'\n")
        args = argparse.Namespace(tag=TAG, apk=self.apk if apk is None else apk,
                                  output=self.root / "dist", certificate=CERTIFICATE)
        with patch.object(android, "run", side_effect=[signature, badging]), \
                patch.dict(android.os.environ, {"ANDROID_HOME": str(self.root / "sdk")}), \
                redirect_stdout(io.StringIO()):
            android.prepare(args)
        return args.output

    def test_prepare_preserves_existing_metadata_and_checksum_wire_format(self):
        output = self.prepare()
        update = (output / android.UPDATE).read_bytes()
        self.assertEqual((json.dumps(metadata(), indent=2) + "\n").encode("utf-8"),
                         update.replace(b"\r\n", b"\n"))
        self.assertEqual(metadata(), json.loads(update))
        filename = metadata()["asset"]
        self.assertEqual(APK_BYTES, (output / filename).read_bytes())
        self.assertEqual([
            f"{APK_DIGEST}  {filename}",
            f"{hashlib.sha256(update).hexdigest()}  {android.UPDATE}",
        ], (output / android.SUMS).read_text(encoding="utf-8").splitlines())

    def test_prepare_keeps_template_sections_until_the_next_version(self):
        (self.root / "CHANGELOG.md").write_text(
            f"## {NAME} - 2026-11-21\n\n"
            "## 📢 版本概述\n\nOverview.\n\n"
            "## ✨ 新增功能\n\nFeature.\n\n"
            "## 2.1.4 - 2026-11-20\n\nOld notes.\n",
            encoding="utf-8",
        )
        output = self.prepare()
        notes = (output / "notes.md").read_text(encoding="utf-8")
        self.assertIn("## 📢 版本概述", notes)
        self.assertIn("## ✨ 新增功能", notes)
        self.assertNotIn("Old notes.", notes)

    def test_version_accepts_client_component_and_integer_upper_bounds(self):

        name = "999999999.999999999.999999999"
        self.write_version(name, MAX_VERSION_CODE, MAX_SDK)
        self.assertEqual((name, MAX_VERSION_CODE, MAX_SDK), android.version(f"v{name}"))

    def test_version_rejects_components_above_the_client_limit(self):
        for name in ("1000000000.0.0", "0.1000000000.0", "0.0.1000000000"):
            with self.subTest(name=name):
                self.write_version(name)
                with self.assertRaises(SystemExit):
                    android.version(f"v{name}")

    def test_version_rejects_sdk_above_android_integer_limit(self):
        self.write_version(minimum=MAX_SDK + 1)
        with self.assertRaises(SystemExit):
            android.version(TAG)

    def test_prepare_rejects_an_empty_archive(self):
        self.apk.write_bytes(b"")
        with self.assertRaises(SystemExit):
            self.prepare()


if __name__ == "__main__":
    unittest.main()
