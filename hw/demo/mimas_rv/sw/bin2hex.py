#!/usr/bin/env python3
"""bin2hex.py program.bin program.hex WORDS - obraz BRAM dla microrv32.

Format jak w microrv32 (Tools.readmemhHalf): jedna linia = jedno slowo 32 b,
4 bajty w kolejnosci adresow, np. "6F00C004". Dopelnia zerami do WORDS slow."""
import sys
src, dst, words = sys.argv[1], sys.argv[2], int(sys.argv[3], 0)
data = open(src, "rb").read()
if len(data) > 4 * words:
    sys.exit(f"{src}: {len(data)} B nie miesci sie w {4 * words} B RAM-u")
data = data.ljust(4 * words, b"\0")
with open(dst, "w") as f:
    for i in range(0, len(data), 4):
        f.write(data[i:i + 4].hex().upper() + "\n")
print(f"{dst}: {len(open(src, 'rb').read())} B programu, {words} slow")
