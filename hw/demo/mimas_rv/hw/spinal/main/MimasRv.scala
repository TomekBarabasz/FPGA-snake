package newhope.mimasrv

import spinal.core._
import spinal.lib._
import spinal.lib.bus.misc.MaskMapping
import scala.collection.mutable.ArrayBuffer
import core.microrv32._
import core.microrv32.bus._
import core.microrv32.peripheral.{SBLED, SBShutdown}
import core.microrv32.peripheral.uart.SBSUart
import newhope.mcb._
import newhope.mimas_v2.SevenSegMux
import newhope.snake.board.InlineReadmem

// =====================================================================
//  microrv32 (RV32IM) na Mimas V2: monitor przez UART + LPDDR 64 MB.
//
//  Mapa pamieci (sw/mimas_rv.h):
//    0x02000000  CLIC (mtime / mtimecmp)      0x02010000  Shutdown
//    0x80000000  BRAM 16 KB, program (sw/monitor.hex)
//    0x81000000  LED D1..D8                   0x82000000  UART 115200 8N1
//    0x84000000  plytka: +0 DIP, +4 przyciski, +8 7-seg, +C status LPDDR
//    0x90000000  LPDDR 64 MB (MCB, port 0, 128 b) przez SimpleBusToMig
//
//  Zegary: 100 MHz -> PLL w MCB -> pamiec 150 MHz, c3_clk0 = 50 MHz
//  (cala logika, MigConfig.forClocks(150, 50)), kalibracja 60 MHz.
// =====================================================================

case class MimasRvConfig(
  ramWords : Int = 4096,                       // 16 KB BRAM na program
  hexFile  : String = "sw/monitor.hex",        // wzgledem demo/mimas_rv (run i test sa forkowane stamtad)
  core     : RV32CoreConfig = RV32CoreConfig(generateMultiply = true, generateDivide = true, generateCompressed = false)
)

/** Most SimpleBus (32 b, jedno slowo naraz) -> port MCB (128 b).
  * Dostep zawsze pojedynczym burstem (bl = 0); zapis: dane + maska bajtow na
  * swojej pozycji w 128-bitowym slowie, potem komenda; odczyt: komenda, czekanie
  * na dane, wybor pasa. Do kalibracji (calib = 0) nie przyjmuje transakcji -
  * CPU stoi, dlatego monitor sprawdza najpierw status. Zakladane naturalne
  * wyrownanie (gcc bez -mno-strict-align). */
case class SimpleBusToMig(c : MigConfig, window : Int = 26) extends Component {
  val io = new Bundle {
    val sb    = slave(SimpleBus(32, 32))
    val sel   = in Bool()
    val calib = in Bool()
    val port  = master(MigPort(c))
  }
  val laneBits = log2Up(c.bytesPerWord)                       // 4 dla 128 b
  val addr     = io.sb.SBaddress(window - 1 downto 0)
  val wordAddr = (addr >> laneBits) << laneBits
  val lane     = addr(laneBits - 1 downto 0)

  val sizeMask = io.sb.SBsize.mux[Bits](1 -> B"0001", 2 -> B"0011", default -> B"1111")
  val enables  = (sizeMask.resize(c.maskWidth) << lane).resize(c.maskWidth)
  val wrData   = (io.sb.SBwdata.resize(c.dataWidth) << (lane @@ U"000")).resize(c.dataWidth)

  object S extends SpinalEnum { val IDLE, WR_DATA, WR_CMD, RD_CMD, RD_WAIT, RESP = newElement() }
  val state = RegInit(S.IDLE)
  val rdata = Reg(Bits(32 bits)) init 0

  io.port.cmd.valid        := False
  io.port.cmd.payload.instr := MigInstr.WRITE
  io.port.cmd.payload.bl   := 0
  io.port.cmd.payload.addr := wordAddr.resized
  io.port.wr.valid         := False
  io.port.wr.payload.data  := wrData
  io.port.wr.payload.mask  := ~enables
  io.port.rd.ready         := False
  io.sb.SBready            := state === S.RESP
  io.sb.SBrdata            := rdata

  switch(state) {
    is(S.IDLE)    { when(io.sel && io.sb.SBvalid && io.calib) { state := io.sb.SBwrite ? S.WR_DATA | S.RD_CMD } }
    is(S.WR_DATA) { io.port.wr.valid := True; when(io.port.wr.ready) { state := S.WR_CMD } }
    is(S.WR_CMD)  { io.port.cmd.valid := True; io.port.cmd.payload.instr := MigInstr.WRITE; when(io.port.cmd.ready) { state := S.RESP } }
    is(S.RD_CMD)  { io.port.cmd.valid := True; io.port.cmd.payload.instr := MigInstr.READ;  when(io.port.cmd.ready) { state := S.RD_WAIT } }
    is(S.RD_WAIT) {
      io.port.rd.ready := True
      when(io.port.rd.valid) {
        val shifted = (io.port.rd.payload >> (lane @@ U"000")).resize(32)
        rdata := io.sb.SBsize.mux[Bits](1 -> shifted(7 downto 0).resized, 2 -> shifted(15 downto 0).resized, default -> shifted)
        state := S.RESP
      }
    }
    is(S.RESP)    { state := S.IDLE }
  }
}

/** SoC bez MCB: CPU, BRAM, UART, LED-y, CLIC, Shutdown, rejestry plytki i port
  * do LPDDR. W tej samej domenie co port MCB (c3_clk0). Symulowany z modelem portu. */
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

  val cpu = new SBRV32Core(cfg.core)
  val map = ArrayBuffer[(SimpleBus, (Bool, MaskMapping))]()

  val clic = new RVCLIC()
  map += clic.io.sb -> (clic.io.sel, MaskMapping(0x02000000L, 0xFFFF0000L))
  cpu.io.irqTimer := clic.io.irq

  val shutdown = new SBShutdown()
  map += shutdown.io.sb -> (shutdown.io.sel, MaskMapping(0x02010000L, 0xFFFFFC00L))
  cpu.io.halt    := shutdown.io.halt
  cpu.io.haltErr := shutdown.io.haltErr

  val ram = new ByteAddressableMemory(cfg.ramWords, cfg.hexFile)
  map += ram.io.sb -> (ram.io.sel, MaskMapping(0x80000000L, 0xFF000000L))

  val led = new SBLED()
  map += led.io.sb -> (led.io.sel, MaskMapping(0x81000000L, 0xFFFFFFF0L))
  io.leds := led.io.leds

  val uart = new SBSUart()
  map += uart.io.sb -> (uart.io.sel, MaskMapping(0x82000000L, 0xFFFFFF00L))
  io.uart <> uart.io.uart

  // ---- rejestry plytki: przyklad wlasnego peryferium na SimpleBus ----------
  val board = new Area {
    val sb  = SimpleBus(32, 32)
    val sel = Bool()
    map += sb -> (sel, MaskMapping(0x84000000L, 0xFFFFFF00L))
    val busCtrl = SimpleBusSlaveFactory(sb, sel, addressBits = 8, dontCareReadData = false)

    val dips    = BufferCC(io.dips, init = B(0, 8 bits))
    val buttons = BufferCC(io.buttons, init = B(0, 6 bits))
    busCtrl.read(dips, address = 0x0)
    busCtrl.read(buttons, address = 0x4)

    val segValue = Reg(Bits(16 bits)) init 0x0AAA          // jak w demo/mimas_j1
    busCtrl.readAndWrite(segValue, address = 0x8)
    val mux = SevenSegMux(clkFrequency = ClockDomain.current.frequency.getValue)
    for (i <- 0 until 3) {
      val n = 2 - i
      mux.io.digits(i).code := segValue(4 * n + 3 downto 4 * n).asUInt
      mux.io.digits(i).dot  := segValue(12 + n)
    }
    io.seg := mux.io.seg
    io.en  := mux.io.en

    val fault = RegInit(False) setWhen io.faults.orR             // lepki
    busCtrl.read(fault ## io.calib, address = 0xC)               // bit 0 = kalibracja, bit 1 = blad portu
  }

  val ddrBridge = SimpleBusToMig(c)
  map += ddrBridge.io.sb -> (ddrBridge.io.sel, MaskMapping(0x90000000L, 0xFC000000L))
  ddrBridge.io.calib := io.calib
  io.ddr <> ddrBridge.io.port

  val decoder = SimpleBusDecoder(master = cpu.io.sb, decodings = map.toSeq)
  cpu.io.unmapped := decoder.io.unmapped.fired
  decoder.io.unmapped.clear := True
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

object MimasRvVerilog extends App {
  val dir = "hw/gen"
  val top = SpinalConfig(targetDirectory = dir,
                         defaultConfigForClockDomains = ClockDomainConfig(resetKind = SYNC, resetActiveLevel = HIGH))
    .generateVerilog(new MimasRv)
  InlineReadmem(s"$dir/MimasRv.v")
  println(MigConfig.forClocks(memMHz = 150, uiMHz = 50).report)
}
