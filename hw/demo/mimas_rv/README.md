# demo/mimas_rv — microrv32 (RISC-V) na Mimas V2: monitor przez UART + LPDDR

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

## Budowanie
```
sbt "demo-mimas-rv/runMain newhope.mimasrv.MimasRvVerilog"     # z hw/ -> demo/mimas_rv/hw/gen/MimasRv.v
sbt "demo-mimas-rv/test"                                       # symulacja SoC (Verilator)
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
- **`SimpleBusToMig`**: każdy dostęp CPU (słowo, pół, bajt) to jedna komenda MCB `bl = 0`
  na 128-bitowym słowie; zapis z maską bajtów, odczyt z wyborem pasa. Prosto, nie szybko
  (kilkadziesiąt taktów na dostęp) — przepustowość MCB wykorzystuje dopiero `MigBurstEngine`.
  Do kalibracji most nie przyjmuje transakcji, dlatego monitor najpierw sprawdza status.
- **Rejestry płytki** (`board` w `MimasRvSoc`) — wzorzec własnego peryferium: `SimpleBusSlaveFactory`
  + `read` / `readAndWrite` pod adresem, tak samo jak `peripheralBusCtrl` w J1.

## Sprawdzone / do sprawdzenia
- `riscv-tests` rdzenia po porcie: 48/48 (rv32ui, rv32um, rv32uc).
- Symulacja SoC (`MimasRvSocTest`): prawdziwy `monitor.hex`, UART 115200, model portu MCB:
  banner, `led`, `seg`, `sw`, odczyt BRAM, zapis/odczyt słowa i bajtu w LPDDR (sprawdzony też
  pas i maska w modelu), `mt 4` → OK, brak kalibracji nie zawiesza monitora.
- yosys `synth_xilinx -family xc6s -flatten` (sam SoC, bez MCB): ok. 2 950 LUT, 1 150 FF,
  8 RAMB16, 14 RAM32M; z soft-kalibracją MCB (~300 LUT) ok. 57% XC6SLX9.
- **Na płytce nie uruchamiane**: timing w ISE przy 50 MHz (rdzeń + most), kalibracja MCB
  przy 150 MHz (voice-changer działał z tymi samymi pinami i ograniczeniami przy 150 MHz / 75 MHz UI).
