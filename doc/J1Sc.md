# J1Sc jako CPU gry

Upstream: https://github.com/SteffenReith/J1Sc (BSD-3, ostatni commit 2020-06, gałąź `master`).
Fork: https://github.com/TomekBarabasz/J1Sc, gałąź `newhope`, submodule `hw/cpu/j1`.

## Stan upstreamu (2020)

| | J1Sc | ten projekt (HwBuild) |
| --- | --- | --- |
| Scala | 2.11.8 | 2.13.14 |
| SpinalHDL | 1.4.2 | 1.15.0 |
| sbt | 0.13.16 | 1.12.4 |
| pakiet | brak (domyślny) | `newhope.*` |
| układ katalogów | `src/main/scala` | `hw/spinal/{main,test}` |
| build | własne taski iceGen/icePnr… dla IceBreaker, IcoBoard, Nexys4 | Makefile per płytka |
| pamięć programu | `$readmemb` z plików `.bin` | `inlineRom` (Conventions) |

Wniosek: jako submodule "tak jak jest" się nie skompiluje w tym buildzie, a
modyfikacje i tak będą (płytki, peryferia gry, pamięć) — stąd **fork**, nie
gołe submodule do upstreamu.

## Dlaczego fork, a nie kopia
- Fork na GitHubie zachowuje historię i powiązanie z upstreamem: `git fetch upstream`
  i cherry-pick, jeśli autor coś poprawi; licencja BSD-3 wymaga zostawienia
  copyrightu — w forku jest automatycznie.
- Kopia (nowe repo z wklejonym kodem) traci historię i zrywa łańcuch zmian; zysk
  tylko taki, że repo może być prywatne (fork publicznego repo musi być publiczny).

## Stan (2026-11-03)
Zrobione (szczegóły w `hw/cpu/j1/NEWHOPE.md`):
1. Fork + gałąź `newhope`; źródła w `j1/hw/spinal` (rdzeń) i `boards/hw/spinal` (płytki upstreamu), przeniesione `git mv`.
2. Port: Scala 2.13.14, SpinalHDL 1.15.0, sbt 1.12.4, pakiety `j1sc` / `j1sc.boards`; logika bez zmian.
3. Poprawka upstreamu: `J1RStack` brał szerokość wskaźnika z `dataStackIdxWidth` — konfiguracje
   z różną głębokością stosów (`default16`, `debug16`, …) nie elaborowały się.
4. `J1IsaTest` (Verilator): ALU, call/ret, jz, odczyt/zapis I/O.
5. iCE40: Spinal ≥ 1.5 oznacza stosy jako `ram_style = "distributed"` — skrypty `.ys` zdejmują atrybut.
6. Submodule `hw/cpu/j1` + `ProjectRef` w `hw/build.sbt`; `snake` zależy od `j1`.

Pomiar: cały SoC `J1Ice` (CPU, 4 k słów RAM z Forthem, UART, timery, PWM, JTAG) na UP5K —
2 009 / 5 280 LC (38%), 18 / 30 EBR.

## Do zrobienia
- ROM z firmware przez `inlineRom` zamiast `$readmemb` (pliki `.bin` obok `.v`).
- `bootCode` z pliku firmware bez `sys.exit` przy braku pliku.
- SoC gry dla Tang Nano 9K i iCESugar: J1 + LED-y/przyciski/wyświetlacz na `J1Bus`.
- Generacja obrazu Forth w buildzie (gforth, `toolchain/forth`).
