package newhope.icesugar

import spinal.core._
import spinal.lib._
import newhope.core.Conventions
import newhope.snake.LedSnake
import newhope.snake.board.ResetGen

// =====================================================================
//  Demo iCESugar v1.5 (iCE40UP5K-SG48): waz na 3 kolorach diody RGB
//  (R -> G -> B i z powrotem, ogon miesza kolory). Pasuje do
//  pcf/SnakeDemoIceSugar.pcf (nazwy portow = nazwy w pcf).
//
//    clk                 12 MHz (pin 35)
//    sw[0]               pauza (SW1 na plytce; sw[3:1] na razie nieuzywane)
//    led_r/g/b_n         dioda RGB, aktywne nisko
//  Plytka nie ma przycisku resetu: tylko power-on reset (ResetGen).
//
//    sbt "demo-icesugar/runMain newhope.icesugar.SnakeDemoIceSugarVerilog"
//    -> demo/icesugar/hw/gen/SnakeDemoIceSugar.v ; dalej make w demo/icesugar
// =====================================================================
class SnakeDemoIceSugar extends Component {
  val io = new Bundle {
    val clk     = in  Bool()
    val sw      = in  Bits(4 bits)
    val led_r_n = out Bool()
    val led_g_n = out Bool()
    val led_b_n = out Bool()
  }
  noIoPrefix()

  val freq = 12 MHz
  val cd   = ResetGen.domain(io.clk, btn_n = True, freq)

  val logic = new ClockingArea(cd) {
    val snake = LedSnake(width = 3, tickCycles = (freq.toBigDecimal / 4).toBigInt, length = 2)   // 4 kroki/s
    snake.io.pause := BufferCC(io.sw(0), init = False)
    io.led_r_n := !snake.io.leds(0)
    io.led_g_n := !snake.io.leds(1)
    io.led_b_n := !snake.io.leds(2)
  }
}

object SnakeDemoIceSugarVerilog extends App {
  Conventions.spinal.generateVerilog(new SnakeDemoIceSugar)
}
