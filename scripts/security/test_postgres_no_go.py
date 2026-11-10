import hashlib
import io
from pathlib import Path
import tarfile
import tempfile
import unittest

from postgres_no_go import fingerprint, inventory


class PostgreSQLRootfsTest(unittest.TestCase):
    def test_fingerprints_elf_even_when_the_marker_crosses_a_read_boundary(self):
        data = b"\x7fELF" + b"0" * (1024 * 1024 - 5) + b"\xff Go buildinf:"
        self.assertEqual(("go-elf", hashlib.sha256(data).hexdigest()), fingerprint(io.BytesIO(data)))
        self.assertEqual("file", fingerprint(io.BytesIO(data[4:]))[0])

    def test_rejects_unsafe_or_duplicated_export_paths(self):
        for paths in (("../outside",), ("usr/bin/tool", "./usr/bin/tool")):
            with self.subTest(paths=paths), tempfile.TemporaryDirectory() as temporary:
                path = Path(temporary) / "export.tar"
                with tarfile.open(path, "w") as archive:
                    for name in paths:
                        archive.addfile(tarfile.TarInfo(name), io.BytesIO())
                with self.assertRaises(ValueError):
                    inventory(path)
