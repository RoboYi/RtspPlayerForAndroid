import struct
import unittest

from check_native_page_size import load_alignments


class NativePageSizeTest(unittest.TestCase):
    def test_reads_elf64_load_alignment(self):
        elf = bytearray(120)
        elf[:6] = b"\x7fELF\x02\x01"
        struct.pack_into("<Q", elf, 32, 64)
        struct.pack_into("<HH", elf, 54, 56, 1)
        struct.pack_into("<I", elf, 64, 1)
        struct.pack_into("<Q", elf, 64 + 48, 0x4000)
        self.assertEqual([0x4000], load_alignments(elf))

    def test_rejects_bad_program_headers(self):
        with self.assertRaises(ValueError):
            load_alignments(b"not an ELF")


if __name__ == "__main__":
    unittest.main()
