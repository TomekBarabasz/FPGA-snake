# demo/tangnano9k — wąż na 6 LED-ach

Top `SnakeDemoTangNano9k`: `LedSnake(6, 27 MHz / 8, length = 2)` — głowa z ogonem
odbija się od końców linijki, 8 kroków/s.

| Port | Pin | Rola |
| --- | --- | --- |
| `clk` | 52 | 27 MHz |
| `btn_s1_n` | 3 | S1: reset (power-on reset jest i bez niego, `ResetGen`) |
| `btn_s2_n` | 4 | S2: pauza, dopóki wciśnięty |
| `led_n[5:0]` | 10, 11, 13, 14, 15, 16 | LED-y, aktywne nisko |

```
sbt "demo-tangnano9k/runMain newhope.tangnano9k.SnakeDemoTangNano9kVerilog"   # z hw/
make && make prog                                                             # stąd
```

yosys + nextpnr-himbaechel (2026-11): 91 LUT4, 46 ALU, 51 DFF, 9 IOB; Fmax 217 MHz przy wymaganiu 27 MHz.

Uwaga: Spinal stawia `keep_hierarchy` na `BufferCC`, a `synth_gowin` wstawia
wtedy bufor IO na wyjściu synchronizatora („Unconstrained IO …buffercc.io_dataOut”).
Makefile zdejmuje ten atrybut (`attrmap -remove keep_hierarchy`) przed syntezą.
