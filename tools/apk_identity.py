"""Read compiled APK identity using Android build tools; no device or credentials needed."""
import os
from pathlib import Path
import re
import subprocess

MAIN_ID = "com.kroflafps.s26downloadorganizer"
DIAGNOSTIC_ID = MAIN_ID + ".diagnostic"
DIAGNOSTIC_LABEL = "S26 Organizer Diagnostic"


def identity(apk):
    aapt = Path(os.environ["ANDROID_HOME"]) / "build-tools/37.0.0/aapt"
    output = subprocess.check_output([str(aapt), "dump", "badging", str(apk)], text=True)
    package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", output, re.M)
    label = re.search(r"^application-label:'([^']+)'", output, re.M)
    if not package or not label:
        raise ValueError("Compiled APK identity/label unavailable")
    return dict(application_id=package[1], version_code=package[2], version_name=package[3], label=label[1])


def require_standalone_diagnostic(value):
    if value["application_id"] != DIAGNOSTIC_ID or value["label"] != DIAGNOSTIC_LABEL or not value["version_name"].endswith("-diagnostic"):
        raise ValueError("Expected separately installed S26 Organizer Diagnostic; normal package signing/publishing refused")
