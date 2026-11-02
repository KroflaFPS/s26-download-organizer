"""Trusted manual CI only. Re-sign an engineering APK; NEVER enables mutations.
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

names = ["S26_DEV_KEYSTORE_BASE64", "S26_DEV_STORE_PASSWORD", "S26_DEV_KEY_ALIAS", "S26_DEV_KEY_PASSWORD", "S26_DEV_CERT_SHA256"]
if not all(os.environ.get(name) for name in names):
    raise SystemExit("Stable signing requested, but credentials/pinned certificate are incomplete. No fallback APK will be produced.")
pin = os.environ["S26_DEV_CERT_SHA256"].replace(":", "").strip().lower()
if not re.fullmatch(r"[0-9a-f]{64}", pin):
    raise SystemExit("Pinned signing certificate must be a SHA-256 fingerprint")
runner_temp = Path(os.environ["RUNNER_TEMP"])
output_dir = runner_temp / "s26-signed-output"
output_dir.mkdir(mode=0o700, exist_ok=False)
output = output_dir / "s26-build-3-stable-signed-engineering.apk"
signer = Path(os.environ["ANDROID_HOME"]) / "build-tools/37.0.0/apksigner"
with tempfile.TemporaryDirectory(prefix="s26-signing-", dir=runner_temp) as private:
    keystore = Path(private) / "development.p12"
    descriptor = os.open(keystore, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    with os.fdopen(descriptor, "wb") as stream:
        stream.write(base64.b64decode(os.environ["S26_DEV_KEYSTORE_BASE64"], validate=True))
    subprocess.run([str(signer), "sign", "--ks", str(keystore),
                    "--ks-key-alias", os.environ["S26_DEV_KEY_ALIAS"],
                    "--ks-pass", "env:S26_DEV_STORE_PASSWORD", "--key-pass", "env:S26_DEV_KEY_PASSWORD",
                    "--out", str(output), "unsigned-input/app-debug.apk"], check=True)
    verified = subprocess.check_output([str(signer), "verify", "--print-certs", str(output)], text=True)
    fingerprints = re.findall(r"certificate SHA-256 digest:\s*([a-fA-F0-9]+)", verified)
    if len(fingerprints) != 1 or not hmac.compare_digest(fingerprints[0].lower(), pin):
        raise SystemExit("Signing certificate mismatch. Artifact upload is blocked.")
    (output_dir / "certificate.txt").write_text(
        "ENGINEERING ONLY - MUTATIONS DISABLED\nCertificate SHA-256: " + pin +
        "\nCommit: " + os.environ.get("GITHUB_SHA", "unknown") +
        "\nAPK SHA-256: " + hashlib.file_digest(output.open("rb"), "sha256").hexdigest() + "\n"
    )
