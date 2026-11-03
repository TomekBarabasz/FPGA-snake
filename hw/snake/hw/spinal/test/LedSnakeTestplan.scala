package newhope.snake

import spinal.core._
import spinal.core.sim._
import scala.util.Random
import scala.collection.mutable
import newhope.core.Conventions
import newhope.vertebra._
import newhope.vertebra.sim.{SimEnv, SimSeed}

// =====================================================================
//  LedSnake: sciezka weza i pauza.
//
//  Sprawdzamy przebieg, nie fazy zbocza: z probek leds co cykl
//  zostaje ciag kolejnych roznych stanow (musi sie zgadzac z modelem)
//  i liczba niezapauzowanych cykli miedzy zmianami (== tickCycles).
// =====================================================================
object LedSnakeModel {
  /** Kolejne stany leds od resetu (pierwszy = stan po resecie). */
  def states(width : Int, length : Int, n : Int) : Seq[Int] = {
    val pos = mutable.ArrayBuffer.fill(length)(0)
    var up  = true
    def leds = pos.map(1 << _).reduce(_ | _)
    val out = mutable.ArrayBuffer(leds)
    while (out.size < n) {
      val atEnd = if (up) pos(0) == width - 1 else pos(0) == 0
      up = up ^ atEnd
      for (i <- length - 1 until 0 by -1) pos(i) = pos(i - 1)
      pos(0) = if (up) pos(0) + 1 else pos(0) - 1
      out += leds
    }
    out.toSeq
  }
}

class LedSnakeTestplan extends TestplanSuite {
  override def testplan = Seq(
    Testpoint("snake_path", Stage.V1,
      "Glowa odbija sie od koncow, ogon idzie za nia",
      stimulus = Seq("pause = 0, kilka pelnych przejsc tam i z powrotem"),
      checking = Seq("ciag stanow leds == model", "zmiana dokladnie co tickCycles cykli")),
    Testpoint("snake_pause", Stage.V1,
      "Pauza zamraza weza i licznik ruchu",
      stimulus = Seq("losowe pause, w tym dlugie odcinki pause = 1"),
      checking = Seq("ciag stanow leds == model (pauza nie gubi ani nie dubluje krokow)",
                     "zmiana co tickCycles NIEzapauzowanych cykli"))
  )

  val configs = Seq((4, 5, 2), (6, 3, 3), (3, 4, 1))

  def run(width : Int, tick : Int, length : Int, name : String)(pause : Random => Boolean) : Unit =
    SimEnv(Conventions.spinal, s"${name}_w${width}_l$length")
      .compile(LedSnake(width, tick, length))
      .doSim(name, seed = SimSeed(name)) { d =>
        val rng = new Random(SimSeed(name))
        d.clockDomain.forkStimulus(10)
        d.io.pause #= true
        d.clockDomain.waitSampling(3)

        // (leds, pause w tym cyklu)
        val samples = for (_ <- 0 until 40 * width * tick) yield {
          val p = pause(rng)
          d.io.pause #= p
          d.clockDomain.waitSampling()
          (d.io.leds.toInt, p)
        }

        // ciag roznych stanow i czas (niezapauzowane cykle) trwania kazdego
        val seq  = mutable.ArrayBuffer(samples.head._1)
        val gaps = mutable.ArrayBuffer[Int]()
        var run  = 0
        for ((l, p) <- samples) {
          if (l != seq.last) { seq += l; gaps += run; run = 0 }
          if (!p) run += 1
        }
        assert(seq.size > 4 * width, s"za malo krokow: ${seq.size}")

        // Model po kroku; kolejne rowne stany (odbicie: glowa wchodzi na
        // ogon) sa na LED-ach nieodroznialne, wiec model tez sie skleja.
        val model = LedSnakeModel.states(width, length, 4 * seq.size)
          .foldLeft(List.empty[(Int, Int)]) {
            case ((s, n) :: t, x) if s == x => (s, n + 1) :: t
            case (acc, x)                   => (x, 1) :: acc
          }.reverse.take(seq.size)
        assert(seq == model.map(_._1), s"sciezka: ${seq.map(_.toBinaryString).mkString(" ")}")
        // gaps(j) = czas trwania stanu j; stan 0 (po resecie) jest niepelny
        for (j <- 1 until gaps.size)
          assert(gaps(j) == tick * model(j)._2, s"stan $j trwal ${gaps(j)}, odstepy: ${gaps.mkString(",")}")
      }

  for ((w, t, l) <- configs) {
    testpoint("snake_path", s"w$w l$l")(run(w, t, l, "snake_path")(_ => false))
    testpoint("snake_pause", s"w$w l$l")(run(w, t, l, "snake_pause") { rng =>
      rng.nextInt(10) < 4
    })
  }
}
