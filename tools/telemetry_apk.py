"""Verify the actual owner APK's read-only identity/capabilities and print only public signing metadata."""
import argparse
import hashlib
import os
from pathlib import Path
import re
import subprocess
from zipfile import ZipFile

PACKAGE = "com.kroflafps.s26downloadorganizer.telemetry"
LABEL = "S26 Read-Only Telemetry"
PREFIX = "com.kroflafps.s26downloadorganizer."

def boolean_marker(tree, name, value):
    blocks = re.split(r"\n\s*E: ", tree)
    found = [b for b in blocks if b.startswith("meta-data ") and re.search(r'android:name\([^)]*\)="' + re.escape(name) + '"', b)]
    return len(found) == 1 and re.search(r"android:value\([^)]*\)=\(type 0x12\)" + ("0xffffffff" if value else "0x0+") + r"\b", found[0]) is not None

def verify(apk):
    apk = Path(apk); tools = Path(os.environ["ANDROID_HOME"]) / "build-tools/37.0.0"
    badge = subprocess.check_output([str(tools / "aapt"), "dump", "badging", str(apk)], text=True)
    package = re.search(r"^package: name='([^']+)'", badge, re.M)
    label = re.search(r"^application-label:'([^']+)'", badge, re.M)
    if not package or package[1] != PACKAGE or not label or label[1] != LABEL:
        raise ValueError("Unexpected package/label; only the separate read-only telemetry app can be published")
    tree = subprocess.check_output([str(tools / "aapt"), "dump", "xmltree", str(apk), "AndroidManifest.xml"], text=True)
    for name, enabled in [("MUTATIONS_ENABLED", False), ("DIAGNOSTIC_COPY_ENABLED", False), ("TELEMETRY_ENABLED", True)]:
        if not boolean_marker(tree, PREFIX + name, enabled):
            raise ValueError("Missing/unsafe compiled capability marker: " + name)
    allowed = {"android.permission.MANAGE_EXTERNAL_STORAGE", "android.permission.WAKE_LOCK", "android.permission.ACCESS_NETWORK_STATE", "android.permission.RECEIVE_BOOT_COMPLETED", PACKAGE + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"}
    permissions = set(re.findall(r"^uses-permission: name='([^']+)'", badge, re.M))
    if permissions != allowed or "android:sharedUserId" in tree or 'name(0x01010003)="androidx.work.impl.foreground.SystemForegroundService"' in tree:
        raise ValueError("Unexpected permission/shared-UID/foreground-service boundary")
    with ZipFile(apk) as archive:
        dex = b"".join(archive.read(name) for name in archive.namelist() if name.endswith(".dex"))
    for forbidden in [b"DiagnosticCopyCoordinator", b"AndroidDiagnosticCopies", b"FixtureFiles", b"MemoryJournal", b"FixtureMetadataPages"]:
        if forbidden in dex:
            raise ValueError("Diagnostic-copy or test-only implementation was packaged")
    signed = subprocess.check_output([str(tools / "apksigner"), "verify", "--print-certs", str(apk)], text=True)
    pins = re.findall(r"certificate SHA-256 digest:\s*([a-fA-F0-9]{64})", signed)
    if len(pins) != 1:
        raise ValueError("Expected one verified signer")
    return pins[0].lower()

def report(apk):
    pin = verify(apk)
    with Path(apk).open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    return ("READ-ONLY EXPERIMENT — no organizing/moving/copying/deleting\nApplication ID: " + PACKAGE +
        "\nLabel: " + LABEL + "\nCertificate SHA-256: " + pin + "\nAPK SHA-256: " + digest +
        "\nCommit: " + os.environ.get("GITHUB_SHA", "local") +
        "\nFirst installation is separate from Build 3.1 and Build 3.2 Diagnostic. No matching certificate required across different IDs.\nSame-ID upgrades still require signature continuity; never uninstall/clear either existing journal as a workaround.\nSamsung wakeup/readiness coverage: UNVERIFIED by CI.\n")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    output = report(args.apk)
    args.output.write_text(output)
    print(output)
