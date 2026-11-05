package newhope.mimasrv

import spinal.core._
import spinal.core.sim._
import spinal.lib.bus.amba4.axi._
import spinal.lib.sim.{StreamDriver, StreamMonitor, StreamReadyRandomizer}
import org.scalatest.funsuite.AnyFunSuite
import scala.collection.mutable
import scala.util.Random
import newhope.mcb._

// =====================================================================
//  Axi4ToMig: bursty AXI4 na porcie MCB (model MigPortModel).
//
//  Master AXI4 ze StreamDriver / StreamMonitor (spinal.lib.sim): kanaly
//  AW, W, AR z kolejek (z losowymi przerwami), B i R z losowym ready
//  (backpressure). Jedna transakcja naraz, jawne len / size / WSTRB -
//  zamiast spinal.lib...sim.Axi4Master, ktory sam dzieli transfery.
//  Referencja: mapa bajtow. Model portu pilnuje regul MCB (dane przed
//  komenda, <= 64 slowa, bez przecinania wiersza 2 kB, FIFO odczytu).
// =====================================================================
class Axi4ToMigTest extends AnyFunSuite {
  val c   = MigConfig.forClocks(memMHz = 150, uiMHz = 50)
  val cfg = MimasRvAxi.config

  lazy val compiled = SimConfig.withVerilator.workspacePath("simWorkspace").workspaceName("axi4_to_mig")
    .compile(Axi4ToMig(cfg, c))

  class Env(val d : Axi4ToMig) {
    val cd   = d.clockDomain
    val mem  = mutable.Map[Long, BigInt]().withDefaultValue(BigInt(0))        // model MCB (slowa 128 b)
    val ref  = mutable.Map[Long, Int]().withDefaultValue(0)                   // referencja (bajty)
    val cmds = mutable.ArrayBuffer[(Int, Long, Int)]()                        // (instr, adres, slow)
    MigPortModel(d.io.port, cd, mem, latency = 9, (i, a, n) => cmds += ((i, a, n)))

    val a = d.io.axi
    val (_, awQ) = StreamDriver.queue(a.aw, cd)
    val (_, wQ)  = StreamDriver.queue(a.w, cd)
    val (_, arQ) = StreamDriver.queue(a.ar, cd)
    StreamReadyRandomizer(a.b, cd)
    StreamReadyRandomizer(a.r, cd)
    val bQ = mutable.Queue[(Int, Int)]()                                       // (id, resp)
    val rQ = mutable.Queue[(Long, Int, Boolean, Int)]()                        // (dane, id, last, resp)
    StreamMonitor(a.b, cd) { p => bQ += ((p.id.toInt, p.resp.toInt)) }
    StreamMonitor(a.r, cd) { p => rQ += ((p.data.toLong, p.id.toInt, p.last.toBoolean, p.resp.toInt)) }

    private def setAx(x : Axi4Ax, addr : Long, len : Int, size : Int, id : Int) : Unit = {
      x.addr #= addr; x.len #= len; x.size #= size; x.id #= id; x.burst #= 1   // INCR
    }
    private def waitFor(cond : => Boolean, what : => String, max : Int = 200000) : Unit = {
      var n = 0
      while (!cond && n < max) { cd.waitSampling(); n += 1 }
      assert(cond, s"timeout: $what")
    }

    /** Zapis burstu: beaty (dane, strb), AxSIZE = log2 bajtow beatu. */
    def write(addr : Long, beats : Seq[(Long, Int)], size : Int = 2, id : Int = 3) : Unit = {
      awQ.enqueue(p => setAx(p, addr, beats.size - 1, size, id))
      for (((data, strb), k) <- beats.zipWithIndex)
        wQ.enqueue { p => p.data #= data; p.strb #= strb; p.last #= k == beats.size - 1 }
      waitFor(bQ.nonEmpty, f"B dla zapisu 0x$addr%x (${beats.size} beatow)")
      val (bid, resp) = bQ.dequeue()
      assert(bid == id && resp == 0, s"B id=$bid resp=$resp")
      // referencja: beat k pod adresem (addr & ~3) + 4k, bajty wg strb
      for (((data, strb), k) <- beats.zipWithIndex; byte <- 0 until 4 if ((strb >> byte) & 1) == 1)
        ref((addr & ~3L) + 4 * k + byte) = ((data >> (8 * byte)) & 0xff).toInt
    }

    def read(addr : Long, len : Int, size : Int = 2, id : Int = 5) : Seq[Long] = {
      arQ.enqueue(p => setAx(p, addr, len, size, id))
      waitFor(rQ.size >= len + 1, f"R dla odczytu 0x$addr%x (${len + 1} beatow, jest ${rQ.size})")
      cd.waitSampling(20)
      assert(rQ.size == len + 1, s"za duzo beatow R: ${rQ.size}")
      for (k <- 0 to len) yield {
        val (data, rid, last, resp) = rQ.dequeue()
        assert(rid == id && resp == 0, s"R id=$rid resp=$resp")
        assert(last == (k == len), s"RLAST na beacie $k z ${len + 1}")
        data
      }
    }
    def refWord(byteAddr : Long) : Long = (0 until 4).map(b => ref(byteAddr + b).toLong << (8 * b)).sum
  }

  def env(body : Env => Unit) : Unit = compiled.doSim { d =>
    d.io.calib #= true
    // przed resetem: sim losuje wartosci poczatkowe, a losowe AR/AW.valid = 1 to
    // poprawna (smieciowa) transakcja dla slave'a
    val a = d.io.axi
    a.aw.valid #= false; a.w.valid #= false; a.ar.valid #= false; a.b.ready #= false; a.r.ready #= false
    d.io.port.cmd.ready #= false; d.io.port.wr.ready #= false; d.io.port.rd.valid #= false
    d.clockDomain.forkStimulus(10)
    d.clockDomain.assertReset(); d.clockDomain.waitSampling(5); d.clockDomain.deassertReset()
    val e = new Env(d)
    d.clockDomain.waitSampling(3)
    body(e)
  }

  test("pojedyncze beaty: slowo, polslowo, bajt na kazdym pasie") {
    env { e =>
      e.write(0x100, Seq(0x11223344L -> 0xF))
      assert(e.read(0x100, 0) == Seq(0x11223344L))
      e.write(0x102, Seq(0xABCD0000L -> 0xC), size = 1)                 // polslowo na pasach 2..3
      e.write(0x101, Seq(0x00007700L -> 0x2), size = 0)                 // bajt na pasie 1
      assert(e.read(0x100, 0) == Seq(0xABCD7744L))
      for (l <- 0 until 4) {                                            // kazdy pas 32 b w slowie 128 b
        e.write(0x200 + 4 * l, Seq((0xC0DE0000L + l) -> 0xF))
        assert(e.read(0x200 + 4 * l, 0) == Seq(0xC0DE0000L + l))
      }
      assert(e.cmds.forall(_._3 == 1))
    }
  }

  test("burst 256 beatow od poczatku slowa = jedna komenda 64 slow") {
    env { e =>
      val data = (0 until 256).map(i => (0x5A000000L + i) -> 0xF)
      e.cmds.clear()
      e.write(0x1000, data)
      assert(e.cmds.toSeq == Seq((0, 0x1000L, 64)), e.cmds)
      e.cmds.clear()
      assert(e.read(0x1000, 255) == data.map(_._1))
      assert(e.cmds.toSeq == Seq((1, 0x1000L, 64)), e.cmds)
    }
  }

  test("burst od srodka slowa i przez granice wiersza 2 kB: dzielony na komendy") {
    env { e =>
      val data = (0 until 256).map(i => (0x3C000000L + i) -> 0xF)
      e.cmds.clear()
      e.write(0x2004, data)                                             // pas 1: 65 slow -> 64 + 1
      assert(e.cmds.toSeq == Seq((0, 0x2000L, 64), (0, 0x2400L, 1)), e.cmds)
      assert(e.read(0x2004, 255) == data.map(_._1))
      e.cmds.clear()
      val d2 = (0 until 16).map(i => (0x77000000L + i) -> 0xF)
      e.write(0x7F0 - 16, d2)                                           // 0x7E0..0x81F: wiersz konczy sie na 0x800
      assert(e.cmds.toSeq == Seq((0, 0x7E0L, 2), (0, 0x800L, 2)), e.cmds)
      assert(e.read(0x7E0, 15) == d2.map(_._1))
    }
  }

  test("losowe bursty i WSTRB vs referencja (200 transakcji)") {
    env { e =>
      val rng = new Random(1234)
      for (n <- 0 until 200) {
        val len  = if (rng.nextInt(4) == 0) rng.nextInt(256) else rng.nextInt(20)
        var addr = 4L * rng.nextInt(0x4000)                             // pierwsze 64 kB
        if ((addr % 4096) + 4 * (len + 1) > 4096) addr -= (addr % 4096) + 4 * (len + 1) - 4096   // regula 4 kB
        if (rng.nextBoolean()) {
          val beats = (0 to len).map(_ => (rng.nextLong() & 0xFFFFFFFFL) -> (if (rng.nextInt(3) == 0) rng.nextInt(16) else 0xF))
          e.write(addr, beats)
        } else {
          val got  = e.read(addr, len)
          val want = (0 to len).map(k => e.refWord(addr + 4 * k))
          assert(got == want, f"odczyt 0x$addr%x len $len")
        }
      }
    }
  }

  test("przed kalibracja AW i AR nie sa przyjmowane") {
    env { e =>
      e.d.io.calib #= false
      e.cd.waitSampling(2)
      e.awQ.enqueue(p => { p.addr #= 0x40; p.len #= 0; p.size #= 2; p.id #= 1; p.burst #= 1 })
      e.arQ.enqueue(p => { p.addr #= 0x80; p.len #= 0; p.size #= 2; p.id #= 2; p.burst #= 1 })
      for (_ <- 0 until 100) {
        e.cd.waitSampling()
        assert(!e.a.aw.ready.toBoolean && !e.a.ar.ready.toBoolean)
      }
      assert(e.cmds.isEmpty)
    }
  }
}
