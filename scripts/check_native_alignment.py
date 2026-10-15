"""Verify every packaged 64-bit ELF library has 16 KB load-segment alignment."""
import struct
import sys
import zipfile

bad = []
with zipfile.ZipFile(sys.argv[1]) as archive:
    for name in archive.namelist():
        if not name.endswith('.so') or not any(abi in name for abi in ('arm64-v8a', 'x86_64')):
            continue
        data = archive.read(name)
        if data[:4] != b'\x7fELF' or data[4] != 2:
            raise ValueError(f'Unexpected ELF format: {name}')
        endian = '<' if data[5] == 1 else '>'
        offset = struct.unpack_from(endian + 'Q', data, 32)[0]
        entry_size, count = struct.unpack_from(endian + 'HH', data, 54)
        alignments = []
        relro_ok = True
        for index in range(count):
            p = struct.unpack_from(endian + 'IIQQQQQQ', data, offset + index * entry_size)
            if p[0] == 1:
                alignments.append(p[7])
            if p[0] == 0x6474e552:
                relro_ok = (p[3] + p[6]) % 16384 == 0
        passed = bool(alignments) and min(alignments) >= 16384 and relro_ok
        print(f'{"PASS" if passed else "FAIL"} {name}: LOAD {alignments}, RELRO aligned={relro_ok}')
        if not passed:
            bad.append(name)
sys.exit(bool(bad))
