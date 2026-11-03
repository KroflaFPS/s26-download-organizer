import hashlib
from pathlib import Path
import tempfile
import unittest
from create_test_fixtures import create_fixtures
from report_apk import compare
from sign_engineering_apk import marker_in_xmltree, COPY_MARKER

class DiagnosticToolsTests(unittest.TestCase):
    def test_fixture_generator_is_reproducible_exclusive_and_never_modifies_existing_data(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "synthetic"
            create_fixtures(root)
            empty = root / "s26-test-empty.bin"
            medium = root / "s26-test-1MiB.bin"
            self.assertEqual(empty.read_bytes(), b"")
            self.assertEqual(medium.stat().st_size, 1024 * 1024)
            self.assertEqual(hashlib.sha256(medium.read_bytes()).hexdigest(), "631b84027d6b9e52b539c4e8373622d23032dfadc64d60af87339c9037e4f769")
            medium.write_bytes(b"newer fixture sentinel")
            with self.assertRaises(FileExistsError):
                create_fixtures(root)
            self.assertEqual(medium.read_bytes(), b"newer fixture sentinel")
    def test_signature_comparison_never_recommends_uninstall_or_clear_data(self):
        self.assertTrue(compare("a", "a").startswith("MATCH:"))
        mismatch = compare("a", "b")
        self.assertTrue(mismatch.startswith("MISMATCH:"))
        self.assertIn("do not install, uninstall or clear data", mismatch)
    def test_signing_diagnostic_marker_must_be_explicit_and_unique(self):
        tree = '\n E: meta-data (line=1)\n A: android:name(0x01010003)="' + COPY_MARKER + '"\n A: android:value(0x01010024)=(type 0x12)0xffffffff\n'
        self.assertTrue(marker_in_xmltree(tree, COPY_MARKER, True))
        self.assertFalse(marker_in_xmltree(tree.replace("0xffffffff", "0x0"), COPY_MARKER, True))
        self.assertFalse(marker_in_xmltree(tree + tree, COPY_MARKER, True))
