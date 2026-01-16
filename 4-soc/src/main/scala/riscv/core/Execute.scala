// SPDX-License-Identifier: MIT
// MyCPU is freely redistributable under the MIT License. See the file
// "LICENSE" for information on usage and redistribution of this file.

package riscv.core

import chisel3._
import chisel3.util._
import riscv.core.ALU
import riscv.core.ALUControl
import riscv.core.Multiplier
import riscv.core.Divider
import riscv.Parameters

class Execute extends Module {
  val io = IO(new Bundle {
    val instruction         = Input(UInt(Parameters.InstructionWidth))
    val instruction_address = Input(UInt(Parameters.AddrWidth))
    val reg1_data           = Input(UInt(Parameters.DataWidth))
    val reg2_data           = Input(UInt(Parameters.DataWidth))
    val immediate           = Input(UInt(Parameters.DataWidth))
    val aluop1_source       = Input(UInt(1.W))
    val aluop2_source       = Input(UInt(1.W))
    val csr_read_data       = Input(UInt(Parameters.DataWidth))
    val forward_from_mem    = Input(UInt(Parameters.DataWidth))
    val forward_from_wb     = Input(UInt(Parameters.DataWidth))
    val reg1_forward        = Input(UInt(2.W))
    val reg2_forward        = Input(UInt(2.W))

    val mem_alu_result = Output(UInt(Parameters.DataWidth))
    val mem_reg2_data  = Output(UInt(Parameters.DataWidth))
    val csr_write_data = Output(UInt(Parameters.DataWidth))
    
    // Multiplier/divider control signals
    val mul_busy       = Output(Bool())
    val mul_valid      = Output(Bool())
    val div_busy       = Output(Bool())
    val div_valid      = Output(Bool())
  })

  val opcode = io.instruction(6, 0)
  val funct3 = io.instruction(14, 12)
  val funct7 = io.instruction(31, 25)
  val uimm   = io.instruction(19, 15)

  val alu      = Module(new ALU)
  val alu_ctrl = Module(new ALUControl)
  val mul      = Module(new Multiplier)
  val div      = Module(new Divider)

  alu_ctrl.io.opcode := opcode
  alu_ctrl.io.funct3 := funct3
  alu_ctrl.io.funct7 := funct7
  alu.io.func        := alu_ctrl.io.alu_funct
  
  // Detect M-extension instructions
  val is_m_extension = (opcode === InstructionTypes.RM) && (funct7 === 1.U)
  val is_mul = is_m_extension && (funct3 <= 3.U)
  val is_div = is_m_extension && (funct3 >= 4.U)
  
  // Track the last instruction address we started multiplication for
  // This prevents re-triggering the multiplier when the same instruction is stalled in EX
  val last_mul_instr_addr = RegInit(0.U(Parameters.AddrWidth))
  val last_div_instr_addr = RegInit(0.U(Parameters.AddrWidth))
  val is_new_mul_instr = io.instruction_address =/= last_mul_instr_addr
  val is_new_div_instr = io.instruction_address =/= last_div_instr_addr
  
  // Start multiplier when:
  // 1. Current instruction is M-extension
  // 2. Multiplier is idle (not busy)
  // 3. This is a new instruction (different PC)
  mul.io.start := is_mul && !mul.io.busy && is_new_mul_instr
  mul.io.funct3 := funct3
  div.io.start := is_div && !div.io.busy && is_new_div_instr
  div.io.funct3 := funct3
  
  // Record instruction address when starting multiplication
  when(mul.io.start) {
    last_mul_instr_addr := io.instruction_address
  }
  when(div.io.start) {
    last_div_instr_addr := io.instruction_address
  }

  val reg1_data = MuxLookup(
    io.reg1_forward,
    io.reg1_data
  )(
    IndexedSeq(
      ForwardingType.ForwardFromMEM -> io.forward_from_mem,
      ForwardingType.ForwardFromWB  -> io.forward_from_wb
    )
  )
  alu.io.op1 := Mux(
    io.aluop1_source === ALUOp1Source.InstructionAddress,
    io.instruction_address,
    reg1_data
  )

  val reg2_data = MuxLookup(
    io.reg2_forward,
    io.reg2_data
  )(
    IndexedSeq(
      ForwardingType.ForwardFromMEM -> io.forward_from_mem,
      ForwardingType.ForwardFromWB  -> io.forward_from_wb
    )
  )
  alu.io.op2 := Mux(
    io.aluop2_source === ALUOp2Source.Immediate,
    io.immediate,
    reg2_data
  )
  
  // Connect multiplier/divider
  mul.io.op1 := reg1_data
  mul.io.op2 := reg2_data
  div.io.op1 := reg1_data
  div.io.op2 := reg2_data
  alu.io.mul_result := mul.io.result
  alu.io.div_result := div.io.result
  
  // Output multiplier status
  // Generate combinational busy signal: busy when multiplier is busy OR when starting new M-instr
  // This ensures pipeline stalls immediately when M-extension instruction enters EX
  io.mul_busy := mul.io.busy || (is_mul && is_new_mul_instr)
  io.mul_valid := mul.io.valid
  io.div_busy := div.io.busy || (is_div && is_new_div_instr)
  io.div_valid := div.io.valid
  
  io.mem_alu_result := alu.io.result
  io.mem_reg2_data  := reg2_data
  io.csr_write_data := MuxLookup(
    funct3,
    0.U
  )(
    IndexedSeq(
      InstructionsTypeCSR.csrrw  -> reg1_data,
      InstructionsTypeCSR.csrrc  -> io.csr_read_data.&((~reg1_data).asUInt),
      InstructionsTypeCSR.csrrs  -> io.csr_read_data.|(reg1_data),
      InstructionsTypeCSR.csrrwi -> Cat(0.U(27.W), uimm),
      InstructionsTypeCSR.csrrci -> io.csr_read_data.&((~Cat(0.U(27.W), uimm)).asUInt),
      InstructionsTypeCSR.csrrsi -> io.csr_read_data.|(Cat(0.U(27.W), uimm)),
    )
  )
}
