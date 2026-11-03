package newhope.snake

import spinal.core._
import spinal.lib._

// =====================================================================
//  LedSnake: "waz" na linijce LED-ow - glowa odbija sie od koncow,
//  za nia ciagnie sie ogon dlugosci length - 1. Pierwszy klocek gry,
//  na razie sluzy jako demo plytek (tangnano9k, icesugar).
//
//  Ruch co tickCycles cykli, w ktorych pause = 0 (pauza zamraza licznik,
//  wiec po wznowieniu waz rusza od tego samego miejsca w okresie).
//  Po resecie: wszystkie segmenty na pozycji 0, kierunek w gore.
//
//  Model w Scali: LedSnakeModel (test), krok po kroku jak sprzet.
// =====================================================================
case class LedSnake(width : Int, tickCycles : BigInt, length : Int = 2) extends Component {
  require(width >= 2, "linijka musi miec co najmniej 2 LED-y")
  require(length >= 1 && length <= width, s"length w 1..$width")
  require(tickCycles >= 2, "tickCycles >= 2")

  val io = new Bundle {
    val pause = in  Bool()
    val leds  = out Bits(width bits)   // aktywne wysoko; negacja w top-levelu plytki
    val tick  = out Bool()             // impuls ruchu (1 cykl)
  }

  val timer = Counter(tickCycles, inc = !io.pause)
  io.tick := timer.willOverflow

  // pos(0) = glowa, pos(length-1) = koniec ogona
  val pos     = Vec.fill(length)(Reg(UInt(log2Up(width) bits)) init 0)
  val goingUp = Reg(Bool()) init True

  when(timer.willOverflow) {
    val head   = pos(0)
    val atEnd  = Mux(goingUp, head === width - 1, head === 0)
    val nextUp = goingUp ^ atEnd
    goingUp := nextUp
    pos(0)  := Mux(nextUp, head + 1, head - 1)
    for (i <- 1 until length) pos(i) := pos(i - 1)
  }

  io.leds := pos.map(p => UIntToOh(p, width)).reduce(_ | _)
}
