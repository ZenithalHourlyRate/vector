package tests.elaborate

import chisel3._
import chisel3.experimental.SerializableModuleGenerator
import v.{V, VParameter}

class TestBench extends RawModule {
  val clock = Wire(Clock())
  val reset = Wire(Bool())
  val generator = SerializableModuleGenerator(
    classOf[V],
    VParameter(
      xLen = 32,
      vLen = 1024,
      datapathWidth = 32,
      laneNumber = 8,
      physicalAddressWidth = 32,
      chainingSize = 4,
      vrfWriteQueueSize = 4
    )
  )
  val dut = withClockAndReset(clock, reset) {
    Module(
      generator.module()
    )
  }
  withClockAndReset(clock, reset) {
    val coverModule = Module(new CoverModule(dut))
    val monitor = Module(new Monitor(dut))

    monitor.clock := clock
    monitor.reset := reset
  }

  val verificationModule = Module(new VerificationModule(dut))
  dut.request <> verificationModule.req
  dut.response <> verificationModule.resp
  dut.csrInterface <> verificationModule.csrInterface
  dut.storeBufferClear <> verificationModule.storeBufferClear
  dut.memoryPorts <> verificationModule.tlPort
  clock := verificationModule.clock
  reset := verificationModule.reset
}
