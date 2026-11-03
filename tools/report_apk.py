"""Public certificate report; optional comparison with the exact previously installed APK.
This checks APK certificates, not a connected device. Never uninstalls or clears data.
"""
import argparse
import hashlib
import os
from pathlib import Path
import re
import subprocess


def certificate(apk):
    signer = Path(os.environ["ANDROID_HOME"]) / "build-tools/37.0.0/apksigner"
    result = subprocess.check_output([str(signer), "verify", "--print-certs", str(apk)], text=True)
    pins = re.findall(r"certificate SHA-256 digest:\s*([a-fA-F0-9]+)", result)
    if len(pins) != 1 or not re.fullmatch(r"[a-fA-F0-9]{64}", pins[0]):
        raise ValueError("Exactly one verified signer is required")
    return pins[0].lower()


def compare(current, previous):
    return "MATCH: certificate-compatible in-place upgrade; installed APK identity still needs owner confirmation." if current == previous else "MISMATCH: do not install, uninstall or clear data. Obtain a certificate-matched APK; retain the journal."


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--previous", type=Path, help="Exact APK previously installed, if retained")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    current = certificate(args.apk)
    text = "Build 3.2 ENGINEERING: synthetic diagnostic copy only; MOVE/UNDO disabled.\nCertificate SHA-256: " + current
    with args.apk.open("rb") as stream:
        text += "\nAPK SHA-256: " + hashlib.file_digest(stream, "sha256").hexdigest()
    text += "\nCommit: " + os.environ.get("GITHUB_SHA", "local/unrecorded")
    text += "\nDevice upgrade compatibility: UNVERIFIED. Compare with the exact installed build before upgrading.\n"
    compatible = True
    if args.previous:
        previous = certificate(args.previous)
        compatible = current == previous
        text += "Previous APK certificate SHA-256: " + previous + "\n" + compare(current, previous) + "\n"
    args.output.write_text(text)
    if not compatible:
        raise SystemExit("Certificate mismatch; no uninstall/data-clear workaround is safe for the journal.")

if __name__ == "__main__":
    main()
