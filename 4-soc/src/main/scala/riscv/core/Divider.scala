// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package riscv.core

import chisel3._
import chisel3.util._
import riscv.Parameters

/**
 * Multi-cycle divider for RV32M extension (DIV/DIVU/REM/REMU)
 * Extended to support 64-bit division for software library acceleration.
 *
 * 32-bit mode (use_64 = false):
 * - DIV  (100): signed division
 * - DIVU (101): unsigned division
 * - REM  (110): signed remainder
 * - REMU (111): unsigned remainder
 *
 * 64-bit mode (use_64 = true):
 * - Combines op1_high:op1 and op2_high:op2 as 64-bit operands
 * - Returns result in result (low 32 bits) and result_high (high 32 bits)
 *
 * Timing:
 * - Cycle 0: Start asserted, inputs latched
 * - Cycle 1..N: Busy
 * - Cycle N+1: valid asserted for 1 cycle
 */
class Divider extends Module {
  val io = IO(new Bundle {
    val start  = Input(Bool())
    val op1    = Input(UInt(32.W))
    val op2    = Input(UInt(32.W))
    val funct3 = Input(UInt(3.W))
    
    // 64-bit extension interface
    val op1_high = Input(UInt(32.W))
    val op2_high = Input(UInt(32.W))
    val use_64   = Input(Bool())

    val result      = Output(UInt(32.W))
    val result_high = Output(UInt(32.W))
    val valid       = Output(Bool())
    val busy        = Output(Bool())
  })

  object State extends ChiselEnum {
    val sIdle, sCompute, sDone = Value
  }
  val state = RegInit(State.sIdle)

  val op1Reg      = RegInit(0.U(64.W))
  val op2Reg      = RegInit(0.U(64.W))
  val funct3Reg   = RegInit(0.U(3.W))
  val use64Reg    = RegInit(false.B)
  val resultReg   = RegInit(0.U(64.W))
  val counter     = RegInit(0.U(6.W))

  io.result := resultReg(31, 0)
  io.result_high := resultReg(63, 32)
  io.valid := false.B
  io.busy := state =/= State.sIdle

  // Latency configuration (emulates multi-cycle division)
  // 64-bit mode uses longer latency
  val LATENCY_32 = 4.U
  val LATENCY_64 = 8.U

  switch(state) {
    is(State.sIdle) {
      when(io.start) {
        // Latch operands based on mode
        when(io.use_64) {
          op1Reg := Cat(io.op1_high, io.op1)
          op2Reg := Cat(io.op2_high, io.op2)
        }.otherwise {
          op1Reg := io.op1  // Zero-extend 32-bit to 64-bit
          op2Reg := io.op2
        }
        funct3Reg := io.funct3
        use64Reg := io.use_64
        counter := 0.U
        state := State.sCompute
      }
    }
    is(State.sCompute) {
      when(counter === 0.U) {
        when(use64Reg) {
          // 64-bit unsigned division (for __divdi3 acceleration)
          val op2Zero = op2Reg === 0.U
          val denom = Mux(op2Zero, 1.U, op2Reg)
          val quot = op1Reg / denom
          val rem  = op1Reg % denom
          
          // Select quotient or remainder based on funct3
          // Use same encoding: 4=div, 5=divu, 6=rem, 7=remu
          resultReg := Mux(op2Zero,
            Mux(funct3Reg(1), op1Reg, "hFFFFFFFFFFFFFFFF".U(64.W)),  // rem returns dividend, div returns -1
            Mux(funct3Reg(1), rem, quot)
          )
        }.otherwise {
          // 32-bit division (original behavior)
          val op1_32 = op1Reg(31, 0)
          val op2_32 = op2Reg(31, 0)
          val op2Zero   = op2_32 === 0.U
          val overflow  = (op1_32 === "h80000000".U) && (op2_32 === "hFFFFFFFF".U)

          val op1Neg = op1_32(31)
          val op2Neg = op2_32(31)
          val op1Abs = Mux(op1Neg, (~op1_32).asUInt + 1.U, op1_32)
          val op2Abs = Mux(op2Neg, (~op2_32).asUInt + 1.U, op2_32)
          val denom  = Mux(op2Zero, 1.U, op2Abs)

          val unsignedQuot = op1Abs / denom
          val unsignedRemAbs  = op1Abs % denom
          val signedQuotNeg = op1Neg ^ op2Neg
          val signedQuot = Mux(signedQuotNeg, (~unsignedQuot).asUInt + 1.U, unsignedQuot)
          val signedRem  = Mux(op1Neg, (~unsignedRemAbs).asUInt + 1.U, unsignedRemAbs)

          val signedDiv = Mux(op2Zero, "hFFFFFFFF".U, Mux(overflow, "h80000000".U, signedQuot))
          val signedRemFinal = Mux(op2Zero, op1_32, Mux(overflow, 0.U, signedRem))
          val unsignedDiv = Mux(op2Zero, "hFFFFFFFF".U, op1_32 / Mux(op2Zero, 1.U, op2_32))
          val unsignedRem = Mux(op2Zero, op1_32, op1_32 % Mux(op2Zero, 1.U, op2_32))

          val result32 = MuxLookup(
            funct3Reg,
            signedDiv
          )(
            IndexedSeq(
              InstructionsTypeM.div  -> signedDiv,
              InstructionsTypeM.divu -> unsignedDiv,
              InstructionsTypeM.rem  -> signedRemFinal,
              InstructionsTypeM.remu -> unsignedRem
            )
          )
          resultReg := result32  // Zero-extend to 64 bits
        }
      }

      counter := counter + 1.U
      val latency = Mux(use64Reg, LATENCY_64, LATENCY_32)
      when(counter >= latency) {
        state := State.sDone
      }
    }
    is(State.sDone) {
      io.valid := true.B
      state := State.sIdle
    }
  }
}
