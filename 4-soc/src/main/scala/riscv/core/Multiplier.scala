// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package riscv.core

import chisel3._
import chisel3.util._
import riscv.Parameters

/**
 * Multi-cycle multiplier for RV32M extension
 * 
 * Implements MUL, MULH, MULHSU, MULHU instructions with 2-3 cycle latency.
 * Uses Chisel's native multiplication (infers DSP blocks on FPGA).
 * 
 * Operation modes (controlled by funct3):
 * - MUL (000):    Returns lower 32 bits of signed × signed
 * - MULH (001):   Returns upper 32 bits of signed × signed
 * - MULHSU (010): Returns upper 32 bits of signed × unsigned
 * - MULHU (011):  Returns upper 32 bits of unsigned × unsigned
 * 
 * Timing:
 * - Cycle 0: Start signal asserted, inputs latched, computation begins
 * - Cycle 1-2: Computation in progress (busy asserted)
 * - Cycle 3: Result ready (valid asserted for 1 cycle)
 * 
 * Pipeline integration:
 * - Execute stage asserts 'start' when M-extension instruction detected
 * - Control unit stalls pipeline while 'busy' is asserted
 * - Result is forwarded when 'valid' is asserted
 */
class Multiplier extends Module {
  val io = IO(new Bundle {
    val start    = Input(Bool())           // Start multiplication
    val op1      = Input(UInt(32.W))       // Multiplicand
    val op2      = Input(UInt(32.W))       // Multiplier
    val funct3   = Input(UInt(3.W))        // Operation type (MUL/MULH/MULHSU/MULHU)
    
    val result   = Output(UInt(32.W))      // Multiplication result
    val valid    = Output(Bool())          // Result valid (1 cycle pulse)
    val busy     = Output(Bool())          // Multiplier busy
  })
  
  // State machine
  object State extends ChiselEnum {
    val sIdle, sCompute, sDone = Value
  }
  val state = RegInit(State.sIdle)
  
  // Internal registers
  val op1_reg = RegInit(0.U(32.W))
  val op2_reg = RegInit(0.U(32.W))
  val funct3_reg = RegInit(0.U(3.W))
  val product = RegInit(0.U(64.W))
  val counter = RegInit(0.U(6.W))  // Count up to 33 cycles (0-32)
  
  // Default outputs
  val is_mul = funct3_reg === 0.U  // MUL: lower 32 bits
  io.result := Mux(is_mul, product(31, 0), product(63, 32))
  io.valid := false.B
  // Busy when not idle
  io.busy := (state =/= State.sIdle)
  
  // Latency configuration: 2 cycles (fast but larger area)
  // For slower but smaller design, increase to 4-8 cycles
  val LATENCY = 2.U
  
  switch(state) {
    is(State.sIdle) {
      when(io.start) {
        // Latch inputs first
        op1_reg := io.op1
        op2_reg := io.op2
        funct3_reg := io.funct3
        counter := 0.U
        
        // Transition to Compute (multiplication will happen in next cycle using latched values)
        state := State.sCompute
      }
    }
    
    is(State.sCompute) {
      when(counter === 0.U) {
        // Perform multiplication on first compute cycle using LATCHED operands
        val op1_signed = op1_reg.asSInt
        val op2_signed = op2_reg.asSInt
        val op1_unsigned = op1_reg
        val op2_unsigned = op2_reg
        
        // Determine signedness based on latched funct3
        val is_mulh = funct3_reg === 1.U    // MULH: signed × signed
        val is_mulhsu = funct3_reg === 2.U  // MULHSU: signed × unsigned
        val is_mulhu = funct3_reg === 3.U   // MULHU: unsigned × unsigned
        
        // Compute 64-bit product
        product := MuxCase(
          (op1_signed * op2_signed).asUInt,  // Default: MUL/MULH (signed × signed)
          IndexedSeq(
            is_mulhsu -> (op1_signed * op2_unsigned.zext).asUInt,  // MULHSU: signed × unsigned (zero-extend op2)
            is_mulhu  -> (op1_unsigned * op2_unsigned)              // MULHU: unsigned × unsigned
          )
        )
      }
      
      counter := counter + 1.U
      when(counter >= LATENCY) {
        state := State.sDone
      }
    }
    
    is(State.sDone) {
      io.valid := true.B
      state := State.sIdle
    }
  }
}
