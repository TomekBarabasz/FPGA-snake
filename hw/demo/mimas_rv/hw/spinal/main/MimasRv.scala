package newhope.mimasrv

import spinal.core._
import spinal.lib._
import spinal.lib.bus.misc.{BusSlaveFactory, MaskMapping, SizeMapping}
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.amba4.axilite._
import spinal.lib.bus.amba4.axilite.AxiLite4Utils._
import scala.collection.mutable.ArrayBuffer
import core.microrv32._
import core.microrv32.bus._
import newhope.mcb._
import newhope.snake.board.InlineReadmem

// =====================================================================
//  microrv32 (RV32IM) na Mimas V2: monitor przez UART + LPDDR 64 MB.
//
//  Mapa pamieci (sw/mimas_rv.h) - ta sama w obu wariantach magistrali:
//    0x02000000  CLIC (mtime / mtimecmp)      0x02010000  Shutdown
//    0x80000000  BRAM 16 KB, program (sw/monitor.hex)
//    0x81000000  LED D1..D8                   0x82000000  UART 115200 8N1
//    0x84000000  plytka: +0 DIP, +4 przyciski, +8 7-seg, +C status LPDDR
//    0x90000000  LPDDR 64 MB (MCB, port 0, 128 b)
//
//  Warianty (MimasRvConfig.bus):
//    SimpleBus  CPU -> SimpleBusDecoder -> peryferia (SimpleBusSlaveFactory),
//               BRAM (ByteAddressableMemory), LPDDR (SimpleBusToMig)
//    Axi4       CPU -> SimpleBusToAxi4 -> Axi4Crossbar -> BRAM (Axi4SharedOnChipRam),
//               2 x AXI-Lite (peryferia, AxiLite4SlaveFactory), LPDDR (Axi4ToMig)
//  Peryferia (MimasRvPeripherals) to ten sam kod w obu wariantach.
//
//  Zegary: 100 MHz -> PLL w MCB -> pamiec 150 MHz, c3_clk0 = 50 MHz
//  (cala logika, MigConfig.forClocks(150, 50)), kalibracja 60 MHz.
// =====================================================================

sealed trait MimasRvBus
object MimasRvBus {
  case object SimpleBus extends MimasRvBus
  case object Axi4      extends MimasRvBus
  def parse(s : String) : MimasRvBus = s.toLowerCase match {
    case "simplebus" | "sb" => SimpleBus
    case "axi4" | "axi"     => Axi4
    case x => sys.error(s"magistrala '$x': simplebus albo axi4")
  }
}

case class MimasRvConfig(
  bus      : MimasRvBus = MimasRvBus.SimpleBus,
  ramWords : Int = 4096,                       // 16 KB BRAM na program
  hexFile  : String = "sw/monitor.hex",        // wzgledem demo/mimas_rv (run i test sa forkowane stamtad)
  core     : RV32CoreConfig = RV32CoreConfig(generateMultiply = true, generateDivide = true, generateCompressed = false)
) {
  def ramBytes = ramWords * 4
  /** Obraz BRAM jako slowa 32 b (format microrv32: bajty w kolejnosci adresow). */
  def hexWords : Seq[BigInt] =
    scala.io.Source.fromFile(hexFile).getLines().map(_.trim).filter(_.nonEmpty).map { l =>
      BigInt(l.grouped(2).toSeq.reverse.mkString, 16)
    }.toSeq.padTo(ramWords, BigInt(0)).take(ramWords)
}

/** Adresy peryferiow - jedno miejsce dla obu wariantow i dla sw/mimas_rv.h. */
object MimasRvMap {
  val clic     = 0x02000000L
  val shutdown = 0x02010000L
  val ram      = 0x80000000L
  val led      = 0x81000000L
  val uart     = 0x82000000L
  val board    = 0x84000000L
  val ddr      = 0x90000000L
  val ddrBytes = 64L << 20
}

/** SoC bez MCB: CPU, BRAM, peryferia i port do LPDDR, w domenie portu MCB (c3_clk0).
  * Symulowany z modelem portu (MimasRvSocTest), w obu wariantach magistrali. */
class MimasRvSoc(cfg : MimasRvConfig, c : MigConfig) extends Component {
  val io = new Bundle {
    val uart    = master(spinal.lib.com.uart.Uart())
    val leds    = out Bits(8 bits)
    val dips    = in  Bits(8 bits)      // 1 = ON
    val buttons = in  Bits(6 bits)      // 1 = wcisniety
    val seg     = out Bits(8 bits)      // SevenSegMux: aktywne nisko
    val en      = out Bits(3 bits)
    val calib   = in  Bool()            // LPDDR skalibrowana (zsynchronizowane)
    val faults  = in  Bits(4 bits)      // flagi bledow portu MCB
    val ddr     = master(MigPort(c))
  }
  import MimasRvMap._
  import MimasRvPeripherals._

  val cpu = new SBRV32Core(cfg.core)

  /** Wszystkie peryferia na dwoch fabrykach: `low` (CLIC, Shutdown) i `high`
    * (LED, UART, plytka); `lowBase` / `highBase` to adres, pod ktorym fabryka
    * widzi poczatek swojego okna (0 przy SimpleBus, adres bezwzgledny przy AXI-Lite). */
  def peripherals(low : BusSlaveFactory, lowBase : BigInt, high : BusSlaveFactory, highBase : BigInt,
                  sbHigh : Option[(BusSlaveFactory, BusSlaveFactory, BusSlaveFactory)] = None) = new Area {
    val clic     = Clic(low, lowBase)
    val shutdown = Shutdown(low, lowBase + (MimasRvMap.shutdown - MimasRvMap.clic))
    val (fLed, fUart, fBoard, bLed, bUart, bBoard) = sbHigh match {
      case Some((l, u, b)) => (l, u, b, BigInt(0), BigInt(0), BigInt(0))           // SimpleBus: osobny port na peryferium
      case None            => (high, high, high, highBase, highBase + (MimasRvMap.uart - MimasRvMap.led),
                                                         highBase + (MimasRvMap.board - MimasRvMap.led))
    }
    val ledP  = Led(fLed, bLed)
    val uartP = Uart(fUart, bUart)
    val brd   = Board(fBoard, bBoard, io.dips, io.buttons, io.calib, io.faults)
    cpu.io.irqTimer := clic.irq
    cpu.io.halt     := shutdown.halt
    cpu.io.haltErr  := shutdown.haltErr
    io.leds := ledP.leds
    io.uart <> uartP.uart
    io.seg  := brd.mux.io.seg
    io.en   := brd.mux.io.en
  }

  // ---- wariant SimpleBus -------------------------------------------------------
  val sbFabric = (cfg.bus == MimasRvBus.SimpleBus) generate new Area {
    val map = ArrayBuffer[(SimpleBus, (Bool, MaskMapping))]()
    /** Port dekodera + fabryka z `bits` bitami adresu lokalnego. */
    def port(base : Long, mask : Long, bits : Int) : BusSlaveFactory = {
      val sb = SimpleBus(32, 32); val sel = Bool()
      map += sb -> (sel, MaskMapping(base, mask))
      SimpleBusSlaveFactory(sb, sel, addressBits = bits, dontCareReadData = false)
    }
    val low   = port(clic,  0xFFFE0000L, 17)       // 0x02000000 - 0x0201FFFF: CLIC + Shutdown
    val fLed  = port(led,   0xFFFFFFF0L, 4)
    val fUart = port(uart,  0xFFFFFF00L, 8)
    val fBrd  = port(board, 0xFFFFFF00L, 8)
    val periph = peripherals(low, 0, null, 0, Some((fLed, fUart, fBrd)))

    val ramB = new ByteAddressableMemory(cfg.ramWords, cfg.hexFile)
    map += ramB.io.sb -> (ramB.io.sel, MaskMapping(ram, 0xFF000000L))

    val ddrBridge = SimpleBusToMig(c)
    map += ddrBridge.io.sb -> (ddrBridge.io.sel, MaskMapping(ddr, 0xFC000000L))
    ddrBridge.io.calib := io.calib
    io.ddr <> ddrBridge.io.port

    val decoder = SimpleBusDecoder(master = cpu.io.sb, decodings = map.toSeq)
    cpu.io.unmapped := decoder.io.unmapped.fired
    decoder.io.unmapped.clear := True
  }

  // ---- wariant AXI4 + AXI-Lite -------------------------------------------------
  val axiFabric = (cfg.bus == MimasRvBus.Axi4) generate new Area {
    val axiCfg = MimasRvAxi.config
    val bridge = SimpleBusToAxi4(axiCfg)
    bridge.io.sb <> cpu.io.sb
    cpu.io.unmapped := False                         // nieznany adres: crossbar odpowiada DECERR, most go pomija

    val ramA = Axi4SharedOnChipRam(dataWidth = 32, byteCount = cfg.ramBytes, idWidth = axiCfg.idWidth)
    ramA.ram.initBigInt(cfg.hexWords)

    val ddrBridge = Axi4ToMig(axiCfg, c)
    ddrBridge.io.calib := io.calib
    io.ddr <> ddrBridge.io.port

    // dwa okna AXI4 zamieniane na AXI-Lite (CPU wysyla tylko pojedyncze beaty)
    val lowAxi  = Axi4(axiCfg)
    val highAxi = Axi4(axiCfg)
    val lowLite  = lowAxi.toLite()
    val highLite = highAxi.toLite()
    val periph = peripherals(AxiLite4SlaveFactory(lowLite), clic, AxiLite4SlaveFactory(highLite), led)

    val crossbar = Axi4CrossbarFactory()
    crossbar.addSlaves(
      ramA.io.axi       -> SizeMapping(ram, cfg.ramBytes),
      ddrBridge.io.axi  -> SizeMapping(ddr, ddrBytes),
      lowAxi            -> SizeMapping(clic, 0x20000),            // CLIC + Shutdown
      highAxi           -> SizeMapping(led, 0x04000000)           // LED, UART, plytka (0x81000000 - 0x84FFFFFF)
    )
    crossbar.addConnections(bridge.io.axi -> List(ramA.io.axi, ddrBridge.io.axi, lowAxi, highAxi))
    crossbar.build()
  }
}

/** Top plytki: MCB (PLL, kalibracja, piny LPDDR) + MimasRvSoc w domenie c3_clk0.
  * SW1 = reset CPU (MCB i zawartosc LPDDR zostaja). */
class MimasRv(cfg : MimasRvConfig = MimasRvConfig(),
              c   : MigConfig     = MigConfig.forClocks(memMHz = 150, uiMHz = 50)) extends Component {
  val io = new Bundle {
    val c3_sys_clk = in Bool()                  // V10, 100 MHz -> PLL w s6_lpddr
    val mcb3_dram  = MigDramPins(c)             // nazwa pola = prefiks portow w UCF
    val mcb3_rzq   = inout(Analog(Bool()))
    val btn_n      = in  Bits(6 bits)           // SW1..SW6, aktywne nisko; SW1 = reset CPU
    val dip_n      = in  Bits(8 bits)           // DP1..DP8 (nadruk), aktywne nisko
    val led        = out Bits(8 bits)
    val uart_rx    = in  Bool()
    val uart_tx    = out Bool()
    val seg        = out Bits(8 bits)
    val en         = out Bits(3 bits)
  }
  noIoPrefix()

  // reset MCB na stale nieaktywny: przycisk SW3 (M16) z UCF Numato jest tu zwyklym przyciskiem
  val mcb = McbCore(c, io.c3_sys_clk, sysRst = False, io.mcb3_dram, io.mcb3_rzq)

  val ui = new ClockingArea(mcb.uiCd) {
    val c3_calib_done_buffercc = BufferCC(mcb.calibDone, init = False)   // nazwa uzyta w UCF (TIG)
    val cpuReset = RegNext(!BufferCC(io.btn_n(0), init = True)) init True
  }
  val socCd = ClockDomain(clock = mcb.uiCd.readClockWire,
                          reset = mcb.uiCd.readResetWire || ui.cpuReset,
                          frequency = c.uiFrequency,
                          config = ClockDomainConfig(resetKind = SYNC, resetActiveLevel = HIGH))

  val soc = socCd(new MimasRvSoc(cfg, c))
  soc.io.dips     := ~io.dip_n
  soc.io.buttons  := ~io.btn_n
  soc.io.calib    := ui.c3_calib_done_buffercc
  soc.io.faults   := mcb.faults
  soc.io.uart.rxd := io.uart_rx
  io.uart_tx      := soc.io.uart.txd
  io.led          := soc.io.leds
  io.seg          := soc.io.seg
  io.en           := soc.io.en
  mcb.port.driveFrom(soc.io.ddr)
}

/** sbt "demo-mimas-rv/runMain newhope.mimasrv.MimasRvVerilog [simplebus|axi4]" */
object MimasRvVerilog extends App {
  val bus = args.headOption.map(MimasRvBus.parse).getOrElse(MimasRvBus.SimpleBus)
  println(s"[mimas_rv] magistrala: $bus")
  val dir = "hw/gen"
  val top = SpinalConfig(targetDirectory = dir,
                         defaultConfigForClockDomains = ClockDomainConfig(resetKind = SYNC, resetActiveLevel = HIGH))
    .generateVerilog(new MimasRv(MimasRvConfig(bus = bus)))
  InlineReadmem(s"$dir/MimasRv.v")
  println(MigConfig.forClocks(memMHz = 150, uiMHz = 50).report)
}
