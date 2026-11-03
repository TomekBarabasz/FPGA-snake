package newhope.mimasj1

import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite
import j1sc._
import j1sc.boards.J1MimasV2
import newhope.mimas_v2.SevenSegMux

// =====================================================================
//  J1MimasV2DisplaySoc: Forth po UART pisze rejestr 0x60, test czyta
//  piny seg/en multipleksera i dekoduje je z powrotem na kody cyfr.
//  UART 625 kbaud tylko po to, zeby symulacja byla krotka.
// =====================================================================
class J1MimasV2DisplaySocTest extends AnyFunSuite {
  val baud   = 625000
  val bitCyc = (50e6 / baud).toInt

  lazy val compiled = SimConfig
    .withConfig(SpinalConfig(defaultConfigForClockDomains = J1MimasV2.globalClockConfig,
                             defaultClockDomainFrequency  = FixedFrequency(50 MHz)))
    .withVerilator.workspacePath("simWorkspace").workspaceName("j1mimasv2_display")
    .compile(new J1MimasV2DisplaySoc(J1Config.forth16, CoreConfig.mimasV2(baud)))

  class Session(val d : J1MimasV2DisplaySoc) {
    val cd = d.clockDomain
    val rx = new StringBuilder
    fork {
      while (true) {
        cd.waitSamplingWhere(!d.io.uart.txd.toBoolean)
        cd.waitSampling(bitCyc + bitCyc / 2)
        var c = 0
        for (i <- 0 until 8) { if (d.io.uart.txd.toBoolean) c |= 1 << i; cd.waitSampling(bitCyc) }
        rx += c.toChar
      }
    }
    def sendByte(c : Int) : Unit = {
      d.io.uart.rxd #= false; cd.waitSampling(bitCyc)
      for (i <- 0 until 8) { d.io.uart.rxd #= ((c >> i) & 1) == 1; cd.waitSampling(bitCyc) }
      d.io.uart.rxd #= true; cd.waitSampling(bitCyc * 3)
    }
    /** Tryb tethered (` 1 tth !`): kazda odpowiedz konczy sie 0x1E. */
    var tethered = false
    /** Linia do Fortha; czeka na 0x1E (tethered) albo "ok"/"?" (interaktywnie). */
    def command(line : String) : String = {
      val from = rx.length
      (line + "\r").foreach(c => sendByte(c.toInt))
      var t = 0
      def done = {
        val r = rx.substring(from)
        if (tethered) r.contains("\u001e") else r.contains("ok\r\n") || r.contains("?")
      }
      while (!done && t < 20000000) { cd.waitSampling(200); t += 200 }
      assert(done, s"no answer to '$line', got: ${rx.substring(from)}")
      rx.substring(from).replace("\u001e", "")
    }
    /** Kody trzech cyfr i kropek, zdekodowane z pinow (aktywne nisko). */
    def shown() : Seq[(Int, Boolean)] = (0 until 3).map { i =>
      cd.waitSamplingWhere(d.display.en.toInt == (~(1 << i) & 7))
      cd.waitSampling(10)
      val seg  = ~d.display.seg.toInt & 0xff
      val code = SevenSegMux.lut.indexOf(seg & 0x7f) match {
        case -1 => fail(f"unknown segment pattern 0x${seg & 0x7f}%02x on digit $i")
        case c  => c
      }
      (code, (seg & 0x80) != 0)
    }
    def digits() : String = shown().map { case (c, dot) =>
      (if (c <= 9) ('0' + c).toChar else if (c == SevenSegMux.minus) '-' else if (c == SevenSegMux.letterE) 'E' else ' ').toString +
      (if (dot) "." else "")
    }.mkString
  }

  def session(body : Session => Unit) : Unit = compiled.doSim { d =>
    d.io.dips #= 0; d.io.buttons #= 0; d.io.extInt #= 0; d.io.uart.rxd #= true
    d.clockDomain.forkStimulus(20)
    d.clockDomain.assertReset(); d.clockDomain.waitSampling(10); d.clockDomain.deassertReset()
    d.clockDomain.waitSampling(20000)
    body(new Session(d))
  }

  test("po resecie wyswietlacz zgaszony") {
    session { s => assert(s.digits() == "   ") }
  }

  test("291 96 io!  (0x123)  ->  \"123\", odczyt 96 io@ .x -> 0123") {
    session { s =>
      s.command("291 96 io!")
      assert(s.digits() == "123")
      assert(s.command("96 io@ .x").toUpperCase.contains("0123 "))
    }
  }

  test("kropki i kody specjalne: 0x5B9A -> \"-.9 .\"") {
    // lewa = B (minus) z kropka (bit 14), srodek 9, prawa A (puste) z kropka (bit 12)
    session { s =>
      s.command(s"${0x5B9A} 96 io!")
      assert(s.digits() == "-.9 .")
    }
  }

  test("README: po swapforth.fs  : >bcd ...  456 >bcd 96 io!  ->  \"456\"") {
    session { s =>
      s.tethered = true
      s.command(" 1 tth !")
      val dir = new java.io.File(sys.env.getOrElse("J1SCBASE", "../../cpu/j1"), "toolchain/forth")
      def include(f : java.io.File) : Unit = {
        val src = scala.io.Source.fromFile(f)
        try for (l0 <- src.getLines()) {
          val l = l0.replace("\t", "    ")
          l.trim.split("\\s+").toList match {
            case ("include" | "#include") :: file :: _ => include(new java.io.File(dir, file))
            case _ => val r = s.command(l); assert(!r.contains("error"), s"'$l' -> $r")
          }
        } finally src.close()
      }
      include(new java.io.File(dir, "swapforth.fs"))
      for (l <- MimasJ1Forth.bcdWords) { val r = s.command(l); println(s"[bcd] $l -> ${r.replace("\r", "\\r").replace("\n", "\\n")}"); assert(!r.contains("error") && !r.contains("?"), s"$l -> $r") }
      println("[bcd] 456 >bcd .x -> " + s.command("456 >bcd .x").replace("\r\n", " "))
      s.command("456 show")
      assert(s.digits() == "456")
      s.command("7 show")
      assert(s.digits() == "007")
    }
  }
}

/** Slowa z README, sprawdzane w tescie powyzej. */
object MimasJ1Forth {
  val bcdWords = Seq(
    "decimal",
    ": >bcd  ( n -- x )  10 /mod 10 /mod  8 lshift swap 4 lshift or or ;",
    ": show  ( n -- )  >bcd 96 io! ;"
  )
}
