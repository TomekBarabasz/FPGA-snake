package newhope.mimasrv

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import core.microrv32.bus._
import newhope.mcb._

// =====================================================================
//  Mosty magistral SoC mimas_rv.
//
//    SimpleBusToMig   SimpleBus (CPU)  -> port MCB      (wariant SimpleBus)
//    SimpleBusToAxi4  SimpleBus (CPU)  -> AXI4 master   (wariant AXI4)
//    Axi4ToMig        AXI4 slave       -> port MCB      (wariant AXI4)
//
//  SimpleBus (microrv32): master trzyma valid, slave odpowiada ready na
//  1 takt; dane zapisu i odczytu WYROWNANE DO LSB, size = 1/2/4 bajty.
//  AXI4: dane na swoich pasach bajtowych (adres & 3), WSTRB wskazuje bajty.
//  Port MCB: slowa 128 b, maska aktywna wysokim (1 = bajt pominiety).
// =====================================================================

/** Konfiguracja AXI4 w SoC: 32 b adres i dane, bez pol, ktorych nikt nie uzywa. */
object MimasRvAxi {
  def config = Axi4Config(addressWidth = 32, dataWidth = 32, idWidth = 4,
                          useRegion = false, useLock = false, useCache = false,
                          useProt = false, useQos = false)

  /** Maska bajtow 1/2/4 z pola size SimpleBusa, przesunieta na pasy slowa. */
  def byteEnables(size : UInt, lane : UInt, lanes : Int) : Bits = {
    val m = size.mux[Bits](1 -> B"0001", 2 -> B"0011", default -> B"1111")
    (m.resize(lanes) << lane).resize(lanes)
  }
  /** Odczyt z pasow: przesuniecie do LSB i obciecie do size. */
  def alignRead(data : Bits, lane : UInt, size : UInt) : Bits = {
    val s = (data >> (lane @@ U"000")).resize(32)
    size.mux[Bits](1 -> s(7 downto 0).resized, 2 -> s(15 downto 0).resized, default -> s)
  }
}

/** SimpleBus (32 b, jedno slowo naraz) -> port MCB (128 b), bez AXI.
  * Kazdy dostep to jedna komenda MCB z bl = 0. Do kalibracji nie przyjmuje
  * transakcji. Zakladane naturalne wyrownanie (gcc bez -mno-strict-align). */
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
  val enables  = MimasRvAxi.byteEnables(io.sb.SBsize, lane, c.maskWidth)
  val wrData   = (io.sb.SBwdata.resize(c.dataWidth) << (lane @@ U"000")).resize(c.dataWidth)

  object S extends SpinalEnum { val IDLE, WR_DATA, WR_CMD, RD_CMD, RD_WAIT, RESP = newElement() }
  val state = RegInit(S.IDLE)
  val rdata = Reg(Bits(32 bits)) init 0

  io.port.cmd.valid         := False
  io.port.cmd.payload.instr := MigInstr.WRITE
  io.port.cmd.payload.bl    := 0
  io.port.cmd.payload.addr  := wordAddr.resized
  io.port.wr.valid          := False
  io.port.wr.payload.data   := wrData
  io.port.wr.payload.mask   := ~enables
  io.port.rd.ready          := False
  io.sb.SBready             := state === S.RESP
  io.sb.SBrdata             := rdata

  switch(state) {
    is(S.IDLE)    { when(io.sel && io.sb.SBvalid && io.calib) { state := io.sb.SBwrite ? S.WR_DATA | S.RD_CMD } }
    is(S.WR_DATA) { io.port.wr.valid := True; when(io.port.wr.ready) { state := S.WR_CMD } }
    is(S.WR_CMD)  { io.port.cmd.valid := True; io.port.cmd.payload.instr := MigInstr.WRITE; when(io.port.cmd.ready) { state := S.RESP } }
    is(S.RD_CMD)  { io.port.cmd.valid := True; io.port.cmd.payload.instr := MigInstr.READ;  when(io.port.cmd.ready) { state := S.RD_WAIT } }
    is(S.RD_WAIT) {
      io.port.rd.ready := True
      when(io.port.rd.valid) {
        rdata := MimasRvAxi.alignRead(io.port.rd.payload, lane, io.sb.SBsize)
        state := S.RESP
      }
    }
    is(S.RESP)    { state := S.IDLE }
  }
}

/** SimpleBus (CPU) -> AXI4 master. Jedna transakcja naraz, zawsze pojedynczy
  * beat (len = 0), AXSIZE = rozmiar dostepu. Zapis: AW i W wystawiane razem,
  * odpowiedz po B. Odczyt: AR, potem R, dane przesuniete do LSB. */
case class SimpleBusToAxi4(cfg : Axi4Config) extends Component {
  require(cfg.dataWidth == 32)
  val io = new Bundle {
    val sb  = slave(SimpleBus(32, 32))
    val axi = master(Axi4(cfg))
  }
  val lane   = io.sb.SBaddress(1 downto 0)
  val axSize = io.sb.SBsize.mux[UInt](1 -> U(0, 3 bits), 2 -> U(1, 3 bits), default -> U(2, 3 bits))

  object S extends SpinalEnum { val IDLE, WRITE, WRITE_RESP, READ, READ_DATA, RESP = newElement() }
  val state  = RegInit(S.IDLE)
  val awDone = RegInit(False)
  val wDone  = RegInit(False)
  val rdata  = Reg(Bits(32 bits)) init 0

  def ax(a : Axi4Ax) : Unit = {
    a.addr  := io.sb.SBaddress
    a.id    := 0
    a.len   := 0
    a.size  := axSize
    a.setBurstINCR()
  }
  io.axi.aw.valid := state === S.WRITE && !awDone
  ax(io.axi.aw.payload)
  io.axi.w.valid       := state === S.WRITE && !wDone
  io.axi.w.payload.data := (io.sb.SBwdata << (lane @@ U"000")).resize(32)
  io.axi.w.payload.strb := MimasRvAxi.byteEnables(io.sb.SBsize, lane, 4)
  io.axi.w.payload.last := True
  io.axi.b.ready  := state === S.WRITE_RESP
  io.axi.ar.valid := state === S.READ
  ax(io.axi.ar.payload)
  io.axi.r.ready  := state === S.READ_DATA

  io.sb.SBready := state === S.RESP
  io.sb.SBrdata := rdata

  switch(state) {
    is(S.IDLE) {
      awDone := False; wDone := False
      when(io.sb.SBvalid) { state := io.sb.SBwrite ? S.WRITE | S.READ }
    }
    is(S.WRITE) {
      when(io.axi.aw.fire) { awDone := True }
      when(io.axi.w.fire)  { wDone := True }
      when((awDone || io.axi.aw.fire) && (wDone || io.axi.w.fire)) { state := S.WRITE_RESP }
    }
    is(S.WRITE_RESP) { when(io.axi.b.fire) { rdata := 0; state := S.RESP } }
    is(S.READ)       { when(io.axi.ar.fire) { state := S.READ_DATA } }
    is(S.READ_DATA)  { when(io.axi.r.fire) { rdata := MimasRvAxi.alignRead(io.axi.r.payload.data, lane, io.sb.SBsize); state := S.RESP } }
    is(S.RESP)       { state := S.IDLE }
  }
}

/** AXI4 slave (32 b) -> port MCB (128 b). Jedna transakcja naraz.
  *
  * Bursty INCR o pelnej szerokosci (AxSIZE = 2) albo pojedyncze beaty dowolnej
  * szerokosci (WSTRB wybiera bajty). Burst dzielony na komendy MCB tak, zeby
  * zadna nie miala wiecej niz 64 slowa (FIFO portu) ani nie przecinala wiersza
  * 2 kB (jak MigBurstEngine). Najdluzszy burst AXI4 (256 x 4 B = 1 kB) to
  * dokladnie 64 slowa MCB - jesli zaczyna sie w polowie slowa, wyjda 2 komendy.
  *
  * Zapis: beaty W pakowane w slowa 128 b (maska z WSTRB), slowa do FIFO zapisu
  * PRZED komenda (inaczej wr_underrun), B = OKAY po przyjeciu ostatniej komendy.
  * Odczyt: komenda na kawalek, slowa z FIFO odczytu rozpakowywane na beaty R.
  * Nieobslugiwane (traktowane jak INCR): FIXED, WRAP, waskie bursty (len > 0
  * przy AxSIZE < 2). Do kalibracji AW/AR nie sa przyjmowane. */
case class Axi4ToMig(cfg : Axi4Config, c : MigConfig, window : Int = 26) extends Component {
  require(cfg.dataWidth == 32 && c.dataWidth == 128)
  val io = new Bundle {
    val axi   = slave(Axi4(cfg))
    val calib = in Bool()
    val port  = master(MigPort(c))
  }
  val lanesPerWord = c.dataWidth / 32                    // 4 beaty 32 b w slowie
  val rowWords     = c.rowBytes / c.bytesPerWord          // 128 slow w wierszu
  val wordBits     = window - log2Up(c.bytesPerWord)

  object S extends SpinalEnum { val IDLE, W_COLLECT, W_PUSH, W_CMD, W_RESP, R_CMD, R_DATA = newElement() }
  val state     = RegInit(S.IDLE)
  val id        = Reg(UInt(cfg.idWidth bits)) init 0
  val word      = Reg(UInt(wordBits bits)) init 0        // biezace slowo 128 b
  val lane      = Reg(UInt(2 bits)) init 0               // biezacy pas 32 b w slowie
  val beatsLeft = Reg(UInt(9 bits)) init 0               // len + 1 .. 1
  val chunkWord = Reg(UInt(wordBits bits)) init 0        // pierwsze slowo komendy
  val chunkLeft = Reg(UInt(8 bits)) init 0               // slow do konca biezacej komendy
  val chunkLen  = Reg(UInt(7 bits)) init 0               // slow w biezacej komendzie (zapis)
  val packData  = Reg(Bits(c.dataWidth bits)) init 0
  val packEn    = Reg(Bits(c.maskWidth bits)) init 0

  /** Najwiecej slow, ile moze miec komenda zaczynajaca sie od `w`: 64 i do konca wiersza. */
  def maxChunk(w : UInt) : UInt = {
    val toRowEnd = U(rowWords, 8 bits) - (w(log2Up(rowWords) - 1 downto 0)).resize(8)
    (toRowEnd > 64) ? U(64, 8 bits) | toRowEnd
  }
  /** Slow potrzebnych na `beats` beatow od pasa `l`. */
  def wordsFor(l : UInt, beats : UInt) : UInt = ((l.resize(10) + beats.resize(10) + 3) >> 2).resize(8)

  def startAx(a : Axi4Ax) : Unit = {
    id        := a.id
    word      := (a.addr(window - 1 downto 0) >> log2Up(c.bytesPerWord)).resized
    lane      := a.addr(3 downto 2)
    beatsLeft := a.len.resize(9) + 1
  }

  io.axi.aw.ready := state === S.IDLE && io.calib
  io.axi.ar.ready := state === S.IDLE && io.calib && !io.axi.aw.valid      // zapis ma pierwszenstwo
  io.axi.w.ready  := state === S.W_COLLECT
  io.axi.b.valid  := state === S.W_RESP
  io.axi.b.payload.id := id
  io.axi.b.payload.setOKAY()

  io.port.cmd.valid         := False
  io.port.cmd.payload.instr := MigInstr.WRITE
  io.port.cmd.payload.addr  := (chunkWord << log2Up(c.bytesPerWord)).resized
  io.port.cmd.payload.bl    := 0
  io.port.wr.valid          := state === S.W_PUSH
  io.port.wr.payload.data   := packData
  io.port.wr.payload.mask   := ~packEn

  // R: dane z FIFO odczytu, pas `lane`
  io.axi.r.valid        := state === S.R_DATA && io.port.rd.valid
  io.axi.r.payload.id   := id
  io.axi.r.payload.data := io.port.rd.payload.subdivideIn(32 bits)(lane)
  io.axi.r.payload.last := beatsLeft === 1
  io.axi.r.payload.setOKAY()
  io.port.rd.ready      := io.axi.r.fire && (lane === 3 || beatsLeft === 1)

  switch(state) {
    is(S.IDLE) {
      when(io.axi.aw.fire) {
        startAx(io.axi.aw.payload)
        val w = (io.axi.aw.payload.addr(window - 1 downto 0) >> log2Up(c.bytesPerWord)).resize(wordBits)
        chunkWord := w; chunkLeft := maxChunk(w); chunkLen := 0
        packData := 0; packEn := 0
        state := S.W_COLLECT
      } elsewhen(io.axi.ar.fire) {
        startAx(io.axi.ar.payload)
        state := S.R_CMD
      }
    }
    // ---- zapis --------------------------------------------------------------
    is(S.W_COLLECT) {
      when(io.axi.w.fire) {
        val sh = lane @@ U"00000"                                  // lane * 32
        packData  := packData | (io.axi.w.payload.data.resize(c.dataWidth) << sh).resize(c.dataWidth)
        packEn    := packEn   | (io.axi.w.payload.strb.resize(c.maskWidth) << (lane @@ U"00")).resize(c.maskWidth)
        lane      := lane + 1
        beatsLeft := beatsLeft - 1
        when(lane === 3 || beatsLeft === 1) { state := S.W_PUSH }  // slowo pelne albo koniec burstu
      }
    }
    is(S.W_PUSH) {
      when(io.port.wr.ready) {
        packData := 0; packEn := 0
        word      := word + 1
        chunkLen  := chunkLen + 1
        chunkLeft := chunkLeft - 1
        when(beatsLeft === 0 || chunkLeft === 1) { state := S.W_CMD } otherwise { state := S.W_COLLECT }
      }
    }
    is(S.W_CMD) {
      io.port.cmd.valid         := True
      io.port.cmd.payload.instr := MigInstr.WRITE
      io.port.cmd.payload.bl    := (chunkLen - 1).resized
      when(io.port.cmd.ready) {
        chunkWord := word; chunkLeft := maxChunk(word); chunkLen := 0
        state := (beatsLeft === 0) ? S.W_RESP | S.W_COLLECT
      }
    }
    is(S.W_RESP) { when(io.axi.b.fire) { state := S.IDLE } }
    // ---- odczyt -------------------------------------------------------------
    is(S.R_CMD) {
      val need = wordsFor(lane, beatsLeft)
      val max  = maxChunk(word)
      val n    = (need > max) ? max | need
      io.port.cmd.valid         := True
      io.port.cmd.payload.instr := MigInstr.READ
      io.port.cmd.payload.addr  := (word << log2Up(c.bytesPerWord)).resized
      io.port.cmd.payload.bl    := (n - 1).resized
      when(io.port.cmd.ready) { chunkLeft := n; state := S.R_DATA }
    }
    is(S.R_DATA) {
      when(io.axi.r.fire) {
        lane      := lane + 1
        beatsLeft := beatsLeft - 1
        when(lane === 3 || beatsLeft === 1) {                       // slowo zdjete z FIFO
          word      := word + 1
          chunkLeft := chunkLeft - 1
          when(beatsLeft === 1) { state := S.IDLE }
            .elsewhen(chunkLeft === 1) { state := S.R_CMD }          // nastepna komenda
        }
      }
    }
  }
}
