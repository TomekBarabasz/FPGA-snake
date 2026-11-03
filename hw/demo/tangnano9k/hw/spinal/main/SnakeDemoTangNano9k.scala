package newhope.tangnano9k

import spinal.core._
import spinal.lib._
import newhope.core.Conventions
import newhope.snake.LedSnake
import newhope.snake.board.ResetGen

// =====================================================================
//  Demo Tang Nano 9K (GW1NR-LV9QN88PC6/I5): waz na 6 LED-ach.
//  Pasuje do cst/SnakeDemoTangNano9k.cst (nazwy portow = nazwy w cst).
//
//    clk       27 MHz (pin 52)
//    btn_s1_n  S1 = reset (aktywny nisko)
//    btn_s2_n  S2 = pauza, dopoki wcisniety
//    led_n     LED 0..5, aktywne nisko
//
//    sbt "demo-tangnano9k/runMain newhope.tangnano9k.SnakeDemoTangNano9kVerilog"
//    -> demo/tangnano9k/hw/gen/SnakeDemoTangNano9k.v ; dalej make w demo/tangnano9k
// =====================================================================
class SnakeDemoTangNano9k extends Component {
  val io = new Bundle {
    val clk      = in  Bool()
    val btn_s1_n = in  Bool()
    val btn_s2_n = in  Bool()
    val led_n    = out Bits(6 bits)
  }
  noIoPrefix()

  val freq = 27 MHz
  val cd   = ResetGen.domain(io.clk, io.btn_s1_n, freq)

  val logic = new ClockingArea(cd) {
    val snake = LedSnake(width = 6, tickCycles = (freq.toBigDecimal / 8).toBigInt, length = 2)   // 8 krokow/s
    snake.io.pause := !BufferCC(io.btn_s2_n, init = True)
    io.led_n := ~snake.io.leds
  }
}

object SnakeDemoTangNano9kVerilog extends App {
  Conventions.spinal.generateVerilog(new SnakeDemoTangNano9k)
}
