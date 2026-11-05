package newhope.mimasrv

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.amba4.axis._
import spinal.lib.bus.misc.BusSlaveFactory

// =====================================================================
//  DMA AXI4-Stream <-> pamiec (AXI4), etap 2 nauki AXI.
//
//  AXI4-Stream nie ma adresow: to strumien beatow (tdata, tkeep - ktore
//  bajty sa wazne, tlast - koniec pakietu) z valid/ready. Do pamieci
//  (AXI4 z adresami) prowadza go dwa silniki, jak w AXI DMA Xilinxa:
//
//    MM2S  pamiec -> strumien: odczyty burstami AXI4, beaty R na strumien,
//          tlast na ostatnim beacie transferu
//    S2MM  strumien -> pamiec: beaty zbierane w FIFO w burst, potem AW + W;
//          tkeep -> WSTRB; koniec na tlast albo po zadanej dlugosci
//
//  Bursty do `maxBurst` beatow i nigdy przez granice 4 kB (regula AXI4).
//  Adresy i dlugosci wyrownane do 4 B. Komendy (adres, bajty) to Streamy -
//  rejestry dla CPU robi DmaRegs na dowolnej BusSlaveFactory.
// =====================================================================

object MimasRvStream {
  /** 32 b danych (dataWidth w bajtach), tkeep, tlast. */
  def config = Axi4StreamConfig(dataWidth = 4, useKeep = true, useLast = true)
}

case class DmaCmd() extends Bundle {
  val addr  = UInt(32 bits)
  val bytes = UInt(24 bits)     // do 16 MB na transfer
}

case class Axi4StreamDma(axiCfg : Axi4Config, maxBurst : Int = 32) extends Component {
  require(axiCfg.dataWidth == 32 && isPow2(maxBurst) && maxBurst <= 256)
  val sCfg = MimasRvStream.config
  val io = new Bundle {
    val axi       = master(Axi4(axiCfg))
    val mm2sCmd   = slave(Stream(DmaCmd()))
    val s2mmCmd   = slave(Stream(DmaCmd()))
    val mm2s      = master(Axi4Stream(sCfg))
    val s2mm      = slave(Axi4Stream(sCfg))
    val mm2sBusy  = out Bool()
    val s2mmBusy  = out Bool()
    val s2mmBytes = out UInt(24 bits)   // bajty zapisane przez biezacy/ostatni S2MM
  }
  require(widthOf(io.mm2s.data) == 32)

  /** Beatow w nastepnym burscie: <= maxBurst, <= ile zostalo, do granicy 4 kB. */
  def burstBeats(addr : UInt, bytesLeft : UInt) : UInt = {
    val toPage = (U(4096, 13 bits) - addr(11 downto 0).resize(13)) >> 2           // 1..1024
    val left   = (bytesLeft >> 2).resize(13)
    val m      = (toPage < left) ? toPage | left
    (m > maxBurst) ? U(maxBurst, 13 bits) | m
  }

  // ---- MM2S: pamiec -> strumien ---------------------------------------------
  val mm2s = new Area {
    object S extends SpinalEnum { val IDLE, AR, R = newElement() }
    val state = RegInit(S.IDLE)
    val addr  = Reg(UInt(32 bits)) init 0
    val left  = Reg(UInt(24 bits)) init 0          // bajty do konca transferu
    val beats = Reg(UInt(9 bits)) init 0           // beaty do konca burstu

    io.mm2sCmd.ready := state === S.IDLE
    io.mm2sBusy      := state =/= S.IDLE

    val n = burstBeats(addr, left)
    io.axi.ar.valid        := state === S.AR
    io.axi.ar.payload.addr := addr
    io.axi.ar.payload.id   := 0
    io.axi.ar.payload.len  := (n - 1).resized
    io.axi.ar.payload.size := 2
    io.axi.ar.payload.setBurstINCR()

    io.mm2s.valid := state === S.R && io.axi.r.valid
    io.mm2s.data  := io.axi.r.payload.data
    io.mm2s.keep  := B"1111"
    io.mm2s.last  := left === 4                     // ostatni beat calego transferu
    io.axi.r.ready := state === S.R && io.mm2s.ready

    switch(state) {
      is(S.IDLE) {
        when(io.mm2sCmd.fire && io.mm2sCmd.bytes >= 4) {
          addr := io.mm2sCmd.addr(31 downto 2) @@ U"00"
          left := io.mm2sCmd.bytes(23 downto 2) @@ U"00"
          state := S.AR
        }
      }
      is(S.AR) { when(io.axi.ar.fire) { beats := n.resized; state := S.R } }
      is(S.R) {
        when(io.axi.r.fire) {
          addr  := addr + 4
          left  := left - 4
          beats := beats - 1
          when(beats === 1) { state := (left === 4) ? S.IDLE | S.AR }
        }
      }
    }
  }

  // ---- S2MM: strumien -> pamiec ---------------------------------------------
  val s2mm = new Area {
    object S extends SpinalEnum { val IDLE, FILL, AW, W, B = newElement() }
    val state     = RegInit(S.IDLE)
    val addr      = Reg(UInt(32 bits)) init 0
    val left      = Reg(UInt(24 bits)) init 0      // miejsce do konca bufora docelowego
    val target    = Reg(UInt(9 bits)) init 0       // beaty planowane w tym burscie
    val collected = Reg(UInt(9 bits)) init 0       // beaty juz w FIFO
    val wLeft     = Reg(UInt(9 bits)) init 0
    val ended     = RegInit(False)                 // przyszedl tlast
    val written   = Reg(UInt(24 bits)) init 0
    io.s2mmBytes := written

    val fifo = StreamFifo(Bits(36 bits), maxBurst)   // tkeep ## tdata
    io.s2mmCmd.ready := state === S.IDLE
    io.s2mmBusy      := state =/= S.IDLE

    io.s2mm.ready         := state === S.FILL && collected < target && fifo.io.push.ready
    fifo.io.push.valid    := io.s2mm.fire
    fifo.io.push.payload  := io.s2mm.keep ## io.s2mm.data

    io.axi.aw.valid        := state === S.AW
    io.axi.aw.payload.addr := addr
    io.axi.aw.payload.id   := 0
    io.axi.aw.payload.len  := (collected - 1).resized
    io.axi.aw.payload.size := 2
    io.axi.aw.payload.setBurstINCR()

    io.axi.w.valid         := state === S.W && fifo.io.pop.valid
    io.axi.w.payload.data  := fifo.io.pop.payload(31 downto 0)
    io.axi.w.payload.strb  := fifo.io.pop.payload(35 downto 32)
    io.axi.w.payload.last  := wLeft === 1
    fifo.io.pop.ready      := state === S.W && io.axi.w.ready
    io.axi.b.ready         := state === S.B

    def startBurst() : Unit = { target := burstBeats(addr, left).resized; collected := 0 }

    switch(state) {
      is(S.IDLE) {
        when(io.s2mmCmd.fire && io.s2mmCmd.bytes >= 4) {
          addr    := io.s2mmCmd.addr(31 downto 2) @@ U"00"
          left    := io.s2mmCmd.bytes(23 downto 2) @@ U"00"
          written := 0
          ended   := False
          target  := burstBeats(io.s2mmCmd.addr(31 downto 2) @@ U"00", io.s2mmCmd.bytes(23 downto 2) @@ U"00").resized
          collected := 0
          state   := S.FILL
        }
      }
      is(S.FILL) {
        when(io.s2mm.fire) {
          collected := collected + 1
          written   := written + CountOne(io.s2mm.keep).resized
          when(io.s2mm.last) { ended := True }
          when(io.s2mm.last || collected + 1 === target) { state := S.AW }
        }
      }
      is(S.AW) { when(io.axi.aw.fire) { wLeft := collected; state := S.W } }
      is(S.W)  { when(io.axi.w.fire) { wLeft := wLeft - 1; when(wLeft === 1) { state := S.B } } }
      is(S.B) {
        when(io.axi.b.fire) {
          val nextAddr = addr + (collected << 2).resized
          val nextLeft = left - (collected << 2).resized
          addr := nextAddr
          left := nextLeft
          when(ended || nextLeft === 0) { state := S.IDLE } otherwise {
            target := burstBeats(nextAddr, nextLeft).resized; collected := 0; state := S.FILL
          }
        }
      }
    }
  }
}

/** Blok strumieniowy miedzy MM2S a S2MM: op 0 = przepuszcza, 1 = XOR arg,
  * 2 = dodaje arg do slowa. Liczy beaty i sume slow (po operacji). */
case class Axi4StreamOp() extends Component {
  val sCfg = MimasRvStream.config
  val io = new Bundle {
    val src   = slave(Axi4Stream(sCfg))
    val dst   = master(Axi4Stream(sCfg))
    val op    = in UInt(2 bits)
    val arg   = in Bits(32 bits)
    val clear = in Bool()
    val beats = out UInt(32 bits)
    val sum   = out UInt(32 bits)
  }
  val result = io.op.mux[Bits](
    1       -> (io.src.data ^ io.arg),
    2       -> (io.src.data.asUInt + io.arg.asUInt).asBits,
    default -> io.src.data)
  io.dst.valid := io.src.valid
  io.dst.data  := result
  io.dst.keep  := io.src.keep
  io.dst.last  := io.src.last
  io.src.ready  := io.dst.ready

  val beats = Reg(UInt(32 bits)) init 0
  val sum   = Reg(UInt(32 bits)) init 0
  when(io.clear) { beats := 0; sum := 0 }
    .elsewhen(io.dst.fire) { beats := beats + 1; sum := sum + result.asUInt }
  io.beats := beats
  io.sum   := sum
}

/** Rejestry DMA dla CPU (na dowolnej BusSlaveFactory):
  *   +00 MM2S adres   +04 MM2S bajty   +08 MM2S: zapis = start, odczyt bit 0 = zajety
  *   +10 S2MM adres   +14 S2MM bajty   +18 S2MM: zapis = start, odczyt bit 0 = zajety
  *   +1C S2MM bajty zapisane (do tlast albo do konca bufora)
  *   +20 op (0 pass, 1 xor, 2 add)     +24 arg
  *   +28 beaty przez blok (zapis = zerowanie licznikow)   +2C suma slow */
case class DmaRegs(bus : BusSlaveFactory, base : BigInt, dma : Axi4StreamDma, op : Axi4StreamOp) extends Area {
  def cmdPort(cmd : Stream[DmaCmd], busy : Bool, off : Int) = new Area {
    val addr  = Reg(UInt(32 bits)) init 0
    val bytes = Reg(UInt(24 bits)) init 0
    bus.readAndWrite(addr, base + off)
    bus.readAndWrite(bytes, base + off + 4)
    val pending = RegInit(False)
    bus.onWrite(base + off + 8) { pending := True }
    when(cmd.fire) { pending := False }
    cmd.valid         := pending
    cmd.payload.addr  := addr
    cmd.payload.bytes := bytes
    bus.read(pending || busy, base + off + 8)
  }
  val mm2s = cmdPort(dma.io.mm2sCmd, dma.io.mm2sBusy, 0x00)
  val s2mm = cmdPort(dma.io.s2mmCmd, dma.io.s2mmBusy, 0x10)
  bus.read(dma.io.s2mmBytes, base + 0x1C)

  val opReg  = Reg(UInt(2 bits)) init 0
  val argReg = Reg(Bits(32 bits)) init 0
  bus.readAndWrite(opReg, base + 0x20)
  bus.readAndWrite(argReg, base + 0x24)
  op.io.op    := opReg
  op.io.arg   := argReg
  op.io.clear := False
  bus.onWrite(base + 0x28) { op.io.clear := True }
  bus.read(op.io.beats, base + 0x28)
  bus.read(op.io.sum, base + 0x2C)
}
