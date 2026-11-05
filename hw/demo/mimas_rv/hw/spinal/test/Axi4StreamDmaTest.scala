package newhope.mimasrv

import spinal.core._
import spinal.core.sim._
import spinal.lib.bus.amba4.axi.sim.{AxiMemorySim, AxiMemorySimConfig}
import spinal.lib.sim.{StreamDriver, StreamMonitor, StreamReadyRandomizer}
import org.scalatest.funsuite.AnyFunSuite
import scala.collection.mutable
import scala.util.Random

// =====================================================================
//  Axi4StreamDma osobno: po stronie AXI4 model pamieci ze spinal.lib
//  (AxiMemorySim), strumienie przez StreamDriver / StreamMonitor /
//  StreamReadyRandomizer. Monitor AR/AW pilnuje regul burstow
//  (<= maxBurst, bez przecinania 4 kB, INCR, 4 B na beat).
// =====================================================================
class Axi4StreamDmaTest extends AnyFunSuite {
  val maxBurst = 32
  lazy val compiled = SimConfig.withVerilator.workspacePath("simWorkspace").workspaceName("axi4_stream_dma")
    .compile(Axi4StreamDma(MimasRvAxi.config.copy(idWidth = 2), maxBurst))

  class Env(val d : Axi4StreamDma) {
    val cd  = d.clockDomain
    val mem = AxiMemorySim(d.io.axi, cd, AxiMemorySimConfig())
    mem.start()
    val bursts = mutable.ArrayBuffer[(Char, Long, Int)]()          // ('R'/'W', adres, beaty)
    cd.onSamplings {
      for ((ch, ax) <- Seq('R' -> d.io.axi.ar, 'W' -> d.io.axi.aw) if ax.valid.toBoolean && ax.ready.toBoolean) {
        val a = ax.payload.addr.toLong; val n = ax.payload.len.toInt + 1
        bursts += ((ch, a, n))
        assert(n <= maxBurst, s"$ch burst $n > $maxBurst")
        assert(a / 4096 == (a + 4 * n - 1) / 4096, f"$ch burst $n beatow od 0x$a%x przecina 4 kB")
        assert(ax.payload.size.toInt == 2 && ax.payload.burst.toInt == 1)
      }
    }
    val (_, mm2sCmdQ) = StreamDriver.queue(d.io.mm2sCmd, cd)
    val (_, s2mmCmdQ) = StreamDriver.queue(d.io.s2mmCmd, cd)
    val (_, inQ)      = StreamDriver.queue(d.io.s2mm, cd)
    StreamReadyRandomizer(d.io.mm2s, cd)
    val out = mutable.Queue[(Long, Boolean)]()
    StreamMonitor(d.io.mm2s, cd) { p => out += ((p.data.toLong, p.last.toBoolean)) }

    /** Czeka, az obie komendy zostana przyjete (StreamDriver zdejmuje je z kolejki
      * juz przy wystawieniu valid, nie po handshake'u) i oba silniki skoncza. */
    // bezczynnosc liczona w onSamplings: wszystkie sygnaly z tego samego taktu (sprzed zbocza);
    // odczyt po waitSampling moglby zlozyc valid po zboczu z busy sprzed zbocza
    var idleStreak = 0
    cd.onSamplings {
      val busy = mm2sCmdQ.nonEmpty || s2mmCmdQ.nonEmpty || d.io.mm2sCmd.valid.toBoolean || d.io.s2mmCmd.valid.toBoolean ||
                 d.io.mm2sBusy.toBoolean || d.io.s2mmBusy.toBoolean
      idleStreak = if (busy) 0 else idleStreak + 1
    }
    def waitIdle(what : String, max : Int = 500000) : Unit = {
      var n = 0
      cd.waitSampling(2)
      while (idleStreak < 8 && n < max) { cd.waitSampling(); n += 1 }
      assert(n < max, s"timeout: $what")
      cd.waitSampling(10)
    }
    def wordAt(a : Long) : Long = mem.memory.readBigInt(a, 4).toLong
  }

  def env(body : Env => Unit) : Unit = compiled.doSim { d =>
    d.io.mm2sCmd.valid #= false; d.io.s2mmCmd.valid #= false; d.io.s2mm.valid #= false; d.io.mm2s.ready #= false
    d.clockDomain.forkStimulus(10)
    d.clockDomain.assertReset(); d.clockDomain.waitSampling(5); d.clockDomain.deassertReset()
    body(new Env(d))
  }

  test("MM2S: pamiec -> strumien, tlast na ostatnim beacie, bursty przez granice 4 kB") {
    env { e =>
      val rng = new Random(7)
      val words = Seq.fill(300)(rng.nextInt() & 0xFFFFFFFFL)
      val base  = 0x1F80L                                           // 32 slowa przed granica 4 kB
      for ((w, i) <- words.zipWithIndex) e.mem.memory.writeBigInt(base + 4 * i, BigInt(w), 4, BigInt(0xF))
      e.mm2sCmdQ.enqueue { p => p.addr #= base; p.bytes #= 4 * words.size }
      e.waitIdle("MM2S")
      assert(e.out.map(_._1).toSeq == words)
      assert(e.out.map(_._2).toSeq == Seq.fill(words.size - 1)(false) :+ true)
      assert(e.bursts.head == (('R', base, 32)))                    // do granicy 4 kB
      assert(e.bursts.forall(_._1 == 'R') && e.bursts.map(_._3).sum == words.size)
    }
  }

  test("S2MM: strumien -> pamiec do tlast, tkeep na ostatnim beacie") {
    env { e =>
      val words  = (0 until 77).map(i => 0xA5000000L + i)
      e.mem.memory.writeBigInt(0x3000 + 4 * 76, BigInt(0x5EA75EA7L), 4, BigInt(0xF))   // wartownik
      val before = 0x5EA75EA7L
      for ((w, i) <- words.zipWithIndex) e.inQ.enqueue { p =>
        p.data #= w; p.last #= i == words.size - 1; p.keep #= (if (i == words.size - 1) 0x3 else 0xF)
      }
      e.s2mmCmdQ.enqueue { p => p.addr #= 0x3000; p.bytes #= 4096 }          // bufor wiekszy niz pakiet
      e.waitIdle("S2MM")
      for (i <- 0 until 76) assert(e.wordAt(0x3000 + 4 * i) == words(i))
      assert((e.wordAt(0x3000 + 4 * 76) & 0xFFFF) == (words(76) & 0xFFFF))   // tylko 2 bajty z tkeep
      assert(e.wordAt(0x3000 + 4 * 76) >>> 16 == (before >>> 16))               // gorne 2 bajty nietkniete
      assert(e.d.io.s2mmBytes.toLong == 4 * 76 + 2)
      assert(e.bursts.map(_._3).toSeq == Seq(32, 32, 13))
    }
  }

  test("S2MM bez tlast: konczy po dlugosci bufora, nadmiar strumienia czeka") {
    env { e =>
      e.mem.memory.writeBigInt(0x5000 + 4 * 20, BigInt(0x5EA75EA7L), 4, BigInt(0xF))    // wartownik za buforem
      val after = 0x5EA75EA7L
      for (i <- 0 until 40) e.inQ.enqueue { p => p.data #= 0x100 + i; p.last #= false; p.keep #= 0xF }
      e.s2mmCmdQ.enqueue { p => p.addr #= 0x5000; p.bytes #= 4 * 20 }
      e.waitIdle("S2MM 20 slow")
      assert(e.d.io.s2mmBytes.toLong == 80)
      for (i <- 0 until 20) assert(e.wordAt(0x5000 + 4 * i) == 0x100 + i)
      assert(e.wordAt(0x5000 + 4 * 20) == after)
      assert(e.inQ.size >= 19)                                       // reszta strumienia nie zostala zjedzona
    }
  }

  test("petla MM2S -> S2MM (kopia), losowe dlugosci i adresy") {
    env { e =>
      // petla na poziomie testu: strumien wyjsciowy z powrotem na wejscie
      val rng = new Random(11)
      for (round <- 0 until 6) {
        val n   = 1 + rng.nextInt(200)
        val src = 0x10000L + 4 * rng.nextInt(2048)
        val dst = 0x40000L + 4 * rng.nextInt(2048)
        val words = Seq.fill(n)(rng.nextInt() & 0xFFFFFFFFL)
        for ((w, i) <- words.zipWithIndex) e.mem.memory.writeBigInt(src + 4 * i, BigInt(w), 4, BigInt(0xF))
        e.out.clear()
        e.mm2sCmdQ.enqueue { p => p.addr #= src; p.bytes #= 4 * n }
        e.waitIdle(s"runda $round MM2S")
        val beats = e.out.toList
        for (((w, last), i) <- beats.zipWithIndex) e.inQ.enqueue { p => p.data #= w; p.last #= last; p.keep #= 0xF }
        e.s2mmCmdQ.enqueue { p => p.addr #= dst; p.bytes #= 4 * 4096 }
        e.waitIdle(s"runda $round S2MM")
        assert(e.d.io.s2mmBytes.toLong == 4 * n)
        for (i <- 0 until n) assert(e.wordAt(dst + 4 * i) == words(i), f"runda $round slowo $i")
      }
    }
  }
}
