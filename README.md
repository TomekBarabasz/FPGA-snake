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
  snake/              logika niezależna od płytki: newhope.snake (gra), newhope.snake.board (ResetGen)
  demo/tangnano9k/    top, cst, Makefile
  demo/icesugar/      top, pcf, Makefile
doc/                  notatki projektowe
```

# how to's
## klonowanie (hw/lib to submodule: fpga-hw-lib)
```
git clone --recurse-submodules https://github.com/TomekBarabasz/FPGA-snake
git config submodule.recurse true
git config push.recurseSubmodules check
```
Istniejący klon: `git submodule update --init`. Przed edycją w `hw/lib`: `git switch main`.

## testy i generacja (z `hw/`)
```
sbt test                    # snake + plytki + testy biblioteki (aggregate hwlib)
sbt snake/test
sbt "{lib}core/test"        # projekty biblioteki z prefiksem {lib}
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

## CPU: J1Sc (planowane)
Kandydat na sterowanie grą: [J1Sc](https://github.com/SteffenReith/J1Sc) (J1 w SpinalHDL, Forth).
Upstream jest na Scali 2.11 / SpinalHDL 1.4.2 / sbt 0.13, bez pakietu i z własnym
buildem dla IceBreakera/IcoBoarda, więc nie wejdzie do tego buildu bez portu
(Scala 2.13, Spinal 1.12, `hwLayout`, pakiet). Plan: fork na koncie
(`TomekBarabasz/J1Sc`), port w forku, submodule `hw/cpu/j1`. Szczegóły: `doc/J1Sc.md`.
