"""Installation checks for the exact restic build and existing local files."""

import hashlib
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import install_restic
import restic_tool


@unittest.skipUnless(os.name == 'posix', 'The restic pin supports Linux amd64')
class ResticInstallerTests(unittest.TestCase):
    binary = b'synthetic pinned binary'

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.folder = Path(self.temp.name)
        self.path = self.folder / 'restic'
        pins = patch.multiple(restic_tool, BINARY_SIZE=len(self.binary),
                              BINARY_SHA256=hashlib.sha256(self.binary).hexdigest(), create=True)
        pins.start()
        self.addCleanup(pins.stop)

    def test_verified_build_is_installed_and_reused(self):
        with patch.object(install_restic, 'build_binary', return_value=self.binary, create=True) as build:
            self.assertTrue(install_restic.install(self.path))
            self.assertFalse(install_restic.install(self.path))
        build.assert_called_once_with()
        self.assertEqual(self.binary, self.path.read_bytes())
        self.assertEqual(0o755, self.path.stat().st_mode & 0o777)
        self.assertEqual([self.path], list(self.folder.iterdir()))

    def test_wrong_existing_file_is_never_replaced(self):
        original = b'old official binary'
        self.path.write_bytes(original)
        with patch.object(install_restic, 'build_binary', create=True) as build, self.assertRaises(ValueError):
            install_restic.install(self.path)
        build.assert_not_called()
        self.assertEqual(original, self.path.read_bytes())

    def test_mismatched_build_is_refused_before_installation(self):
        with patch.object(install_restic, 'build_binary', return_value=b'wrong binary', create=True):
            with self.assertRaisesRegex(ValueError, 'checksum mismatch'):
                install_restic.install(self.path)
        self.assertEqual([], list(self.folder.iterdir()))

    def test_existing_file_appearing_during_build_is_verified(self):
        def simultaneous_install():
            self.path.write_bytes(self.binary)
            self.path.chmod(0o755)
            return self.binary

        with patch.object(install_restic, 'build_binary', side_effect=simultaneous_install, create=True):
            self.assertFalse(install_restic.install(self.path))
        self.assertEqual([self.path], list(self.folder.iterdir()))
        self.assertEqual(self.binary, self.path.read_bytes())
