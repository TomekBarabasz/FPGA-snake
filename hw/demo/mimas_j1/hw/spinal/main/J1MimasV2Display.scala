package newhope.mimasj1

import spinal.core._
import spinal.lib._
import j1sc._
import j1sc.boards.{J1MimasV2, J1MimasV2Soc, InlineReadmem}
import newhope.mimas_v2.SevenSegMux

// =====================================================================
//  J1 + SwapForth na Mimas V2 z wyswietlaczem 7-seg.
//
//  Wzorzec rozszerzenia J1: dziedziczymy SoC z cpu/j1 (J1MimasV2Soc: CPU,
//  UART, LED-y, DIP-y, przyciski, timery, przerwania) i dokladamy wlasne
//  peryferium na jego magistrali IO (peripheralBusCtrl):
//
//    1. rejestr (albo inny stan) w SoC,
//    2. zmapowanie go pod wolny adres: peripheralBusCtrl.readAndWrite(...)
//       (albo read / write / onWrite - API BusSlaveFactory ze spinal.lib),
//    3. sprzet, ktory z tego rejestru korzysta, i nowe piny w io.
//
//  Z Fortha to zwykle io! / io@ pod tym adresem.
//
//  Wyswietlacz, adres 0x60 (r/w), jedna 16-bitowa wartosc:
//    bity 11..8  lewa cyfra     bit 14  kropka lewej
//    bity  7..4  srodkowa       bit 13  kropka srodkowej
//    bity  3..0  prawa          bit 12  kropka prawej
//  Kody cyfr jak w SevenSegMux: 0..9, 10 = puste, 11 = minus, 12 = E,
//  13..15 = puste. Po resecie 0x0AAA (wszystko zgaszone).
//  Czyli po `hex`:  123 60 io!  ->  "123",   1A5B 60 io!  ->  "5.-" ... itd.
// =====================================================================
class J1MimasV2DisplaySoc(j1Cfg : J1Config, boardCfg : CoreConfig) extends J1MimasV2Soc(j1Cfg, boardCfg) {

  val display = new Bundle {
    val seg = out Bits(8 bits)    // a..g, kropka; aktywne nisko (SevenSegMux)
    val en  = out Bits(3 bits)    // en[0] = lewa cyfra; aktywne nisko
  }

  val sevenSeg = new Area {
    val value = Reg(Bits(16 bits)) init 0x0AAA
    peripheralBusCtrl.readAndWrite(value, address = 0x60)

    val mux = SevenSegMux(clkFrequency = boardCfg.coreFrequency.getValue)
    for (i <- 0 until 3) {                 // i = 0: lewa cyfra = bity 11..8
      val n = 2 - i
      mux.io.digits(i).code := value(4 * n + 3 downto 4 * n).asUInt
      mux.io.digits(i).dot  := value(12 + n)
    }
    display.seg := mux.io.seg
    display.en  := mux.io.en
  }
}

class J1MimasV2Display(j1Cfg : J1Config, boardCfg : CoreConfig) extends Component {

  val io = new Bundle {
    val clk100  = in  Bool()
    val btn_n   = in  Bits(6 bits)     // SW1..SW6, aktywne nisko; SW1 = reset
    val dip_n   = in  Bits(8 bits)     // DP1..DP8 (nadruk), aktywne nisko
    val led     = out Bits(8 bits)
    val uart_rx = in  Bool()
    val uart_tx = out Bool()
    val seg     = out Bits(8 bits)
    val en      = out Bits(3 bits)
  }
  noIoPrefix()

  val coreCd = J1MimasV2.coreDomain(io.clk100, reset = !io.btn_n(0), boardCfg.coreFrequency)

  val core = coreCd(new J1MimasV2DisplaySoc(j1Cfg, boardCfg))
  core.io.dips     := ~io.dip_n
  core.io.buttons  := ~io.btn_n
  core.io.extInt   := 0
  io.led           := core.io.leds
  core.io.uart.rxd := io.uart_rx
  io.uart_tx       := core.io.uart.txd
  io.seg           := core.display.seg
  io.en            := core.display.en
}

object J1MimasV2DisplayVerilog extends App {
  // run forkowany z demo/mimas_j1 (forkSettings): wynik w demo/mimas_j1/hw/gen
  // argument: predkosc UART-u = firmware PIC-a (brak = 115200 jimmo, "19200" = fabryczny)
  val baud = J1MimasV2.baudFromArgs(args)
  println(s"[mimas_j1] UART $baud baud")
  val dir = "hw/gen"
  SpinalConfig(mergeAsyncProcess            = true,
               defaultConfigForClockDomains = J1MimasV2.globalClockConfig,
               targetDirectory              = dir)
    .generateVerilog(new J1MimasV2Display(J1Config.forth16, CoreConfig.mimasV2(baud)))
    .printPruned()
  InlineReadmem(s"$dir/J1MimasV2Display.v")
}
