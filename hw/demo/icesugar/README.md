# demo/icesugar — wąż na diodzie RGB

Top `SnakeDemoIceSugar`: `LedSnake(3, 12 MHz / 4, length = 2)` na kolorach R, G, B —
głowa z ogonem chodzi R → G → B i z powrotem (ogon miesza kolory), 4 kroki/s.

| Port | Pin | Rola |
| --- | --- | --- |
| `clk` | 35 | 12 MHz |
| `sw[0]` | 18 | SW1: pauza (który stan to ON — do sprawdzenia na płytce) |
| `sw[3:1]` | 19–21 | nieużywane; współdzielone z PMOD4 |
| `led_r_n` / `led_g_n` / `led_b_n` | 40 / 41 / 39 | dioda RGB, aktywne nisko |

Płytka nie ma przycisku resetu — tylko power-on reset (`ResetGen`, `btn_n = True`).

```
sbt "demo-icesugar/runMain newhope.icesugar.SnakeDemoIceSugarVerilog"   # z hw/
make && make prog                                                       # albo build/*.bin na dysk iCELink
```

yosys + nextpnr-ice40 (2026-11): 72 / 5 280 LC, 8 SB_IO; Fmax ~63 MHz przy wymaganiu 12 MHz.
