# FPGA-snake

Gra snake na FPGA, dwa targety:

| Płytka | Układ | Zegar | Toolchain |
| --- | --- | --- | --- |
| [Tang Nano 9K](https://wiki.sipeed.com/hardware/en/tang/Tang-Nano-9K/Nano-9K.html) | GW1NR-LV9QN88PC6/I5 | 27 MHz | yosys `synth_gowin` → nextpnr-himbaechel → gowin_pack → openFPGALoader |
| [iCESugar v1.5](https://github.com/wuxx/icesugar) | iCE40UP5K-SG48 | 12 MHz | yosys `synth_ice40` → nextpnr-ice40 → icepack → icesprog / iCELink |

RTL w SpinalHDL, build sbt jak w [voice-changer](https://github.com/TomekBarabasz/voice-changer):
wspólne ustawienia i biblioteki z [fpga-hw-lib](https://github.com/TomekBarabasz/fpga-hw-lib) (submodule `hw/lib`).

```
hw/
  build.sbt           projekty: snake, demo-tangnano9k, demo-icesugar (+ biblioteki z lib/)
  project/            meta-build: HwBuild z lib/sbt
  lib/                submodule fpga-hw-lib (core, vertebra, ...)
  cpu/j1/             submodule J1Sc (fork, gałąź newhope): CPU J1, pakiet j1sc
  cpu/microrv32/      submodule microrv32 (fork, gałąź newhope): RISC-V RV32IMC, pakiet core.microrv32
  snake/              logika niezależna od płytki: newhope.snake (gra), newhope.snake.board (ResetGen)
  demo/tangnano9k/    top, cst, Makefile
  demo/icesugar/      top, pcf, Makefile
  demo/mimas_j1/      Mimas V2: J1 + SwapForth + wyświetlacz 7-seg (poligon rozszerzeń J1)
  demo/mimas_rv/      Mimas V2: microrv32 + monitor przez UART + LPDDR 64 MB (MCB)
doc/                  notatki projektowe
```

# how to's
## klonowanie (submoduły: hw/lib = fpga-hw-lib, hw/cpu/j1 = J1Sc, hw/cpu/microrv32 = microrv32)
```
git clone --recurse-submodules https://github.com/TomekBarabasz/FPGA-snake
git config submodule.recurse true
git config push.recurseSubmodules check
```
Istniejący klon: `git submodule update --init`. Przed edycją w `hw/lib`: `git switch main`,
w `hw/cpu/j1`: `git switch newhope`.

## testy i generacja (z `hw/`)
```
sbt test                    # snake + plytki + testy biblioteki i CPU (aggregate hwlib, j1)
sbt snake/test
sbt "{lib}core/test"        # projekty biblioteki z prefiksem {lib}
sbt "{cpu/j1}j1/test"       # J1IsaTest (regresja ISA po porcie)
sbt "{cpu/microrv32/microrv32}microrv32/test"   # riscv-tests (48) rdzenia microrv32
sbt "demo-tangnano9k/runMain newhope.tangnano9k.SnakeDemoTangNano9kVerilog"
sbt "demo-icesugar/runMain newhope.icesugar.SnakeDemoIceSugarVerilog"
```
Backend symulacji: `VERTEBRA_BACKEND=verilator` albo w sesji sbt `backendVrl` (patrz `lib/sbt/HwBuild.scala`).

## synteza i wgrywanie (z `hw/demo/<płytka>`)
```
make gen        # Verilog ze Spinala (sbt z hw/)
make            # bitstream w build/
make prog       # Tang Nano: SRAM, `make flash` do flasha; iCESugar: icesprog
```
Narzędzia z [oss-cad-suite](https://github.com/YosysHQ/oss-cad-suite-build) albo z pip
(`yowasp-yosys`, `yowasp-nextpnr-ice40`, `yowasp-nextpnr-himbaechel-gowin`, `apycula`):
```
make YOSYS=yowasp-yosys NEXTPNR=yowasp-nextpnr-himbaechel-gowin                  # tangnano9k
make YOSYS=yowasp-yosys NEXTPNR=yowasp-nextpnr-ice40 ICEPACK=yowasp-icepack      # icesugar
```

## start container
from hw folder
```bash
docker run --rm -ti -v .:/workspace -w /workspace -v .\.sbt:/sbt ghcr.io/spinalhdl/docker:master
```

## CPU: J1Sc
`hw/cpu/j1` to [fork J1Sc](https://github.com/TomekBarabasz/J1Sc) (gałąź `newhope`) przeniesiony
na Scala 2.13 / SpinalHDL 1.12 — te same wersje co fpga-hw-lib, więc oba buildy ładują się
w jednej sesji sbt. Projekt `snake` zależy od `j1` (`ProjectRef(file("cpu/j1"), "j1")`),
klasy w pakiecie `j1sc` (`J1`, `J1Config`, peryferia).
Zmiany względem upstreamu: `hw/cpu/j1/NEWHOPE.md`; plan i stan: `doc/J1Sc.md`.

Firmware Forth (`forth16`) wymaga gforth: `cd hw/cpu/j1/toolchain/forth && make`.

## CPU: microrv32 (RISC-V)
`hw/cpu/microrv32` to [fork microrv32](https://github.com/TomekBarabasz/microrv32) (gałąź `newhope`,
RV32IMC w SpinalHDL, Uni Bremen) przeniesiony na Scala 2.13 / Spinal 1.12; zmiany w
`hw/cpu/microrv32/NEWHOPE.md`. Użycie: `hw/demo/mimas_rv` (monitor przez UART + LPDDR).
