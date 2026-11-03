package newhope.snake.board

import spinal.core._
import spinal.lib._

// =====================================================================
//  ResetGen: reset projektu (SYNC, aktywny wysoko - Conventions) z
//  power-on resetu i przycisku aktywnego nisko.
//
//  Instancjonowac w domenie z resetKind = BOOT: rejestry startuja z
//  wartosci z bitstreamu (iCE40 i GW1N to wspieraja), wiec POR nie
//  potrzebuje zadnego zewnetrznego resetu. Plytka bez przycisku
//  resetu podaje btn_n = True.
//
//  reset = 1 przez porCycles cykli po konfiguracji i tak dlugo, jak
//  przycisk jest wcisniety (+ 2 cykle synchronizatora, + 1 rejestr).
// =====================================================================
case class ResetGen(porCycles : Int = 1 << 16) extends Component {
  require(porCycles >= 1)

  val io = new Bundle {
    val btn_n = in  Bool()
    val reset = out Bool()
  }

  val btn  = BufferCC(io.btn_n, init = False)
  val por  = Reg(UInt(log2Up(porCycles + 1) bits)) init 0
  val done = por === porCycles
  when(!done) { por := por + 1 }

  io.reset := RegNext(!done || !btn) init True
}

object ResetGen {
  /** Domena projektu na zegarze plytki: ResetGen w domenie BOOT,
    * wynik jako reset SYNC/HIGH (domyslny config z Conventions). */
  def domain(clk : Bool, btn_n : Bool, freq : HertzNumber, porCycles : Int = 1 << 16) : ClockDomain = {
    val boot = ClockDomain(clk, config = ClockDomainConfig(resetKind = BOOT))
    val rst  = boot(ResetGen(porCycles))
    rst.io.btn_n := btn_n
    ClockDomain(clk, rst.io.reset, frequency = FixedFrequency(freq))
  }
}
