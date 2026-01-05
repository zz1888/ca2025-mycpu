// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

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
 *   0x00: ID     - Peripheral identification (RO: 0x41554430 = 'AUD0')
 *   0x04: STATUS - [1] fifo_full, [0] fifo_empty
 *   0x08: DATA   - Write: push 16-bit PCM sample into FIFO
 *
 * Output interface:
 *   - sample        : 16-bit signed PCM sample
 *   - sample_valid  : asserted when a sample is dequeued (consumed)
 */
class AudioPeripheral extends Module {
  val io = IO(new Bundle {
    val channels      = Flipped(new AXI4LiteChannels(8, Parameters.DataBits))
    val sample        = Output(UInt(16.W))
    val sample_valid  = Output(Bool())
  })

  // ================= Constants =================
  object Reg {
    val ID     = 0x00
    val STATUS = 0x04
    val DATA   = 0x08
  }

  val AUDIO_ID = "h41554430".U  // 'AUD0'

  // ================= AXI4-Lite Slave =================
  val slave = Module(new AXI4LiteSlave(8, Parameters.DataBits))
  slave.io.channels <> io.channels

  // MMIO address decode (offset inside peripheral)
  val addr = slave.io.bundle.address & 0xff.U
  val addr_id     = addr === Reg.ID.U
  val addr_status = addr === Reg.STATUS.U
  val addr_data   = addr === Reg.DATA.U

  // ================= Sample FIFO =================
  // Depth 8 is enough for Verilator + software audio
  val fifo = Module(new Queue(UInt(16.W), entries = 8))

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
  val read_data_prepared = WireDefault(0.U(32.W))

  when(addr_id) {
    read_data_prepared := AUDIO_ID
  }.elsewhen(addr_status) {
    read_data_prepared := Cat(
      0.U(30.W),
      fifo.io.enq.ready === false.B, // fifo_full
      fifo.io.deq.valid === false.B  // fifo_empty
    )
  }
  // Same AXI timing discipline as VGA:
  // assert read_valid only when responding to a read request
  slave.io.bundle.read_valid := slave.io.bundle.read
  slave.io.bundle.read_data  := read_data_prepared

  // ================= Audio Output =================
  fifo.io.deq.ready := true.B

  io.sample       := fifo.io.deq.bits
  io.sample_valid := fifo.io.deq.fire
}
