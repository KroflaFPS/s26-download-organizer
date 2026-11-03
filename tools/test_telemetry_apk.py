import os
from pathlib import Path
import tempfile
import unittest
from telemetry_apk import verify, report, boolean_marker, PREFIX

ROOT = Path(__file__).resolve().parent.parent
APK = ROOT / "app/build/outputs/apk/telemetry/app-telemetry.apk"
class TelemetryApkTests(unittest.TestCase):
    def test_owner_artifact_is_separate_readonly_and_signature_verified(self):
        self.assertEqual(len(verify(APK)), 64)
        output = report(APK)
        self.assertIn("com.kroflafps.s26downloadorganizer.telemetry", output)
        self.assertIn("UNVERIFIED by CI", output)
    def test_normal_main_package_cannot_be_mislabeled_or_published_as_telemetry(self):
        with self.assertRaises(ValueError):
            verify(ROOT / "app/build/outputs/apk/debug/app-debug.apk")
    def test_compiled_flags_fail_closed_on_missing_duplicate_or_changed_markers(self):
        tree = '\n E: meta-data (line=1)\n A: android:name(0x01010003)="' + PREFIX + 'DIAGNOSTIC_COPY_ENABLED"\n A: android:value(0x01010024)=(type 0x12)0x0\n'
        self.assertTrue(boolean_marker(tree, PREFIX + "DIAGNOSTIC_COPY_ENABLED", False))
        self.assertFalse(boolean_marker(tree + tree, PREFIX + "DIAGNOSTIC_COPY_ENABLED", False))
        self.assertFalse(boolean_marker(tree.replace("0x0\n", "0xffffffff\n"), PREFIX + "DIAGNOSTIC_COPY_ENABLED", False))
        self.assertFalse(boolean_marker("", PREFIX + "DIAGNOSTIC_COPY_ENABLED", False))
