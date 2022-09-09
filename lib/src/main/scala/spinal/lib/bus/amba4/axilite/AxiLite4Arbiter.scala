package spinal.lib.bus.amba4.axilite

import spinal.core._
import spinal.lib._

import spinal.lib.bus.misc.SizeMapping

case class AxiLite4ReadOnlyArbiter(config: AxiLite4Config,inputsCount : Int) extends Component {
  val io = new Bundle{
    val inputs = Vec(slave(AxiLite4ReadOnly(config)),inputsCount)
    val output = master(AxiLite4ReadOnly(config))
  }

  val cmdArbiter = StreamArbiterFactory.roundRobin.build(AxiLite4Ar(config),inputsCount)
  (cmdArbiter.io.inputs,io.inputs.map(_.readCmd)).zipped.map(_ <> _)

  val (cmdOutputFork, rspRouteFork) = StreamFork2(cmdArbiter.io.output)
  cmdOutputFork >> io.output.readCmd

  val rspRoute = rspRouteFork.translateWith(cmdArbiter.io.chosen)
  rspRoute.ready := io.output.readRsp.fire

  // Route readResp
  val readRspIndex = rspRoute.payload
  val readRspSels = (0 until inputsCount).map(readRspIndex === _)
  for((input,sel)<- (io.inputs,readRspSels).zipped){
    input.readRsp.valid := io.output.readRsp.valid && sel
    input.readRsp.payload <> io.output.readRsp.payload
  }
  io.output.readRsp.ready := io.inputs(readRspIndex).readRsp.ready
}


//routeBufferSize Specify how many write cmd could be schedule before any write data transaction is transmitted
case class AxiLite4WriteOnlyArbiter(config: AxiLite4Config,
                                inputsCount : Int,
                                routeBufferSize : Int,
                                routeBufferLatency : Int = 0,
                                routeBufferS2mPipe : Boolean = false) extends Component {
  assert(routeBufferSize >= 1)
  val io = new Bundle{
    val inputs = Vec(slave(AxiLite4WriteOnly(config)),inputsCount)
    val output = master(AxiLite4WriteOnly(config))
  }

  // Route writeCmd
  val cmdArbiter = StreamArbiterFactory.roundRobin.build(AxiLite4Aw(config),inputsCount)
  (cmdArbiter.io.inputs,io.inputs.map(_.writeCmd)).zipped.map(_ <> _)
  val (cmdOutputFork,cmdRouteFork,rspRouteFork) = StreamFork3(cmdArbiter.io.output)
  io.output.writeCmd << cmdOutputFork

  // Route writeData
  var routeBuffer = cmdRouteFork.translateWith(cmdArbiter.io.chosen).queueLowLatency(routeBufferSize, latency = routeBufferLatency)
  if(routeBufferS2mPipe) routeBuffer = routeBuffer.s2mPipe()
  val routeDataInput = io.inputs(routeBuffer.payload).writeData
  io.output.writeData.valid := routeBuffer.valid && routeDataInput.valid
  io.output.writeData.payload  := routeDataInput.payload
  io.inputs.zipWithIndex.foreach{case(input,idx) => {
    input.writeData.ready := routeBuffer.valid && io.output.writeData.ready && routeBuffer.payload === idx
  }}
  routeBuffer.ready := io.output.writeData.fire

  val rspRoute = rspRouteFork.translateWith(cmdArbiter.io.chosen)
  rspRoute.ready := io.output.writeRsp.fire

  // Route writeResp
  val writeRspIndex = rspRoute.payload
  val writeRspSels = (0 until inputsCount).map(writeRspIndex === _)
  for((input,sel)<- (io.inputs,writeRspSels).zipped){
    input.writeRsp.valid := io.output.writeRsp.valid && sel
    input.writeRsp.payload <> io.output.writeRsp.payload
  }
  io.output.writeRsp.ready := io.inputs(writeRspIndex).writeRsp.ready
}


//routeBufferSize Specify how many write cmd could be schedule before any write data transaction is transmitted
case class AxiLite4SharedArbiter(config: AxiLite4Config,
                             readInputsCount : Int,
                             writeInputsCount : Int,
                             sharedInputsCount : Int,
                             routeBufferSize : Int,
                             routeBufferLatency : Int = 0,
                             routeBufferM2sPipe : Boolean = false,
                             routeBufferS2mPipe : Boolean = false) extends Component {
  assert(routeBufferSize >= 1)
  val inputsCount = readInputsCount + writeInputsCount + sharedInputsCount

  val io = new Bundle{
    val readInputs   = Vec(slave(AxiLite4ReadOnly(config)) ,readInputsCount)
    val writeInputs  = Vec(slave(AxiLite4WriteOnly(config)),writeInputsCount)
    val sharedInputs = Vec(slave(AxiLite4Shared(config))   ,sharedInputsCount)
    val output = master(AxiLite4Shared(config))
  }

  val readRange   = 0 to readInputsCount -1
  val writeRange  = readRange.high + 1 to readRange.high + writeInputsCount
  val sharedRange = writeRange.high + 1 to writeRange.high + sharedInputsCount


  // Route writeCmd
  val inputsCmd = io.readInputs.map(axilite => {
    val newPayload = AxiLite4Arw(config)
    newPayload.assignSomeByName(axilite.readCmd.payload)
    newPayload.write := False
    axilite.readCmd.translateWith(newPayload)
  }) ++ io.writeInputs.map(axilite => {
    val newPayload = AxiLite4Arw(config)
    newPayload.assignSomeByName(axilite.writeCmd.payload)
    newPayload.write := True
    axilite.writeCmd.translateWith(newPayload)
  }) ++ io.sharedInputs.map( axilite => {
    axilite.sharedCmd.translateWith(axilite.sharedCmd.payload)
  })

  val cmdArbiter = StreamArbiterFactory.roundRobin.build(AxiLite4Arw(config),inputsCount)
  (inputsCmd,cmdArbiter.io.inputs).zipped.map(_ >> _)
  val (cmdOutputFork,cmdRouteFork,readRspFork) = StreamFork3(cmdArbiter.io.output)
  io.output.sharedCmd << cmdOutputFork

  // Route writeData
  val writeLogic = if (writeInputsCount + sharedInputsCount != 0) new Area {
    val (writeCmdFork, writeRspRouteFork) = StreamFork2(cmdRouteFork.takeWhen(cmdRouteFork.write).translateWith(OHToUInt(cmdArbiter.io.chosenOH(sharedRange) ## cmdArbiter.io.chosenOH(writeRange))))

    @dontName val writeDataInputs = (io.writeInputs.map(_.writeData) ++ io.sharedInputs.map(_.writeData))
    var routeBuffer = writeCmdFork.queueLowLatency(routeBufferSize, latency = routeBufferLatency)
    if(routeBufferM2sPipe) routeBuffer = routeBuffer.m2sPipe()
    if(routeBufferS2mPipe) routeBuffer = routeBuffer.s2mPipe()
    val routeDataInput = writeDataInputs.apply(routeBuffer.payload)
    io.output.writeData.valid := routeBuffer.valid && routeDataInput.valid
    io.output.writeData.payload := routeDataInput.payload
    writeDataInputs.zipWithIndex.foreach { case (input, idx) => {
      input.ready := routeBuffer.valid && io.output.writeData.ready && routeBuffer.payload === idx
    }}
    routeBuffer.ready := io.output.writeData.fire

    // Route writeResp
    val writeRspIndex = writeRspRouteFork.payload
    writeRspRouteFork.ready := io.output.writeRsp.fire

    @dontName val writeRspInputs = (io.writeInputs.map(_.writeRsp) ++ io.sharedInputs.map(_.writeRsp))
    val writeRspSels = (0 until writeInputsCount + sharedInputsCount).map(writeRspIndex === _)
    for ((input, sel) <- (writeRspInputs, writeRspSels).zipped) {
      input.valid := io.output.writeRsp.valid && sel
      input.payload <> io.output.writeRsp.payload
    }
    io.output.writeRsp.ready := writeRspInputs.read(writeRspIndex).ready
  } else new Area {
    cmdRouteFork.ready := True
    io.output.w.valid := False
    io.output.w.payload.assignDontCare()
    io.output.b.ready := True
  }

  // Route readResp
  val readRspRoute = readRspFork.takeWhen(!readRspFork.write).translateWith(OHToUInt(cmdArbiter.io.chosenOH(sharedRange) ## cmdArbiter.io.chosenOH(readRange)))
  val readRspIndex = readRspRoute.payload
  readRspRoute.ready := io.output.readRsp.fire

  @dontName val readRspInputs = (io.readInputs.map(_.readRsp) ++ io.sharedInputs.map(_.readRsp))
  val readRspSels = (0 until readInputsCount + sharedInputsCount).map(readRspIndex === _)
  for((input,sel)<- (readRspInputs,readRspSels).zipped){
    input.valid := io.output.readRsp.valid && sel
    input.payload <> io.output.readRsp.payload
  }
  io.output.readRsp.ready := readRspInputs.read(readRspIndex).ready
}
