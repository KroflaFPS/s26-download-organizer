"""Public certificate report; optional comparison with the exact previously installed APK.
This checks APK certificates, not a connected device. Never uninstalls or clears data.
"""
import argparse
import hashlib
import os
from pathlib import Path
import re
import subprocess
from sign_engineering_apk import marker_in_xmltree, COPY_MARKER, MARKER
from apk_identity import identity, require_standalone_diagnostic, MAIN_ID, DIAGNOSTIC_ID


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
    parser.add_argument("--expect-package", help="Fail if the compiled application ID differs")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    package = identity(args.apk)
    if package["application_id"] not in (MAIN_ID, DIAGNOSTIC_ID) or args.expect_package and args.expect_package != package["application_id"]:
        raise SystemExit("Unexpected application ID; APK report/publishing refused.")
    current = certificate(args.apk)
    diagnostic = package["application_id"] == DIAGNOSTIC_ID
    aapt = Path(os.environ["ANDROID_HOME"]) / "build-tools/37.0.0/aapt"
    tree = subprocess.check_output([str(aapt), "dump", "xmltree", str(args.apk), "AndroidManifest.xml"], text=True)
    def flag(marker):
        return "true" if marker_in_xmltree(tree, marker, True) else "false" if marker_in_xmltree(tree, marker, False) else "UNVERIFIED"
    if args.expect_package == DIAGNOSTIC_ID:
        require_standalone_diagnostic(package)
        if flag(MARKER) != "false" or flag(COPY_MARKER) != "true":
            raise SystemExit("Unexpected compiled safety gates; diagnostic publishing refused.")
    text = "STANDALONE DIAGNOSTIC" if diagnostic else "MAIN APPLICATION PACKAGE"
    text += "\nCompiled MOVE/UNDO gate: " + flag(MARKER) + "\nCompiled diagnostic capability: " + flag(COPY_MARKER)
    text += "\nApplication ID: " + package["application_id"] + "\nLauncher label: " + package["label"]
    text += "\nVersion: " + package["version_code"] + "/" + package["version_name"] + "\nCertificate SHA-256: " + current
    with args.apk.open("rb") as stream:
        text += "\nAPK SHA-256: " + hashlib.file_digest(stream, "sha256").hexdigest()
    text += "\nCommit: " + os.environ.get("GITHUB_SHA", "local/unrecorded")
    if diagnostic:
        text += "\nInstall separately alongside Build 3.1. Its certificate need not match Build 3.1. Own app-private journal and storage permission.\n"
    text += "Device installation: UNVERIFIED. Updates to the SAME application ID require a matching certificate; never uninstall/clear data to bypass mismatch.\n"
    compatible = True
    if args.previous:
        previous_package = identity(args.previous)
        previous = certificate(args.previous)
        text += "Previous application ID: " + previous_package["application_id"] + "\nPrevious APK certificate SHA-256: " + previous + "\n"
        if previous_package["application_id"] != package["application_id"]:
            text += "SIDE-BY-SIDE: different application IDs, not an in-place upgrade. Certificate match not required; device installation remains unverified.\n"
        else:
            compatible = current == previous
            text += compare(current, previous) + "\n"
    args.output.write_text(text)
    if not compatible:
        raise SystemExit("Certificate mismatch; no uninstall/data-clear workaround is safe for the journal.")

if __name__ == "__main__":
    main()
