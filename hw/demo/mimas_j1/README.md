# demo/mimas_j1 — J1 + SwapForth na Mimas V2 z wyświetlaczem 7-seg

Bazą jest `J1MimasV2Soc` z `cpu/j1` (J1Sc, gałąź `newhope`): CPU, UART (115200 dla firmware'u PIC-a jimmo, 19200 dla fabrycznego), LED-y,
DIP-y, przyciski, timery i przerwania (opis: `cpu/j1/src/main/xilinx/mimasv2/README.md`).
Tutaj dochodzi rozszerzenie: wyświetlacz 3 × 7-seg pod adresem `0x60`, sterowany
przez `SevenSegMux` z `lib/mimas_v2`.

| Adres | Co |
| --- | --- |
| `40` | LED D1..D8 |
| `60` | **wyświetlacz** (r/w), opis niżej |
| `80` | DIP DP1..DP8, 1 = ON |
| `90` | przyciski SW1..SW6, 1 = wciśnięty (SW1 = reset) |
| `C0` / `D0` / `E0` / `F0` | timer A / timer B / przerwania / UART |

## Rejestr wyświetlacza `0x60`

| Bity | 14 | 13 | 12 | 11..8 | 7..4 | 3..0 |
| --- | --- | --- | --- | --- | --- | --- |
| | kropka lewej | kropka środkowej | kropka prawej | lewa cyfra | środkowa | prawa |

Kody cyfr jak w `SevenSegMux`: `0`..`9`, `A` = puste, `B` = minus, `C` = E, `D`..`F` = puste.
Po resecie `0AAA` (zgaszony). Z liczbą hex wpisaną wprost widać ją na wyświetlaczu:
```
hex 123 60 io!        \ "123"
5B9A 60 io!           \ "-.9 ." (minus z kropką, 9, puste z kropką)
60 io@ .              \ odczyt rejestru
```
Gołe jądro (bez `swapforth.fs`) liczy dziesiętnie i drukuje przez `.x`:
`291 96 io!` daje to samo „123”.

Liczba dziesiętna 0..999 (po wgraniu `swapforth.fs`, słowa sprawdzone w teście):
```
decimal
: >bcd  ( n -- x )  10 /mod 10 /mod  8 lshift swap 4 lshift or or ;
: show  ( n -- )  >bcd 96 io! ;
456 show              \ "456"
7 show                \ "007"
```

## Jak dołożyć własne rozszerzenie J1
Całe rozszerzenie to klasa `J1MimasV2DisplaySoc` w `hw/spinal/main/J1MimasV2Display.scala`:

1. **Dziedziczysz SoC** z `cpu/j1`: `class MojSoc(...) extends J1MimasV2Soc(...)`.
   Masz w nim `peripheralBusCtrl` — magistralę IO procesora (`BusSlaveFactory` ze spinal.lib).
2. **Stan + adres**: rejestr i mapowanie pod wolny adres, np.
   `peripheralBusCtrl.readAndWrite(value, address = 0x60)`. Inne warianty:
   `read(sygnał, adres)` (tylko odczyt, np. licznik), `write(rejestr, adres)`,
   `onWrite(adres) { ... }` (akcja na zapis, np. start transmisji).
   Wolne adresy to np. `0x50` i `0x70` (na Nexys4 PWM i GPIO, tu nieużywane) oraz `0xA0`–`0xBF`.
   Przestrzeń IO jest osobna od pamięci, więc adres IO nie zabiera RAM-u.
3. **Sprzęt + piny**: komponent korzystający z rejestru (tu `SevenSegMux`) i nowy
   `Bundle` z `in`/`out` w SoC; w topie płytki podłączasz go do pinów i dopisujesz UCF.
4. **Forth**: `io!` / `io@` pod tym adresem; wygodne słowa (`: show ... ;`) definiujesz
   w terminalu albo w pliku `.fs` wgrywanym przez `j1term.py`.
5. **Test**: symulacja jak `J1MimasV2DisplaySocTest` — Forth po UART, sprawdzenie pinów.

## Budowanie i uruchomienie
```
cd hw/cpu/j1/toolchain/forth && make && cd ../../..                     # obrazy SwapForth (gforth): nuc, nuc-mul, nuc-mac
sbt "demo-mimas-j1/runMain newhope.mimasj1.J1MimasV2DisplayVerilog"        # z hw/, UART 115200 -> demo/mimas_j1/hw/gen/J1MimasV2Display.v
sbt "demo-mimas-j1/runMain newhope.mimasj1.J1MimasV2DisplayVerilog 19200"  # fabryczny firmware PIC-a
sbt "demo-mimas-j1/runMain newhope.mimasj1.J1MimasV2DisplayVerilog mul"    # mnozenie na 1 x DSP48A1 (mozna z 19200)
sbt "demo-mimas-j1/runMain newhope.mimasj1.J1MimasV2DisplayVerilog mac"    # mnozenie + MAC na 2 x DSP48A1
sbt "demo-mimas-j1/test"                                                # symulacja (Verilator)
```
ISE 14.7: XC6SLX9, CSG324, speed -3, top `J1MimasV2Display`, źródła
`hw/gen/J1MimasV2Display.v` i `ucf/J1MimasV2Display.ucf`; „Create Binary
Configuration File” → `.bin`. Wgranie i porty COM zależą od firmware'u PIC-a
(`cpu/j1/src/main/xilinx/mimasv2/README.md` §0, §3). Firmware jimmo: `.bin` przez port
z `mimas>`, a Forth na **drugim** porcie płytki:
```
python3 hw/cpu/j1/toolchain/forth/j1term.py -d COM14 hw/cpu/j1/toolchain/forth/swapforth.fs     # COM = port UART FPGA
```

## Mnożenie i MAC (opcje `mul`, `mac`)
`mul`: J1 z `J1MulUnit` na jednym prymitywie DSP48A1 (rejestry w bloku DSP) — szybsze `*`, `um*`, `m*`.
`mac`: drugi DSP48A1 z akumulatorem 48 b — `acc0 acc! mac macs acc@ acc@x`. Np. iloczyn skalarny
do wyświetlacza (opcja `mac`):
```
decimal acc0  3 4 mac  5 6 mac  acc@ drop show      \ "042"
```
Kodowanie, potok i blokada: `cpu/j1/NEWHOPE.md`.

## Sprawdzone / do sprawdzenia
- Symulacja: zgaszony po resecie, `291 96 io!` → „123” (zdekodowane z pinów `seg`/`en`),
  kropki i minus, odczyt rejestru, `>bcd`/`show` po wgraniu `swapforth.fs`.
- yosys `synth_xilinx -family xc6s`: ok. 926 LUT (wyświetlacz ok. +46), 4 RAMB16, DCM_SP.
- Piny wyświetlacza i kolejność DIP-ów jak w NewHope/aht10 (`en[0]` = B2 = lewa cyfra).
- **Na płytce nie uruchamiane**; timing w ISE przy 50 MHz do sprawdzenia.
