"""Verify the actual Build 5 package and report its ephemeral engineering certificate.

Read-only on the APK. Does not sign, install, provision credentials or touch user files.
"""
import hashlib
import os
from pathlib import Path
import re
import subprocess
from zipfile import ZipFile

from sign_engineering_apk import mutations_disabled_in_xmltree


def verify(apk, sdk):
    tools = sdk / "build-tools/37.0.0"
    badging = subprocess.check_output([str(tools / "aapt"), "dump", "badging", str(apk)], text=True)
    package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging, re.M)
    if package is None or package.groups() != ("com.kroflafps.s26downloadorganizer", "8", "0.5.0-preview"):
        raise SystemExit("Unexpected packaged identity/version")
    if "application-label:'S26 Download Organizer'" not in badging:
        raise SystemExit("Unexpected application label")
    tree = subprocess.check_output([str(tools / "aapt"), "dump", "xmltree", str(apk), "AndroidManifest.xml"], text=True)
    if not mutations_disabled_in_xmltree(tree):
        raise SystemExit("Compiled mutation-disabled marker missing or enabled")
    permissions = set(re.findall(r"^uses-permission: name='([^']+)'", badging, re.M))
    if permissions != {"android.permission.MANAGE_EXTERNAL_STORAGE", "com.kroflafps.s26downloadorganizer.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"}:
        raise SystemExit("Unexpected packaged permissions")
    with ZipFile(apk) as archive:
        dex = b"".join(archive.read(name) for name in archive.namelist() if name.endswith(".dex"))
    for forbidden in (b"FixtureFiles", b"MemoryJournal", b"s26downloadorganizer/diagnostic", b"s26downloadorganizer/telemetry"):
        if forbidden in dex:
            raise SystemExit("Unexpected diagnostic/telemetry/test capability in packaged DEX")
    if b"s26downloadorganizer/classification/FileClassifier;" not in dex:
        raise SystemExit("Classifier missing from packaged DEX")
    certificate = subprocess.check_output([str(tools / "apksigner"), "verify", "--verbose", "--print-certs", str(apk)], text=True)
    fingerprints = re.findall(r"certificate SHA-256 digest:\s*([a-fA-F0-9]+)", certificate)
    if len(fingerprints) != 1:
        raise SystemExit("Expected exactly one verified engineering signer")
    subprocess.run([str(tools / "zipalign"), "-c", "-P", "16", "4", str(apk)], check=True)
    return ("ENGINEERING PREVIEW ONLY - MUTATIONS DISABLED\n"
            "Package: com.kroflafps.s26downloadorganizer\nVersion: 8 / 0.5.0-preview\n"
            "Certificate SHA-256: " + fingerprints[0].lower() + "\n"
            "APK SHA-256: " + hashlib.sha256(apk.read_bytes()).hexdigest() + "\n"
            "Checkout SHA (PR may use merge ref): " + os.environ.get("GITHUB_SHA", "local-uncommitted") + "\n"
            "Marker, permission, DEX, signature and 16 KiB alignment checks passed. No installation performed.\n")


if __name__ == "__main__":
    apk = Path("app/build/outputs/apk/debug/app-debug.apk")
    report = verify(apk, Path(os.environ["ANDROID_HOME"]))
    apk.with_name("certificate.txt").write_text(report)
    print(report)
