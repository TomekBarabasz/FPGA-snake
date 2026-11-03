# J1Sc jako CPU gry

Upstream: https://github.com/SteffenReith/J1Sc (BSD-3, ostatni commit 2020-06, gałąź `master`).

## Stan upstreamu (2020)

| | J1Sc | ten projekt (HwBuild) |
| --- | --- | --- |
| Scala | 2.11.8 | 2.13.14 |
| SpinalHDL | 1.4.2 | 1.12.3 |
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

## Plan
1. Fork `SteffenReith/J1Sc` → `TomekBarabasz/J1Sc`; gałąź robocza np. `newhope`.
2. Port w forku, osobne commity:
   - `build.sbt` w stylu fpga-hw-lib (projekt `j1`, `hwLayout`, zależność od `core`
     przez `ProjectRef` albo bez niej), Scala 2.13, Spinal 1.12;
   - pakiet (np. `j1sc`), przeniesienie do `hw/spinal/main`;
   - API Spinala 1.4 → 1.12 (głównie `Mem`, `ClockDomain`, `BusSlaveFactory`, sim);
   - ROM z firmware przez `initialContent`/`inlineRom` zamiast `$readmemb`;
   - top-levele IceBreaker/IcoBoard/Nexys4 zostają albo wylatują do `boards/`.
3. W FPGA-snake: `git submodule add -b newhope https://github.com/TomekBarabasz/J1Sc hw/cpu/j1`,
   w `hw/build.sbt`: `lazy val j1 = ProjectRef(file("cpu/j1"), "j1")`, `snake.dependsOn(j1)`.
4. Firmware (Forth, `support/`/`toolchain/` w J1Sc): generacja obrazu ROM w buildzie.

Zasoby: J1 to ~1–1,5 k LUT4 + pamięć w BRAM — mieści się w UP5K (5 280 LC, 30 × EBR 4 kb,
128 KB SPRAM) i GW1NR-9 (8 640 LUT, 26 × BSRAM 18 kb).
