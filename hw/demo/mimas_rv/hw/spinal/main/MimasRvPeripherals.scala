package newhope.mimasrv

import spinal.core._
import spinal.lib._
import spinal.lib.bus.misc.BusSlaveFactory
import spinal.lib.com.uart._
import newhope.mimas_v2.SevenSegMux

// =====================================================================
//  Peryferia SoC niezalezne od magistrali.
//
//  Kazde dostaje BusSlaveFactory i adres bazowy - i nic wiecej nie wie o
//  protokole. Ten sam kod siedzi za SimpleBusSlaveFactory (wariant
//  SimpleBus) i za AxiLite4SlaveFactory (wariant AXI4 + AXI-Lite).
//  `base` jest w przestrzeni adresow danej fabryki: lokalny offset przy
//  SimpleBus (kazde peryferium ma wlasny port dekodera), adres bezwzgledny
//  przy AXI-Lite (fabryka widzi pelny adres z crossbara).
//
//  Rejestry 1:1 z peryferiami microrv32 (SBLED, SBSUart, RVCLIC,
//  SBShutdown) - ta sama mapa, ten sam sw/monitor.hex w obu wariantach.
// =====================================================================
object MimasRvPeripherals {

  /** LED D1..D8: +0 r/w (bity 7..0). */
  case class Led(bus : BusSlaveFactory, base : BigInt) extends Area {
    val reg = Reg(Bits(32 bits)) init 0
    bus.readAndWrite(reg, base)
    def leds : Bits = reg(7 downto 0)
  }

  /** UART 8N1 (UartCtrl ze spinal.lib), jak SBSUart:
    *   +0  TX dane (zapis)       +4  zapis 1 = wyslij, odczyt = TX gotowy
    *   +8  RX dane (odczyt zdejmuje z FIFO)   +C  zajetosc FIFO RX
    *   +10 RX prawie puste (1 bajt)           +14 RX puste */
  case class Uart(bus : BusSlaveFactory, base : BigInt, baudrate : HertzNumber = 115200 Hz,
                  rxFifoDepth : Int = 16) extends Area {
    val ctrl = new UartCtrl()
    ctrl.io.config.setClockDivider(baudrate)
    ctrl.io.config.frame.dataLength := 7          // 8 bitow
    ctrl.io.config.frame.parity     := UartParityType.NONE
    ctrl.io.config.frame.stop       := UartStopType.ONE
    ctrl.io.writeBreak := False

    val tx = Stream(Bits(8 bits))
    bus.drive(tx.payload, base + 0x0)
    tx.valid := bus.setOnSet(RegNext(False) init False, base + 0x4, 0)   // impuls 1 takt po zapisie 1
    bus.read(tx.ready, base + 0x4)
    ctrl.io.write <-< tx

    val rxFifo = StreamFifo(Bits(8 bits), rxFifoDepth)
    rxFifo.io.push << ctrl.io.read
    rxFifo.io.pop.ready := False
    bus.read(rxFifo.io.pop.payload, base + 0x8)
    bus.onRead(base + 0x8) { rxFifo.io.pop.ready := True }
    bus.read(rxFifo.io.occupancy, base + 0xC)
    bus.read(rxFifo.io.occupancy === 1, base + 0x10)
    bus.read(rxFifo.io.occupancy === 0, base + 0x14)

    def uart = ctrl.io.uart
  }

  /** Timer RISC-V jak RVCLIC: mtime (+BFF8/+BFFC, co takt +1, tylko odczyt),
    * mtimecmp (+4000/+4004, r/w); przerwanie przy mtime >= mtimecmp. */
  case class Clic(bus : BusSlaveFactory, base : BigInt) extends Area {
    val mtime    = Reg(UInt(64 bits)) init 0
    val mtimecmp = Reg(UInt(64 bits)) init 0
    mtime := mtime + 1
    bus.read(mtime(31 downto 0),  base + 0xBFF8)
    bus.read(mtime(63 downto 32), base + 0xBFFC)
    bus.readAndWrite(mtimecmp(31 downto 0),  base + 0x4000)
    bus.readAndWrite(mtimecmp(63 downto 32), base + 0x4004)
    val irq = RegNext(mtime >= mtimecmp) init False
  }

  /** Zatrzymanie CPU jak SBShutdown: zapis 93 pod +0 = koniec, pod +4 = koniec z bledem. */
  case class Shutdown(bus : BusSlaveFactory, base : BigInt) extends Area {
    val halt    = RegInit(False)
    val haltErr = RegInit(False)
    val data    = Bits(32 bits)
    bus.nonStopWrite(data)
    bus.onWrite(base + 0x0) { when(data.asUInt === 93) { halt := True } }
    bus.onWrite(base + 0x4) { when(data.asUInt === 93) { haltErr := True } }
  }

  /** Rejestry plytki: +0 DIP (1 = ON), +4 przyciski, +8 wyswietlacz 7-seg
    * (jak demo/mimas_j1), +C status LPDDR (bit 0 = skalibrowana, bit 1 = blad portu MCB). */
  case class Board(bus : BusSlaveFactory, base : BigInt,
                   dipsIn : Bits, buttonsIn : Bits, calib : Bool, faults : Bits) extends Area {
    val dips    = BufferCC(dipsIn, init = B(0, widthOf(dipsIn) bits))
    val buttons = BufferCC(buttonsIn, init = B(0, widthOf(buttonsIn) bits))
    bus.read(dips, base + 0x0)
    bus.read(buttons, base + 0x4)

    val segValue = Reg(Bits(16 bits)) init 0x0AAA
    bus.readAndWrite(segValue, base + 0x8)
    val mux = SevenSegMux(clkFrequency = ClockDomain.current.frequency.getValue)
    for (i <- 0 until 3) {                      // i = 0: lewa cyfra = bity 11..8
      val n = 2 - i
      mux.io.digits(i).code := segValue(4 * n + 3 downto 4 * n).asUInt
      mux.io.digits(i).dot  := segValue(12 + n)
    }

    val fault = RegInit(False) setWhen faults.orR            // lepki
    bus.read(fault ## calib, base + 0xC)
  }
}
