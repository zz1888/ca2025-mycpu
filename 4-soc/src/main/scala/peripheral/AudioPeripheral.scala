// SPDX-License-Identifier: MIT
package peripheral

import bus.AXI4LiteChannels
import bus.AXI4LiteSlave
import chisel3._
import chisel3.util._
import riscv.Parameters

/**
 * Audio output peripheral with AXI4-Lite interface
 *
 * Memory map (Base: 0x60000000):
 *   0x00: ID     - Peripheral identification (RO: 0x41554449 = 'AUDI')
 *   0x04: STATUS - [1] fifo_full, [0] fifo_empty
 *   0x08: DATA   - Write: push 16-bit PCM sample into FIFO
 *
 * Output interface:
 *   - sample       : 16-bit unsigned PCM sample
 *   - sample_valid : asserted when a sample is dequeued
 */
class AudioPeripheral extends Module {
  val io = IO(new Bundle {
    val channels = Flipped(new AXI4LiteChannels(8, Parameters.DataBits))
    val sample = Output(UInt(16.W))
    val sample_valid = Output(Bool())
  })

  // ================= Constants =================
  object Reg {
    val ID     = 0x00
    val STATUS = 0x04
    val DATA   = 0x08
  }
  
  val AUDIO_ID = "h41554449".U  // 'AUDI'

  // ================= AXI4-Lite Slave =================
  val slave = Module(new AXI4LiteSlave(8, Parameters.DataBits))
  slave.io.channels <> io.channels

  // MMIO address decode (offset inside peripheral)
  val addr = slave.io.bundle.address & 0xff.U
  val addr_id     = addr === Reg.ID.U
  val addr_status = addr === Reg.STATUS.U
  val addr_data   = addr === Reg.DATA.U

  // ================= Sample FIFO =================
  // Depth 16384 to hold full 1-second audio buffer at 11025 Hz
  val fifo = Module(new Queue(UInt(16.W), entries = 16384))
  fifo.io.enq.valid := false.B
  fifo.io.enq.bits  := 0.U

  // ================= AXI Write Handling =================
  when(slave.io.bundle.write && addr_data) {
    when(fifo.io.enq.ready) {
      fifo.io.enq.valid := true.B
      fifo.io.enq.bits  := slave.io.bundle.write_data(15, 0)
    }
    // If FIFO full, data is dropped (software should poll STATUS)
  }

  // ================= AXI Read Handling =================
  val read_data_prepared = WireDefault(0.U(32.W))  // 默認返回 0

  when(addr_id) {
    read_data_prepared := AUDIO_ID
  }.elsewhen(addr_status) {
    read_data_prepared := Cat(
      0.U(30.W),
      !fifo.io.enq.ready,  // bit 1: fifo_full
      !fifo.io.deq.valid   // bit 0: fifo_empty
    )
  }
  // DATA 暫存器是寫入專用，讀取返回 0（已由 WireDefault 處理）

  // 與 VGA/UART 一致的時序規則
  slave.io.bundle.read_valid := slave.io.bundle.read
  slave.io.bundle.read_data := read_data_prepared

private val SYS_CLK_HZ = 50000000      // 添加這行
private val SAMPLE_RATE_HZ = 11025     // 添加這行
private val DIV = SYS_CLK_HZ / SAMPLE_RATE_HZ  // ~4535

private val CNT_WIDTH = log2Ceil(DIV + 1)
val tickCnt = RegInit(0.U(CNT_WIDTH.W))
val tick = (tickCnt === (DIV - 1).U)   // 添加這行
tickCnt := Mux(tick, 0.U, tickCnt + 1.U)  // 添加這行

// Only dequeue ONE sample per audio tick
fifo.io.deq.ready := tick
io.sample := fifo.io.deq.bits
io.sample_valid := fifo.io.deq.fire
}
