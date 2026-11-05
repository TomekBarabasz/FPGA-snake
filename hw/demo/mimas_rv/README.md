# demo/mimas_rv — microrv32 (RISC-V) na Mimas V2: monitor przez UART + LPDDR, SimpleBus albo AXI4

CPU [microrv32](https://github.com/TomekBarabasz/microrv32) (RV32IM, wielocyklowy, gałąź `newhope`,
submodule `hw/cpu/microrv32`) z programem w BRAM. Program to mały **monitor**: konsola przez
UART, z której czytasz i piszesz dowolny adres. Do tego pełne 64 MB **LPDDR** przez MCB
z `lib/mimas_v2/.../mcb` (`McbCore`), dostępne dla CPU jak zwykła pamięć pod `0x90000000`.

| Adres | Co |
| --- | --- |
| `02000000` | CLIC: `mtime` (+`bff8`, takty 50 MHz), `mtimecmp` (+`4000`) |
| `80000000` | BRAM 16 KB: program (`sw/monitor.hex`), stos na końcu |
| `81000000` | LED D1..D8 |
| `82000000` | UART 115200 8N1 (`+0` dane TX, `+4` start/gotowość TX, `+8` dane RX, `+14` RX pusty) |
| `84000000` | płytka: `+0` DIP (1 = ON), `+4` przyciski SW1..SW6, `+8` 7-seg, `+C` status LPDDR |
| `90000000` | **LPDDR 64 MB** (bit 0 statusu = skalibrowana, bit 1 = błąd portu MCB) |

Rejestr 7-seg jak w `demo/mimas_j1`: bity 11..8 / 7..4 / 3..0 = cyfry od lewej, 14..12 = kropki,
kody 0..9, `A` = puste, `B` = minus, `C` = E.

## Monitor (UART 115200, terminal na **porcie UART FPGA**)
```
microrv32 @ Mimas V2 - monitor (help)
rv> led 5a                 LED-y
rv> seg 123                wyświetlacz "123"
rv> sw                     DIP a5  BTN 05
rv> st                     LPDDR: skalibrowana
rv> w 90000000 deadbeef    zapis słowa (też wb ADDR VAL - bajt)
rv> r 90000000 4           odczyt 4 słów
rv> mt 400                 test LPDDR 1 MB (KB w hex): wynik i czas zapisu/odczytu
```
Liczby w hex. Dowolny terminal (PuTTY, picocom); `j1term.py` tu nie pasuje — to nie Forth.
Firmware PIC-a jimmo: drugi port COM płytki (nie ten z `mimas>`). Fabryczny firmware
PIC-a ma 19200 bodów — ten SoC go nie obsługuje (`SBSUart` w microrv32 ma stałe 115200).

## Dwa warianty magistrali (`MimasRvConfig.bus`)
Ta sama mapa adresów, ten sam `monitor.hex`, te same testy — różni się tylko połączenie:

```
SimpleBus:  microrv32 ─SimpleBus─► SimpleBusDecoder ─┬─► BRAM (ByteAddressableMemory)
                                                     ├─► CLIC+Shutdown / LED / UART / płytka (SimpleBusSlaveFactory)
                                                     └─► SimpleBusToMig ─► port MCB

Axi4:       microrv32 ─SimpleBus─► SimpleBusToAxi4 ─AXI4─► Axi4Crossbar ─┬─► BRAM (Axi4SharedOnChipRam)
                                                                         ├─► AXI4→AXI-Lite: CLIC+Shutdown (AxiLite4SlaveFactory)
                                                                         ├─► AXI4→AXI-Lite: LED, UART, płytka (AxiLite4SlaveFactory)
                                                                         └─► Axi4ToMig ─► port MCB (bursty → komendy MCB)
```
- **Peryferia** (`MimasRvPeripherals.scala`) dostają `BusSlaveFactory` i adres bazowy — ten sam kod
  działa za `SimpleBusSlaveFactory` i za `AxiLite4SlaveFactory`. Nowe peryferium = nowa `Area`
  z `bus.read` / `readAndWrite` / `onWrite`, niezależnie od magistrali.
- **`SimpleBusToAxi4`**: CPU robi jeden dostęp naraz, więc zawsze pojedynczy beat (`len = 0`),
  `AxSIZE` = rozmiar dostępu, dane przesunięte na swoje pasy bajtowe, `WSTRB` z rozmiaru i adresu.
- **`Axi4ToMig`** (`MimasRvBridges.scala`) — tu jest AXI4 „na serio”: bursty INCR do 256 beatów
  (32 b) pakowane w słowa 128 b portu MCB. Komenda MCB ma najwyżej 64 słowa i nie przecina wiersza
  2 kB, więc burst jest dzielony (256 × 4 B od początku słowa = dokładnie 1 komenda 64 słów; od
  środka słowa = 64 + 1). Zapis: dane do FIFO przed komendą, `WSTRB` → maska, `B` po ostatniej
  komendzie. Odczyt: komenda na kawałek, słowa rozpakowywane na beaty `R` z `RLAST`.
  Nieobsługiwane (jak INCR): FIXED, WRAP, wąskie bursty. CPU burstów nie robi — ćwiczy je test.
- **AXI-Lite** przez `Axi4.toLite()` (`AxiLite4Utils`): to tylko przepięcie sygnałów, poprawne dla
  pojedynczych beatów (CPU innych nie wysyła).

## Budowanie
```
sbt "demo-mimas-rv/runMain newhope.mimasrv.MimasRvVerilog"          # z hw/, SimpleBus -> demo/mimas_rv/hw/gen/MimasRv.v
sbt "demo-mimas-rv/runMain newhope.mimasrv.MimasRvVerilog axi4"     # wariant AXI4 + AXI-Lite (ten sam UCF i pliki MIG)
sbt "demo-mimas-rv/test"                                            # symulacja: monitor x 2 warianty + Axi4ToMig
sbt "{cpu/microrv32/microrv32}microrv32/test"                  # riscv-tests rdzenia (48)
```
`MimasRv.v` ma program wpisany w blok `initial` (pliki `.bin` obok nie są potrzebne).

**ISE 14.7**: XC6SLX9, CSG324, speed -3, top `MimasRv`. Źródła: `hw/gen/MimasRv.v`,
`ucf/MimasRv.ucf` i **dziewięć plików MIG** z `hw/lib/mimas_v2/hw/spinal/main/mcb/MIG-128b/`
(`s6_lpddr.v`, `infrastructure.v`, `memc_wrapper.v`, `mcb_ui_top.v`, `mcb_raw_wrapper.v`,
`mcb_soft_calibration.v`, `mcb_soft_calibration_top.v`, `iodrp_controller.v`,
`iodrp_mcb_controller.v`). „Create Binary Configuration File” → `.bin`.

**Własny program**: `sw/` (`crt0.S`, `link.ld`, `mimas_rv.h`, `monitor.c`). Potrzebny
`riscv64-unknown-elf-gcc` (Ubuntu: `apt install gcc-riscv64-unknown-elf`, Windows: xPack RISC-V GCC):
```
cd sw && make          # -> monitor.hex (4096 słów), potem ponowna generacja Verilogu
```
Inny program: podmień `monitor.c` albo `MimasRvConfig(hexFile = ...)`; musi się zmieścić w 16 KB.

## Jak to jest zbudowane
- **Zegar**: cały SoC w domenie `c3_clk0` z MCB — 100 MHz → PLL w `s6_lpddr` → pamięć 150 MHz,
  logika 50 MHz (`MigConfig.forClocks(150, 50)`). Bez DCM. SW1 resetuje tylko CPU
  (zawartość LPDDR zostaje).
- **`SimpleBusToMig`** (wariant SimpleBus): każdy dostęp CPU (słowo, pół, bajt) to jedna komenda MCB `bl = 0`
  na 128-bitowym słowie; zapis z maską bajtów, odczyt z wyborem pasa. Prosto, nie szybko
  (kilkadziesiąt taktów na dostęp) — przepustowość MCB wykorzystuje dopiero `MigBurstEngine`.
  Do kalibracji most nie przyjmuje transakcji, dlatego monitor najpierw sprawdza status.
- **Rejestry płytki** (`Board` w `MimasRvPeripherals`) — wzorzec własnego peryferium: fabryka
  + `read` / `readAndWrite` pod adresem, tak samo jak `peripheralBusCtrl` w J1.

## Sprawdzone / do sprawdzenia
- `riscv-tests` rdzenia po porcie: 48/48 (rv32ui, rv32um, rv32uc).
- Symulacja SoC (`MimasRvSocSimpleBusTest`, `MimasRvSocAxi4Test` — te same 7 testów dla obu
  wariantów): prawdziwy `monitor.hex`, UART 115200, model portu MCB (`MigPortModel`, pilnuje reguł
  MCB: dane przed komendą, ≤ 64 słowa, bez przecinania wiersza, FIFO odczytu): banner, `led`, `seg`,
  `sw`, odczyt BRAM, zapis/odczyt słowa i bajtu w LPDDR, `mt 4` → OK, brak kalibracji nie zawiesza.
- `Axi4ToMigTest`: master AXI4 ze `StreamDriver`/`StreamMonitor` (losowe przerwy, losowy `ready`
  na B/R): pojedyncze beaty na każdym pasie, burst 256 beatów = 1 komenda 64 słów, burst od środka
  słowa (64 + 1) i przez granicę wiersza 2 kB, 200 losowych burstów z losowym `WSTRB` vs referencja,
  brak przyjmowania AW/AR przed kalibracją.
- yosys `synth_xilinx -family xc6s -flatten` (sam SoC, bez MCB):

  | Wariant | LUT | FF | RAMB16 | RAM32M |
  | --- | ---: | ---: | ---: | ---: |
  | SimpleBus | ok. 3 020 | 1 130 | 8 | 14 |
  | AXI4 + AXI-Lite | ok. 3 070 | 1 630 | 8 | 22 |

  Z soft-kalibracją MCB (~300 LUT) ok. 58–59% XC6SLX9.
- **Na płytce nie uruchamiane**: timing w ISE przy 50 MHz (rdzeń + most), kalibracja MCB
  przy 150 MHz (voice-changer działał z tymi samymi pinami i ograniczeniami przy 150 MHz / 75 MHz UI).
