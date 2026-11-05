package newhope.mimasrv

import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite
import scala.collection.mutable
import newhope.mcb._

// =====================================================================
//  MimasRvSoc (oba warianty magistrali) z prawdziwym sw/monitor.hex: polecenia monitora przez UART
//  (115200, 50 MHz) i model portu MCB w miejscu LPDDR.
//
//  Model portu: pamiec 128-bitowych slow (Map), cmd/wr zawsze gotowe,
//  zapis = slowo z kolejki wr z maska (1 = bajt pominiety), odczyt =
//  dane po `latency` taktach na rd (FIFO first-word-fall-through jak MCB).
//  MCB i jego kalibracji symulacja nie pokazuje - to sprawdza dopiero plytka.
// =====================================================================
/** Model portu MCB w symulacji: pamiec slow 128 b, cmd/wr zawsze gotowe, zapis = slowo
  * z kolejki wr z maska (1 = bajt pominiety), odczyt = `bl + 1` slow na rd po `latency`
  * taktach (FIFO first-word-fall-through jak MCB). Pilnuje regul portu: dane zapisu
  * w kolejce przed komenda, nie wiecej niz 64 slowa w FIFO odczytu, burst <= 64 slowa
  * i w obrebie wiersza 2 kB. */
object MigPortModel {
  def apply(p : MigPort, cd : ClockDomain, mem : mutable.Map[Long, BigInt], latency : Int,
            onCmd : (Int, Long, Int) => Unit = (_, _, _) => ()) : Unit = {
    val wrQ = mutable.Queue[(BigInt, BigInt)]()
    val rdQ = mutable.Queue[(Long, BigInt)]()                               // (takt gotowosci, dane)
    var t = 0L
    p.cmd.ready #= true
    p.wr.ready  #= true
    p.rd.valid  #= false
    // onSamplings: odczyty widza stan sprzed zbocza niezaleznie od kolejnosci watkow
    // symulacji, przypisania obowiazuja od nastepnego taktu.
    cd.onSamplings {
      t += 1
      if (p.wr.valid.toBoolean) wrQ += (p.wr.payload.data.toBigInt -> p.wr.payload.mask.toBigInt)
      if (p.rd.valid.toBoolean && p.rd.ready.toBoolean) rdQ.dequeue()
      if (p.cmd.valid.toBoolean) {
        val a  = p.cmd.payload.addr.toLong
        val bl = p.cmd.payload.bl.toInt + 1
        onCmd(p.cmd.payload.instr.toInt, a, bl)
        assert(a % 16 == 0, f"adres komendy 0x$a%x niewyrownany do 16 B")
        assert(a / 2048 == (a + 16 * bl - 1) / 2048, f"burst $bl slow od 0x$a%x przecina wiersz 2 kB")
        p.cmd.payload.instr.toInt match {
          case 0 =>                                                         // WRITE
            assert(wrQ.size >= bl, s"WRITE bl=$bl, w kolejce ${wrQ.size} slow (wr_underrun)")
            for (k <- 0 until bl) {
              val (data, mask) = wrQ.dequeue()
              var w = mem(a + 16 * k)
              for (b <- 0 until 16 if !mask.testBit(b)) {
                val byte = (data >> (8 * b)) & 0xff
                w = (w & ~(BigInt(0xff) << (8 * b))) | (byte << (8 * b))
              }
              mem(a + 16 * k) = w
            }
          case 1 =>                                                         // READ
            assert(rdQ.size + bl <= 64, s"READ bl=$bl przy ${rdQ.size} slowach w FIFO (rd_overflow)")
            for (k <- 0 until bl) rdQ += ((t + latency + k) -> mem(a + 16 * k))
          case x => assert(false, s"nieoczekiwana komenda $x")
        }
      }
      val ready = rdQ.headOption.exists(_._1 <= t)
      p.rd.valid #= ready
      if (ready) p.rd.payload #= rdQ.head._2
    }
  }
}

/** Ten sam monitor i te same testy dla obu wariantow magistrali. */
class MimasRvSocSimpleBusTest extends MimasRvSocTest(MimasRvBus.SimpleBus)
class MimasRvSocAxi4Test      extends MimasRvSocTest(MimasRvBus.Axi4)

abstract class MimasRvSocTest(bus : MimasRvBus) extends AnyFunSuite {
  val c       = MigConfig.forClocks(memMHz = 150, uiMHz = 50)
  val bitCyc  = (50e6 / 115200).round.toInt
  val latency = 12

  lazy val compiled = SimConfig
    .withConfig(SpinalConfig(defaultClockDomainFrequency = FixedFrequency(50 MHz),
                             defaultConfigForClockDomains = ClockDomainConfig(resetKind = SYNC, resetActiveLevel = HIGH)))
    .withVerilator.workspacePath("simWorkspace").workspaceName(s"mimas_rv_soc_$bus")
    .compile(new MimasRvSoc(MimasRvConfig(bus = bus), c))

  class Session(val d : MimasRvSoc, calib : Boolean) {
    val cd  = d.clockDomain
    val rx  = new StringBuilder
    val mem = mutable.Map[Long, BigInt]().withDefaultValue(BigInt(0))     // adres bajtowy slowa 128 b -> dane
    var cmds = 0

    MigPortModel(d.io.ddr, cd, mem, latency, (_, _, _) => cmds += 1)
    // ---- UART ----
    fork {
      while (true) {
        cd.waitSamplingWhere(!d.io.uart.txd.toBoolean)
        cd.waitSampling(bitCyc + bitCyc / 2)
        var ch = 0
        for (i <- 0 until 8) { if (d.io.uart.txd.toBoolean) ch |= 1 << i; cd.waitSampling(bitCyc) }
        rx += ch.toChar
      }
    }
    def sendByte(ch : Int) : Unit = {
      d.io.uart.rxd #= false; cd.waitSampling(bitCyc)
      for (i <- 0 until 8) { d.io.uart.rxd #= ((ch >> i) & 1) == 1; cd.waitSampling(bitCyc) }
      d.io.uart.rxd #= true; cd.waitSampling(bitCyc * 2)
    }
    def waitFor(what : String, from : Int = 0, maxCycles : Int = 3000000) : String = {
      var n = 0
      while (!rx.substring(from).contains(what) && n < maxCycles) { cd.waitSampling(500); n += 500 }
      val got = rx.substring(from)
      assert(got.contains(what), s"'$what' nie przyszlo, jest: ${got.replace("\r", "\\r").replace("\n", "\\n")}")
      got
    }
    /** Polecenie monitora; zwraca odpowiedz do nastepnego "rv> " (bez echa polecenia). */
    def cmd(line : String) : String = {
      val from = rx.length
      (line + "\r").foreach(ch => sendByte(ch.toInt))
      val r = waitFor("rv> ", from)
      r.stripPrefix(line).stripSuffix("rv> ").trim
    }
  }

  def session(calib : Boolean = true, dips : Int = 0, buttons : Int = 0)(body : Session => Unit) : Unit =
    compiled.doSim { d =>
      d.io.dips #= dips; d.io.buttons #= buttons; d.io.uart.rxd #= true
      d.io.calib #= calib; d.io.faults #= 0                // przed resetem: bit bledu jest lepki
      d.clockDomain.forkStimulus(20)
      d.clockDomain.assertReset(); d.clockDomain.waitSampling(10); d.clockDomain.deassertReset()
      val s = new Session(d, calib)
      s.waitFor("rv> ")
      body(s)
    }

  test("start: banner i znak zachety") {
    session() { s => assert(s.rx.toString.contains("microrv32 @ Mimas V2")) }
  }

  test("led 5a -> LED-y, seg 123 -> rejestr wyswietlacza") {
    session() { s =>
      s.cmd("led 5a")
      assert(s.d.io.leds.toInt == 0x5a)
      s.cmd("seg 123")
      assert(s.cmd("r 84000008").endsWith("00000123"))
    }
  }

  test("sw: DIP a5, przyciski 05") {
    session(dips = 0xa5, buttons = 0x05) { s => assert(s.cmd("sw") == "DIP a5  BTN 05") }
  }

  test("BRAM: r 80000000 = pierwsze slowo monitor.hex") {
    session() { s =>
      val first = scala.io.Source.fromFile("sw/monitor.hex").getLines().next().trim
      val word  = first.grouped(2).toSeq.reverse.mkString.toLowerCase          // bajty w kolejnosci adresow -> slowo LE
      assert(s.cmd("r 80000000") == s"80000000: $word")
    }
  }

  test("LPDDR: zapis/odczyt slowa i bajtu przez most (model portu MCB)") {
    session() { s =>
      s.cmd("w 90000014 deadbeef")
      assert(s.cmd("r 90000014") == "90000014: deadbeef")
      assert(s.mem(0x10L) == (BigInt(0xdeadbeefL) << 32))                    // pas 1 slowa 128 b pod 0x10
      s.cmd("wb 90000017 77")
      assert(s.cmd("r 90000014") == "90000014: 77adbeef")
      assert(s.cmd("st") == "LPDDR: skalibrowana")
    }
  }

  test("LPDDR: mt 4 -> 4 KB OK") {
    session() { s =>
      val r = s.cmd("mt 4")
      assert(r.startsWith("4 KB: OK"), r)
      println(s"[MimasRvSocTest] mt 4: $r, komend MCB: ${s.cmds}")
    }
  }

  test("bez kalibracji: monitor nie wisi na LPDDR") {
    session(calib = false) { s =>
      assert(s.cmd("st") == "LPDDR: brak kalibracji")
      assert(s.cmd("r 90000000") == "LPDDR nie jest skalibrowana (st)")
    }
  }
}
