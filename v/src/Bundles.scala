package v

import chisel3._
import chisel3.util.experimental.decode.DecodeBundle
import chisel3.util.{log2Ceil, Decoupled, DecoupledIO, Valid, ValidIO}

/** Interface from CPU. */
class VRequest(xLen: Int) extends Bundle {

  /** instruction fetched by scalar processor. */
  val instruction: UInt = UInt(32.W)

  /** data read from scalar RF RS1.
    * TODO: rename to rs1Data
    */
  val src1Data: UInt = UInt(xLen.W)

  /** data read from scalar RF RS2.
    * TODO: rename to rs2Data
    */
  val src2Data: UInt = UInt(xLen.W)
}

/** Interface to CPU. */
class VResponse(xLen: Int) extends Bundle {

  /** data write to scalar rd. */
  val data: UInt = UInt(xLen.W)

  /** Vector Fixed-Point Saturation Flag, propagate to vcsr in CSR.
    * This is not maintained in the vector coprocessor since it is not used in the Vector processor.
    */
  val vxsat: Bool = Bool()

  /** assert of [[rd.valid]] indicate vector need to write rd,
    * the [[rd.bits]] is the index of rd
    * TODO: merge [[data]] to rd.
    */
  val rd: ValidIO[UInt] = Valid(UInt(log2Ceil(32).W))

  /** when [[mem]] is asserted, indicate the instruction need to access memory.
    * if the vector instruction need to access memory,
    * to maintain the order of memory access:
    * the scalar core maintains a counter of vector memory access,
    * if the counter is not zero, the memory access instruction of scalar core will stall until the counter is zero.
    *
    * TODO: rename to `memAccess`
    */
  val mem: Bool = Bool()
}

class InstructionRecord(instructionIndexWidth: Int) extends Bundle {

  /** record the index of this instruction,
    * this is maintained by [[V.instructionCounter]]
    */
  val instructionIndex: UInt = UInt(instructionIndexWidth.W)

  /** whether instruction is `crossWrite`,
    * for instructions has `widen`, it need use cross lane write channel,
    * but lane will regard the instruction is finished when data is sent to ring,
    * so we need this bit to record if the ring is cleared.
    */
  val hasCrossWrite: Bool = Bool()

  /** whether instruction is load store.
    * it should tell scalar core if this is a load store unit.
    */
  val isLoadStore: Bool = Bool()
}

/** context for state machine:
  * w: passive, s: initiative
  * assert: don't need execute or is executed.
  * deassert: need execute.
  */
class InstructionState extends Bundle {

  /** wait for last signal from each lanes and [[LSU]].
    * TODO: remove wLast. last = control.endTag.asUInt.andR & (!control.record.widen || busClear)
    */
  val wLast: Bool = Bool()

  /** the slot is idle. */
  val idle: Bool = Bool()

  /** used for mask unit, schedule mask unit to execute. */
  val sMaskUnitExecution: Bool = Bool()

  /** used for instruction commit, schedule [[V]] to commit. */
  val sCommit: Bool = Bool()
}

class ExecutionUnitType extends Bundle {
  val logic: Bool = Bool()
  val adder: Bool = Bool()
  val shift: Bool = Bool()
  val multiplier: Bool = Bool()
  val divider: Bool = Bool()
  val other: Bool = Bool()
}

class InstructionControl(instIndexWidth: Int, laneSize: Int) extends Bundle {

  /** metadata for this instruction. */
  val record: InstructionRecord = new InstructionRecord(instIndexWidth)

  /** control state to record the current execution state. */
  val state: InstructionState = new InstructionState

  /** tag for recording each lane and lsu is finished for this instruction.
    * TODO: move to `state`.
    */
  val endTag: Vec[Bool] = Vec(laneSize + 1, Bool())

  val executionUnitType: ExecutionUnitType = new ExecutionUnitType
}

class ExtendInstructionType extends Bundle {
  val extend:   Bool = Bool()
  val mv:       Bool = Bool()
  val ffo:      Bool = Bool()
  val popCount: Bool = Bool()
  val id:       Bool = Bool()
}

/** interface from [[V]] to [[Lane]]. */
class LaneRequest(param: LaneParameter) extends Bundle {
  val instructionIndex: UInt = UInt(param.instructionIndexBits.W)
  // decode
  val decodeResult: DecodeBundle = Decoder.bundle
  val loadStore:    Bool = Bool()
  val store:        Bool = Bool()
  val special:      Bool = Bool()

  // instruction
  /** vs1 or imm */
  val vs1: UInt = UInt(5.W)

  /** vs2 or rs2 */
  val vs2: UInt = UInt(5.W)

  /** vd or rd */
  val vd: UInt = UInt(5.W)

  val loadStoreEEW: UInt = UInt(2.W)

  /** mask type ? */
  val mask: Bool = Bool()

  val segment: UInt = UInt(3.W)

  /** data of rs1 */
  val readFromScalar: UInt = UInt(param.datapathWidth.W)

  // vmacc 的vd需要跨lane读 TODO: move to [[V]]
  def ma: Bool =
    decodeResult(Decoder.multiplier) && decodeResult(Decoder.uop)(1, 0).xorR && !decodeResult(Decoder.vwmacc)

  // TODO: move to Module
  def initState: InstGroupState = {
    val res: InstGroupState = Wire(new InstGroupState(param))
    val sCrossRead = !decodeResult(Decoder.crossRead)
    val sCrossWrite = !decodeResult(Decoder.crossWrite)
    val readOnly = decodeResult(Decoder.readOnly)
    // decode的时候需要注意有些bit操作的指令虽然不需要读vs1,但是需要读v0
    res.sRead1 := !decodeResult(Decoder.vtype)
    res.sRead2 := false.B
    res.sReadVD := decodeResult(Decoder.sReadVD)
    res.wCrossReadLSB := sCrossRead
    res.wCrossReadMSB := sCrossRead
    res.wScheduler := decodeResult(Decoder.scheduler)
    res.sScheduler := decodeResult(Decoder.scheduler)
    res.sExecute := decodeResult(Decoder.execute)
    res.wExecuteRes := decodeResult(Decoder.execute)
    res.sWrite := decodeResult(Decoder.sWrite)
    res.sCrossWriteLSB := sCrossWrite
    res.sCrossWriteMSB := sCrossWrite
    res.sSendCrossReadResultLSB := sCrossRead
    res.sSendCrossReadResultMSB := sCrossRead
    res.sCrossReadLSB := sCrossRead
    res.sCrossReadMSB := sCrossRead
    res
  }

  // TODO: move to Module
  def instType: UInt = {
    VecInit(
      Seq(
        decodeResult(Decoder.logic) && !decodeResult(Decoder.other),
        decodeResult(Decoder.adder) && !decodeResult(Decoder.other),
        decodeResult(Decoder.shift) && !decodeResult(Decoder.other),
        decodeResult(Decoder.multiplier) && !decodeResult(Decoder.other),
        decodeResult(Decoder.divider) && !decodeResult(Decoder.other),
        decodeResult(Decoder.other)
      )
    ).asUInt
  }
}

class InstGroupState(param: LaneParameter) extends Bundle {
  // s for scheduler
  //   0 is for need to do but not do for now
  //   1 is done or don't need to do
  // w for wait
  //   0 is for need to wait and still waiting for finishing
  //   1 is for don't need to wait or already finished
  /** schedule read src1 */
  val sRead1: Bool = Bool()

  /** schedule read src2 */
  val sRead2: Bool = Bool()

  /** schedule read vd */
  val sReadVD: Bool = Bool()

  /** wait scheduler send [[LaneResponseFeedback]] */
  val wScheduler: Bool = Bool()

  /** schedule send [[LaneResponse]] to scheduler */
  val sScheduler: Bool = Bool()

  /** schedule execute. */
  val sExecute: Bool = Bool()

  /** wait for execute result. */
  val wExecuteRes: Bool = Bool()

  /** schedule write VRF. */
  val sWrite: Bool = Bool()

  // Cross lane
  // For each lane it won't request others to cross.
  // All the cross request is decoded from the [[V]]
  // Thus each lane knows it should send or wait for cross lane read/write requests.
  /** schedule cross lane write LSB */
  val sCrossWriteLSB: Bool = Bool()

  /** schedule cross lane write MSB */
  val sCrossWriteMSB: Bool = Bool()

  /** wait for cross lane read LSB result. */
  val wCrossReadLSB: Bool = Bool()

  /** wait for cross lane read MSB result. */
  val wCrossReadMSB: Bool = Bool()

  /** schedule cross lane read LSB.(access VRF for cross read) */
  val sCrossReadLSB: Bool = Bool()

  /** schedule cross lane read MSB.(access VRF for cross read) */
  val sCrossReadMSB: Bool = Bool()

  /** schedule send cross lane read LSB result. */
  val sSendCrossReadResultLSB: Bool = Bool()

  /** schedule send cross lane read MSB result. */
  val sSendCrossReadResultMSB: Bool = Bool()
}

/** Instruction State in [[Lane]]. */
class InstructionControlRecord(param: LaneParameter) extends Bundle {

  /** Store request from [[V]]. */
  val laneRequest: LaneRequest = new LaneRequest(param)

  /** State machine of the current instruction. */
  val state: InstGroupState = new InstGroupState(param)

  /** the initial state of state machine,
    * for each group refresh, it will be updated to [[state]]
    */
  val initState: InstGroupState = new InstGroupState(param)

  /** csr follows the instruction.
    * TODO: move to [[laneRequest]]
    */
  val csr: CSRInterface = new CSRInterface(param.vlMaxBits)

  /** which group in the slot is executing. */
  val groupCounter: UInt = UInt(param.groupNumberBits.W)

  /** the mask instruction is finished by other lanes,
    * for example, sbf(set before first)
    * TODO: better name.
    */
  val schedulerComplete: Bool = Bool()

  /** the mask instruction is finished by this group.
    * the instruction target is finished(but still need to access VRF.)
    */
  val selfCompleted: Bool = Bool()

  /** the execution index in group
    * use byte as granularity,
    * SEW
    * 0 -> 00 01 10 11
    * 1 -> 00    10
    * 2 -> 00
    * TODO: 3(64 only)
    */
  val executeIndex: UInt = UInt(log2Ceil(param.dataPathByteWidth).W)

  /** used for indicating the instruction is finished.
    * for small vl, lane might not be used.
    */
  val instructionFinished: Bool = Bool()

  /** 存 mask */
  val mask: ValidIO[UInt] = Valid(UInt(param.datapathWidth.W))

  /** 把mask按每四个分一个组,然后看orR */
  val maskGroupedOrR: UInt = UInt((param.datapathWidth / param.sewMin).W)

  /** 这一组写vrf的mask */
  val vrfWriteMask: UInt = UInt(4.W)
}

/** CSR Interface from Scalar Core. */
class CSRInterface(vlWidth: Int) extends Bundle {

  /** Vector Length Register `vl`,
    * see [[https://github.com/riscv/riscv-v-spec/blob/8c8a53ccc70519755a25203e14c10068a814d4fd/v-spec.adoc#35-vector-length-register-vl]]
    */
  val vl: UInt = UInt(vlWidth.W)

  /** Vector Start Index CSR `vstart`,
    * see [[https://github.com/riscv/riscv-v-spec/blob/8c8a53ccc70519755a25203e14c10068a814d4fd/v-spec.adoc#37-vector-start-index-csr-vstart]]
    * TODO: rename to `vstart`
    */
  val vStart: UInt = UInt(vlWidth.W)

  /** Vector Register Grouping `vlmul[2:0]`
    * subfield of `vtype``
    * see [[https://github.com/riscv/riscv-v-spec/blob/8c8a53ccc70519755a25203e14c10068a814d4fd/v-spec.adoc#342-vector-register-grouping-vlmul20]]
    */
  val vlmul: UInt = UInt(3.W)

  /** Vector Register Grouping (vlmul[2:0])
    * subfield of `vtype``
    * see [[https://github.com/riscv/riscv-v-spec/blob/8c8a53ccc70519755a25203e14c10068a814d4fd/v-spec.adoc#341-vector-selected-element-width-vsew20]]
    */
  val vSew: UInt = UInt(2.W)

  /** Rounding mode register
    * see [[https://github.com/riscv/riscv-v-spec/blob/8c8a53ccc70519755a25203e14c10068a814d4fd/v-spec.adoc#38-vector-fixed-point-rounding-mode-register-vxrm]]
    */
  val vxrm: UInt = UInt(2.W)

  /** Vector Tail Agnostic
    * see [[https://github.com/riscv/riscv-v-spec/blob/8c8a53ccc70519755a25203e14c10068a814d4fd/v-spec.adoc#38-vector-fixed-point-rounding-mode-register-vxrm]]
    *
    * we always keep the undisturbed behavior, since there is no rename here.
    */
  val vta: Bool = Bool()

  /** Vector Mask Agnostic
    * see [[https://github.com/riscv/riscv-v-spec/blob/8c8a53ccc70519755a25203e14c10068a814d4fd/v-spec.adoc#38-vector-fixed-point-rounding-mode-register-vxrm]]
    *
    * we always keep the undisturbed behavior, since there is no rename here.
    */
  val vma: Bool = Bool()

  /** TODO: remove it. */
  val ignoreException: Bool = Bool()
}

/** [[Lane]] -> [[V]], response for [[LaneRequest]] */
class LaneResponse(param: LaneParameter) extends Bundle {

  /** data sending to [[V]]. */
  val data: UInt = UInt(param.datapathWidth.W)

  /** if [[toLSU]] is asserted, this field is used to indicate the data is sending to LSU,
    * otherwise, it is for mask unit
    */
  val toLSU: Bool = Bool()

  /** successfully find first one in the [[V]]. */
  val ffoSuccess: Bool = Bool()

  /** which instruction is the source of this transaction
    * TODO: for DEBUG use.
    */
  val instructionIndex: UInt = UInt(param.instructionIndexBits.W)
}

class ReadBusData(param: LaneParameter) extends Bundle {

  /** data field of the bus. */
  val data: UInt = UInt(param.datapathWidth.W)

  /** The cross lane data is double to [[param.datapathWidth]],
    * assertion of this flag is indicating that the data is the second half of the cross lane data.
    */
  val isTail: Bool = Bool()

  /** indicate which lane is the source of this transaction
    * TODO: for DEBUG use.
    */
  val sourceIndex: UInt = UInt(param.laneNumberBits.W)

  /** indicate which lane is the sink of this transaction */
  val sinkIndex: UInt = UInt(param.laneNumberBits.W)

  /** which instruction is the source of this transaction
    * TODO: for DEBUG use.
    */
  val instructionIndex: UInt = UInt(param.instructionIndexBits.W)

  /** define the order of the data to dequeue from ring. */
  val counter: UInt = UInt(param.groupNumberBits.W)
}

class WriteBusData(param: LaneParameter) extends Bundle {

  /** data field of the bus. */
  val data: UInt = UInt(param.datapathWidth.W)

  /** The cross lane data is double to [[param.datapathWidth]],
    * assertion of this flag is indicating that the data is the second half of the cross lane data.
    */
  val isTail: Bool = Bool()

  /** used for instruction with mask. */
  val mask: UInt = UInt((param.datapathWidth / 2 / 8).W)

  /** indicate which lane is the sink of this transaction */
  val sinkIndex: UInt = UInt(param.laneNumberBits.W)

  /** indicate which lane is the source of this transaction
    * TODO: for DEBUG use.
    */
  val sourceIndex: UInt = UInt(param.laneNumberBits.W)

  /** which instruction is the source of this transaction
    * TODO: for DEBUG use.
    */
  val instructionIndex: UInt = UInt(param.instructionIndexBits.W)

  /** define the order of the data to dequeue from ring. */
  val counter: UInt = UInt(param.groupNumberBits.W)
}

class RingPort[T <: Data](gen: T) extends Bundle {
  val enq: DecoupledIO[T] = Flipped(Decoupled(gen))
  val deq: DecoupledIO[T] = Decoupled(gen)
}

/** [[V]] -> [[Lane]], ack of [[LaneResponse]] */
class LaneResponseFeedback(param: LaneParameter) extends Bundle {

  /** which instruction is the source of this transaction
    * TODO: for DEBUG use.
    */
  val instructionIndex: UInt = UInt(param.instructionIndexBits.W)

  /** for instructions that might finish in other lanes,
    * use [[complete]] to tell the target lane
    * - LSU indexed type load store is finished in LSU.
    * - find first one is found by other lanes.
    * - the VRF read operation of mask unit is finished.
    */
  val complete: Bool = Bool()
}

class V0Update(param: LaneParameter) extends Bundle {
  val data:   UInt = UInt(param.datapathWidth.W)
  val offset: UInt = UInt(param.vrfOffsetBits.W)
  // mask/ld类型的有可能不会写完整的32bit
  val mask: UInt = UInt(4.W)
}

/** Request to access VRF in each lanes. */
class VRFReadRequest(regNumBits: Int, offsetBits: Int, instructionIndexBits: Int) extends Bundle {

  /** address to access VRF.(v0, v1, v2, ...) */
  val vs: UInt = UInt(regNumBits.W)

  /** the offset of VRF access. */
  val offset: UInt = UInt(offsetBits.W)

  /** index for record the age of instruction, designed for handling RAW hazard */
  val instructionIndex: UInt = UInt(instructionIndexBits.W)
}

class VRFWriteRequest(regNumBits: Int, offsetBits: Int, instructionIndexSize: Int, dataPathWidth: Int) extends Bundle {

  /** address to access VRF.(v0, v1, v2, ...) */
  val vd: UInt = UInt(regNumBits.W)

  /** the offset of VRF access. */
  val offset: UInt = UInt(offsetBits.W)

  /** write mask in byte. */
  val mask: UInt = UInt((dataPathWidth / 8).W)

  /** data to write to VRF. */
  val data: UInt = UInt(dataPathWidth.W)

  /** this is the last write of this instruction
    * TODO: rename to isLast.
    */
  val last: Bool = Bool()

  /** used to update the record in VRF. */
  val instructionIndex: UInt = UInt(instructionIndexSize.W)
}

class VRFWriteReport(param: VRFParam) extends Bundle {
  val vd:        ValidIO[UInt] = Valid(UInt(param.regNumBits.W))
  val vs1:       ValidIO[UInt] = Valid(UInt(param.regNumBits.W))
  val vs2:       UInt = UInt(param.regNumBits.W)
  val instIndex: UInt = UInt(param.instructionIndexBits.W)
  val vdOffset:  UInt = UInt(3.W)
  val offset:    UInt = UInt(param.vrfOffsetBits.W)
  val seg:       ValidIO[UInt] = Valid(UInt(3.W))
  val eew:       UInt = UInt(2.W)
  val ls:        Bool = Bool()
  val st:        Bool = Bool()
  val narrow:    Bool = Bool()
  val widen:     Bool = Bool()
  val stFinish:  Bool = Bool()
  // 乘加
  val ma:           Bool = Bool()
  val unOrderWrite: Bool = Bool()
  // csr 如果是浮点的就校正为0
  val mul: UInt = UInt(2.W)
}

/** 为了decode, 指令需要在入口的时候打一拍, 这是需要保存的信息 */
class InstructionPipeBundle(parameter: VParameter) extends Bundle {
  // 原始指令信息
  val request: VRequest = new VRequest(parameter.xLen)
  // decode 的结果
  val decodeResult: DecodeBundle = new DecodeBundle(Decoder.all)
  // 这条指令被vector分配的index
  val instructionIndex: UInt = UInt(parameter.instructionIndexBits.W)
  // 指令的csr信息
  val csr = new CSRInterface(parameter.laneParam.vlMaxBits)
  // 有写v0的风险
  val vdIsV0: Bool = Bool()
}

class LSUWriteQueueBundle(param: LSUParam) extends Bundle {
  val data: VRFWriteRequest =
    new VRFWriteRequest(param.regNumBits, param.vrfOffsetBits, param.instructionIndexBits, param.datapathWidth)
  val targetLane: UInt = UInt(param.laneNumber.W)
}

class LSUInstructionInformation extends Bundle {

  /** specifies the number of fields in each segment, for segment load/stores
    * NFIELDS = nf + 1
    * see [[https://github.com/riscv/riscv-v-spec/blob/8c8a53ccc70519755a25203e14c10068a814d4fd/v-spec.adoc#71-vector-loadstore-instruction-encoding]]
    */
  val nf: UInt = UInt(3.W)

  /** extended memory element width
    * see [[https://github.com/riscv/riscv-v-spec/blob/8c8a53ccc70519755a25203e14c10068a814d4fd/v-spec.adoc#sec-vector-loadstore-width-encoding]]
    *
    * this field is always tied to 0, since spec is reserved for future use.
    *
    * TODO: add illegal instruction exception in scalar decode stage for it.
    */
  val mew: Bool = Bool()

  /** specifies memory addressing mode
    * see [[https://github.com/riscv/riscv-v-spec/blob/8c8a53ccc70519755a25203e14c10068a814d4fd/v-spec.adoc#72-vector-loadstore-addressing-modes]]
    * table 7, and table 8
    *
    * - 00: unit stride
    * - 01: indexed unordered
    * - 10: stride
    * - 11: indexed ordered
    */
  val mop: UInt = UInt(2.W)

  /** additional field encoding variants of unit-stride instructions
    * see [[https://github.com/riscv/riscv-v-spec/blob/8c8a53ccc70519755a25203e14c10068a814d4fd/v-spec.adoc#71-vector-loadstore-instruction-encoding]]
    * and [[https://github.com/riscv/riscv-v-spec/blob/8c8a53ccc70519755a25203e14c10068a814d4fd/v-spec.adoc#72-vector-loadstore-addressing-modes]]
    * table 9 and table 10
    *
    * 0b00000 -> unit stride
    * 0b01000 -> whole register
    * 0b01011 -> mask, eew = 8
    * 0b10000 -> fault only first (load)
    */
  val lumop: UInt = UInt(5.W)

  /** specifies size of memory elements, and distinguishes from FP scalar
    * MSB is ignored, which is used in FP.
    * see [[https://github.com/riscv/riscv-v-spec/blob/8c8a53ccc70519755a25203e14c10068a814d4fd/v-spec.adoc#71-vector-loadstore-instruction-encoding]]
    * and [[https://github.com/riscv/riscv-v-spec/blob/8c8a53ccc70519755a25203e14c10068a814d4fd/v-spec.adoc#73-vector-loadstore-width-encoding]]
    * table 11
    *
    * 00 -> 8
    * 01 -> 16
    * 10 -> 32
    * 11 -> 64
    */
  val eew: UInt = UInt(2.W)

  /** specifies v register holding store data
    * see [[https://github.com/riscv/riscv-v-spec/blob/8c8a53ccc70519755a25203e14c10068a814d4fd/v-spec.adoc#71-vector-loadstore-instruction-encoding]]
    */
  val vs3: UInt = UInt(5.W)

  /** indicate if this instruction is store. */
  val isStore: Bool = Bool()

  /** indicate if this instruction use mask. */
  val useMask: Bool = Bool()

  /** fault only first element
    * TODO: extract it
    */
  def fof: Bool = mop === 0.U && lumop(4) && !isStore
}

/** request interface from [[V]] to [[LSU]] and [[MSHR]] */
class LSURequest(dataWidth: Int) extends Bundle {

  /** from instruction. */
  val instructionInformation: LSUInstructionInformation = new LSUInstructionInformation

  /** data from rs1 in scalar core, if necessary. */
  val rs1Data: UInt = UInt(dataWidth.W)

  /** data from rs2 in scalar core, if necessary. */
  val rs2Data: UInt = UInt(dataWidth.W)

  /** tag from [[V]] to record instruction.
    * TODO: parameterize it.
    */
  val instructionIndex: UInt = UInt(3.W)
}
