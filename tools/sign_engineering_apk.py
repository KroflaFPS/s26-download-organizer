"""Trusted manual CI only. Re-sign a verified synthetic-fixture diagnostic APK; MOVE/UNDO remain disabled.
No passwords appear in command arguments or logs. Private material stays in runner temp.
"""
import base64
import hashlib
import hmac
import os
from pathlib import Path
import re
import subprocess
import tempfile
from apk_identity import identity, require_standalone_diagnostic, DIAGNOSTIC_ID

COPY_MARKER = "com.kroflafps.s26downloadorganizer.DIAGNOSTIC_COPY_ENABLED"
MARKER = "com.kroflafps.s26downloadorganizer.MUTATIONS_ENABLED"

def marker_in_xmltree(tree, marker, enabled):
    blocks = re.split(r"\n\s*E: ", tree)
    named = [block for block in blocks if block.startswith("meta-data ") and
             re.search(r'android:name\([^)]*\)="' + re.escape(marker) + '"', block)]
    value = "0xffffffff" if enabled else "0x0+"
    return len(named) == 1 and re.search(r"android:value\([^)]*\)=\(type 0x12\)" + value + r"\b", named[0]) is not None

def mutations_disabled_in_xmltree(tree):
    return marker_in_xmltree(tree, MARKER, False)

def main():
    # Defense in depth. Protected GitHub environment approval remains the primary secret boundary.
    if os.environ.get("GITHUB_ACTIONS") == "true" and (
        os.environ.get("GITHUB_EVENT_NAME") != "workflow_dispatch" or
        os.environ.get("GITHUB_REF") != "refs/heads/main" or
        os.environ.get("GITHUB_REPOSITORY") != "KroflaFPS/s26-download-organizer"
    ):
        raise SystemExit("Owner signing is restricted to trusted manual main runs. No PR signing.")
    names = ["S26_DEV_KEYSTORE_BASE64", "S26_DEV_STORE_PASSWORD", "S26_DEV_KEY_ALIAS", "S26_DEV_KEY_PASSWORD", "S26_DEV_CERT_SHA256"]
    if not all(os.environ.get(name) for name in names):
        raise SystemExit("Stable signing requested, but credentials/pinned certificate are incomplete. No fallback APK will be produced.")
    pin = os.environ["S26_DEV_CERT_SHA256"].replace(":", "").strip().lower()
    if not re.fullmatch(r"[0-9a-f]{64}", pin):
        raise SystemExit("Pinned signing certificate must be a SHA-256 fingerprint")
    tools = Path(os.environ["ANDROID_HOME"]) / "build-tools/37.0.0"
    input_apk = Path("unsigned-input/app-diagnostic.apk")
    require_standalone_diagnostic(identity(input_apk))
    tree = subprocess.check_output([str(tools / "aapt"), "dump", "xmltree", str(input_apk), "AndroidManifest.xml"], text=True)
    if not mutations_disabled_in_xmltree(tree):
        raise SystemExit("APK is not explicitly marked mutation-disabled; signing refused.")
    if not marker_in_xmltree(tree, COPY_MARKER, True):
        raise SystemExit("Expected explicitly labeled synthetic-fixture diagnostic build; signing refused.")
    runner_temp = Path(os.environ["RUNNER_TEMP"])
    output_dir = runner_temp / "s26-signed-output"
    output_dir.mkdir(mode=0o700, exist_ok=False)
    output = output_dir / "s26-build-3-2-stable-signed-standalone-diagnostic.apk"
    signer = tools / "apksigner"
    with tempfile.TemporaryDirectory(prefix="s26-signing-", dir=runner_temp) as private:
        keystore = Path(private) / "development.p12"
        descriptor = os.open(keystore, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(base64.b64decode(os.environ["S26_DEV_KEYSTORE_BASE64"], validate=True))
        subprocess.run([str(signer), "sign", "--ks", str(keystore),
                        "--ks-key-alias", os.environ["S26_DEV_KEY_ALIAS"],
                        "--ks-pass", "env:S26_DEV_STORE_PASSWORD", "--key-pass", "env:S26_DEV_KEY_PASSWORD",
                        "--out", str(output), str(input_apk)], check=True)
        verified = subprocess.check_output([str(signer), "verify", "--print-certs", str(output)], text=True)
        fingerprints = re.findall(r"certificate SHA-256 digest:\s*([a-fA-F0-9]+)", verified)
        if len(fingerprints) != 1 or not hmac.compare_digest(fingerprints[0].lower(), pin):
            raise SystemExit("Signing certificate mismatch. Artifact upload is blocked.")
        with output.open("rb") as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
        (output_dir / "certificate.txt").write_text(
            "ENGINEERING ONLY - MOVE/UNDO MUTATIONS DISABLED; SYNTHETIC DIAGNOSTIC COPY ENABLED\nApplication ID: " + DIAGNOSTIC_ID + "\nSeparate app-private journal; installs alongside main app.\nCertificate SHA-256: " + pin +
            "\nCommit: " + os.environ.get("GITHUB_SHA", "unknown") + "\nAPK SHA-256: " + digest + "\n"
        )

if __name__ == "__main__":
    main()
