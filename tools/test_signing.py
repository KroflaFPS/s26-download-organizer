"""Disposable credentials only. Verifies continuity/mismatch/absence without GitHub secrets."""
import base64
import hashlib
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().with_name("sign_engineering_apk.py")
APK = SCRIPT.parent.parent / "app/build/outputs/apk/diagnostic/app-diagnostic.apk"
KEYTOOL = Path(os.environ["JAVA_HOME"]) / "bin/keytool"

class SigningTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.keys = tempfile.TemporaryDirectory(prefix="s26-test-credentials-")
        key = Path(cls.keys.name) / "test-only.p12"
        cls.password = secrets.token_urlsafe(24)
        env = dict(os.environ, S26_TEST_PASSWORD=cls.password)
        subprocess.run([str(KEYTOOL), "-genkeypair", "-storetype", "PKCS12", "-keystore", str(key),
                        "-alias", "test-only", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                        "-dname", "CN=Disposable Signing Test", "-storepass:env", "S26_TEST_PASSWORD",
                        "-keypass:env", "S26_TEST_PASSWORD"], env=env, check=True, capture_output=True)
        der = subprocess.check_output([str(KEYTOOL), "-exportcert", "-keystore", str(key), "-alias", "test-only",
                                      "-storepass:env", "S26_TEST_PASSWORD"], env=env, stderr=subprocess.DEVNULL)
        cls.pin = hashlib.sha256(der).hexdigest()
        cls.key_base64 = base64.b64encode(key.read_bytes()).decode()

    @classmethod
    def tearDownClass(cls):
        cls.keys.cleanup()

    def invoke(self, directory, credentials=True, wrong_pin=False, github_context=None, input_apk=APK):
        work = Path(directory)
        (work / "unsigned-input").mkdir()
        shutil.copyfile(input_apk, work / "unsigned-input/app-diagnostic.apk")
        env = {name: value for name, value in os.environ.items() if not name.startswith("S26_DEV_")}
        env.pop("GITHUB_ACTIONS", None)  # Existing disposable-key tests model a trusted local signing session.
        if github_context:
            env.update(github_context)
        env["RUNNER_TEMP"] = str(work)
        if credentials:
            env.update(S26_DEV_KEYSTORE_BASE64=self.key_base64, S26_DEV_STORE_PASSWORD=self.password,
                       S26_DEV_KEY_PASSWORD=self.password, S26_DEV_KEY_ALIAS="test-only",
                       S26_DEV_CERT_SHA256="0" * 64 if wrong_pin else self.pin)
        return subprocess.run(["python3", str(SCRIPT)], cwd=work, env=env, capture_output=True, text=True)

    def test_same_protected_key_gives_verified_continuity_across_two_runs(self):
        for _ in range(2):
            with tempfile.TemporaryDirectory(prefix="s26-signing-test-") as directory:
                result = self.invoke(directory)
                self.assertEqual(result.returncode, 0, result.stderr)
                report = (Path(directory) / "s26-signed-output/certificate.txt").read_text()
                self.assertIn(self.pin, report)
                self.assertIn("MUTATIONS DISABLED", report)
                self.assertFalse(list(Path(directory).glob("s26-signing-*")))

    def test_missing_credentials_fail_without_fallback_output(self):
        with tempfile.TemporaryDirectory(prefix="s26-signing-test-") as directory:
            result = self.invoke(directory, credentials=False)
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse((Path(directory) / "s26-signed-output").exists())

    def test_wrong_certificate_blocks_artifact_and_cleans_private_material(self):
        with tempfile.TemporaryDirectory(prefix="s26-signing-test-") as directory:
            result = self.invoke(directory, wrong_pin=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse((Path(directory) / "s26-signed-output/certificate.txt").exists())
            self.assertFalse(list(Path(directory).glob("s26-signing-*")))

    def test_pr_and_non_main_contexts_are_rejected_before_signing(self):
        contexts = [
            {"GITHUB_ACTIONS": "true", "GITHUB_EVENT_NAME": "pull_request", "GITHUB_REF": "refs/pull/3/merge", "GITHUB_REPOSITORY": "KroflaFPS/s26-download-organizer"},
            {"GITHUB_ACTIONS": "true", "GITHUB_EVENT_NAME": "workflow_dispatch", "GITHUB_REF": "refs/heads/feat/build-3-safe-operations", "GITHUB_REPOSITORY": "KroflaFPS/s26-download-organizer"},
        ]
        for context in contexts:
            with tempfile.TemporaryDirectory(prefix="s26-context-test-") as directory:
                result = self.invoke(directory, github_context=context)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("No PR signing", result.stderr)
                self.assertFalse((Path(directory) / "s26-signed-output").exists())

    def test_compiled_gate_marker_must_be_unique_and_boolean_false(self):
        from sign_engineering_apk import mutations_disabled_in_xmltree, MARKER
        disabled = '\n    E: meta-data (line=1)\n      A: android:name(0x01010003)="' + MARKER + '"\n      A: android:value(0x01010024)=(type 0x12)0x0\n'
        self.assertTrue(mutations_disabled_in_xmltree(disabled))
        self.assertFalse(mutations_disabled_in_xmltree(disabled.replace("0x0\n", "0xffffffff\n")))
        self.assertFalse(mutations_disabled_in_xmltree(disabled + disabled))
        self.assertFalse(mutations_disabled_in_xmltree(""))

    def test_public_report_verifies_actual_apks_and_refuses_certificate_mismatch(self):
        reporter = SCRIPT.with_name("report_apk.py")
        with tempfile.TemporaryDirectory(prefix="s26-report-test-") as directory:
            result = self.invoke(directory)
            self.assertEqual(result.returncode, 0, result.stderr)
            apk = Path(directory) / "s26-signed-output/s26-build-3-2-stable-signed-standalone-diagnostic.apk"
            report = Path(directory) / "comparison.txt"
            same = subprocess.run(["python3", str(reporter), str(apk), "--previous", str(apk), "--output", str(report)], capture_output=True, text=True)
            self.assertEqual(same.returncode, 0, same.stderr)
            self.assertIn("MATCH:", report.read_text())
            different = subprocess.run(["python3", str(reporter), str(apk), "--previous", str(APK), "--output", str(report)], capture_output=True, text=True)
            self.assertNotEqual(different.returncode, 0)
            self.assertIn("MISMATCH:", report.read_text())
            self.assertIn("do not install, uninstall or clear data", report.read_text())

    def test_protected_signer_refuses_normal_application_before_private_material_is_written(self):
        normal = APK.parents[1] / "debug/app-debug.apk"
        with tempfile.TemporaryDirectory(prefix="s26-wrong-app-") as directory:
            result = self.invoke(directory, input_apk=normal)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("normal package signing/publishing refused", result.stderr)
            self.assertFalse((Path(directory) / "s26-signed-output").exists())

    def test_compiled_packages_are_distinct_and_coinstallation_has_no_authority_or_permission_collision(self):
        from apk_identity import identity, MAIN_ID, DIAGNOSTIC_ID, DIAGNOSTIC_LABEL
        tools = Path(os.environ["ANDROID_HOME"]) / "build-tools/37.0.0"
        normal = APK.parents[1] / "debug/app-debug.apk"
        self.assertEqual(identity(normal)["application_id"], MAIN_ID)
        self.assertEqual(identity(APK)["application_id"], DIAGNOSTIC_ID)
        self.assertEqual(identity(APK)["label"], DIAGNOSTIC_LABEL)
        from sign_engineering_apk import marker_in_xmltree, COPY_MARKER, MARKER
        for apk, expected, diagnostic in [(normal, MAIN_ID, False), (APK, DIAGNOSTIC_ID, True)]:
            tree = subprocess.check_output([str(tools / "aapt"), "dump", "xmltree", str(apk), "AndroidManifest.xml"], text=True)
            self.assertTrue(marker_in_xmltree(tree, COPY_MARKER, diagnostic))
            self.assertTrue(marker_in_xmltree(tree, MARKER, False))
            self.assertIn(expected + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION", tree)
            self.assertIn(expected + ".androidx-startup", tree)
            self.assertNotIn("android:sharedUserId", tree)

    def test_report_treats_normal_and_diagnostic_apks_as_separate_installs(self):
        reporter = SCRIPT.with_name("report_apk.py")
        normal = APK.parents[1] / "debug/app-debug.apk"
        with tempfile.TemporaryDirectory(prefix="s26-separate-app-report-") as directory:
            signed = self.invoke(directory)
            self.assertEqual(signed.returncode, 0, signed.stderr)
            apk = Path(directory) / "s26-signed-output/s26-build-3-2-stable-signed-standalone-diagnostic.apk"
            report = Path(directory) / "comparison.txt"
            result = subprocess.run(["python3", str(reporter), str(apk), "--previous", str(normal), "--output", str(report)], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("SIDE-BY-SIDE:", report.read_text())
            self.assertIn("Certificate match not required", report.read_text())
            refused = subprocess.run(["python3", str(reporter), str(normal), "--expect-package", "com.kroflafps.s26downloadorganizer.diagnostic", "--output", str(Path(directory) / "wrong.txt")], capture_output=True, text=True)
            self.assertNotEqual(refused.returncode, 0)
            self.assertFalse((Path(directory) / "wrong.txt").exists())

    def test_disposable_mutation_adapters_are_not_packaged(self):
        from zipfile import ZipFile
        with ZipFile(APK) as package:
            dex = b"".join(package.read(name) for name in package.namelist() if name.endswith(".dex"))
        self.assertNotIn(b"FixtureFiles", dex)
        self.assertNotIn(b"MemoryJournal", dex)
        self.assertNotIn(b"FixtureDirectories", dex)
        self.assertNotIn(b"MemoryDiagnosticJournal", dex)
