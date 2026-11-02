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
APK = SCRIPT.parent.parent / "app/build/outputs/apk/debug/app-debug.apk"
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

    def invoke(self, directory, credentials=True, wrong_pin=False):
        work = Path(directory)
        (work / "unsigned-input").mkdir()
        shutil.copyfile(APK, work / "unsigned-input/app-debug.apk")
        env = {name: value for name, value in os.environ.items() if not name.startswith("S26_DEV_")}
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
