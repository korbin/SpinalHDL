package spinal.lib.bus.amba4.axilite

import spinal.core._
import spinal.lib._

import spinal.lib.bus.misc.SizeMapping

case class AxiLite4ReadOnlyDecoder(axiConfig: AxiLite4Config,decodings : Seq[SizeMapping],pendingMax : Int = 7) extends Component{

  assert(!SizeMapping.verifyOverlapping(decodings), "AXI4 address decoding overlapping")

  val io = new Bundle{
    val input = slave(AxiLite4ReadOnly(axiConfig))
    val outputs = Vec(master(AxiLite4ReadOnly(axiConfig)),decodings.size)
  }

  val pendingCmdCounter = CounterUpDown(
    stateCount = pendingMax+1,
    incWhen = io.input.readCmd.fire,
    decWhen = io.input.readRsp.fire
  )
  val decodedCmdSels = decodings.map(_.hit(io.input.readCmd.addr) && io.input.readCmd.valid).asBits
  val decodedCmdError = decodedCmdSels === 0
  val pendingSels  = RegNextWhen(decodedCmdSels,io.input.readCmd.ready)  init(0)
  val pendingError = RegNextWhen(decodedCmdError,io.input.readCmd.ready)  init(False)
  val allowCmd    = pendingCmdCounter === 0 || (pendingCmdCounter =/= pendingMax && pendingSels === decodedCmdSels)

  //Decoding error managment
  val decodingErrorPossible = decodings.map(_.size).sum < (BigInt(1) << axiConfig.addressWidth)
  val errorSlave = if(decodingErrorPossible) AxiLite4ReadOnlyErrorSlave(axiConfig) else null

  //Wire readCmd
  io.input.readCmd.ready := ((decodedCmdSels & io.outputs.map(_.readCmd.ready).asBits).orR || (if(decodingErrorPossible) (decodedCmdError && errorSlave.io.axilite.readCmd.ready) else False))  && allowCmd
  if(decodingErrorPossible) {
    errorSlave.io.axilite.readCmd.valid := io.input.readCmd.valid && decodedCmdError && allowCmd
    errorSlave.io.axilite.readCmd.payload := io.input.readCmd.payload
  }
  for((output,sel) <- (io.outputs,decodedCmdSels.asBools).zipped){
    output.readCmd.valid := io.input.readCmd.valid && sel && allowCmd
    output.readCmd.payload := io.input.readCmd.payload
  }

  //Wire ReadRsp
  val readRspIndex = OHToUInt(pendingSels)

  io.input.readRsp.valid := io.outputs.map(_.readRsp.valid).asBits.orR
  io.input.readRsp.payload := MuxOH(pendingSels,io.outputs.map(_.readRsp.payload))
  if(decodingErrorPossible) {
    io.input.readRsp.valid setWhen(errorSlave.io.axilite.readRsp.valid)
    when(pendingError){
      io.input.readRsp.resp := errorSlave.io.axilite.readRsp.resp
    }
    errorSlave.io.axilite.readRsp.ready := io.input.readRsp.ready
  }
  io.outputs.foreach(_.readRsp.ready := io.input.readRsp.ready)
}


case class AxiLite4WriteOnlyDecoder(axiConfig: AxiLite4Config,decodings : Seq[SizeMapping],pendingMax : Int = 7) extends Component{
  assert(!SizeMapping.verifyOverlapping(decodings), "AxiLite4 address decoding overlapping")

  val io = new Bundle{
    val input = slave(AxiLite4WriteOnly(axiConfig))
    val outputs = Vec(master(AxiLite4WriteOnly(axiConfig)),decodings.size)
  }

  val cmdAllowedStart = Bool()

  val pendingCmdCounter = CounterUpDown(
    stateCount = pendingMax+1,
    incWhen = io.input.writeCmd.fire,
    decWhen = io.input.writeRsp.fire
  )

  val pendingDataCounter = CounterUpDown(
    stateCount = pendingMax+1,
    incWhen = cmdAllowedStart,
    decWhen = io.input.writeData.fire
  )

  val decodedCmdSels = decodings.map(_.hit(io.input.writeCmd.addr) && io.input.writeCmd.valid).asBits
  val decodedCmdError = decodedCmdSels === 0
  val pendingSels  = RegNextWhen(decodedCmdSels,cmdAllowedStart)  init(0)
  val pendingError = RegNextWhen(decodedCmdError,cmdAllowedStart)  init(False)
  val allowCmd    = pendingCmdCounter === 0 || (pendingCmdCounter =/= pendingMax && pendingSels === decodedCmdSels)
  val allowData   = pendingDataCounter =/= 0
  cmdAllowedStart := io.input.writeCmd.valid && allowCmd && (RegInit(True) clearWhen(cmdAllowedStart) setWhen(io.input.writeCmd.ready))

  //Decoding error managment
  val decodingErrorPossible = decodings.map(_.size).sum < (BigInt(1) << axiConfig.addressWidth)
  val errorSlave = if(decodingErrorPossible) AxiLite4WriteOnlyErrorSlave(axiConfig) else null

  //Wire writeCmd
  io.input.writeCmd.ready := ((decodedCmdSels & io.outputs.map(_.writeCmd.ready).asBits).orR || (if(decodingErrorPossible) (decodedCmdError && errorSlave.io.axilite.writeCmd.ready) else False)) && allowCmd
  if(decodingErrorPossible) {
    errorSlave.io.axilite.writeCmd.valid := io.input.writeCmd.valid && decodedCmdError && allowCmd
    errorSlave.io.axilite.writeCmd.payload := io.input.writeCmd.payload
  }
  for((output,sel) <- (io.outputs,decodedCmdSels.asBools).zipped){
    output.writeCmd.valid := io.input.writeCmd.valid && sel && allowCmd
    output.writeCmd.payload := io.input.writeCmd.payload
  }

  //Wire writeData
  io.input.writeData.ready := ((pendingSels & io.outputs.map(_.writeData.ready).asBits).orR || (if(decodingErrorPossible) (pendingError && errorSlave.io.axilite.writeData.ready) else False)) && allowData
  if(decodingErrorPossible) {
    errorSlave.io.axilite.writeData.valid := io.input.writeData.valid && pendingError && allowData
    errorSlave.io.axilite.writeData.payload := io.input.writeData.payload
  }
  for((output,sel) <- (io.outputs,pendingSels.asBools).zipped){
    output.writeData.valid   := io.input.writeData.valid && sel && allowData
    output.writeData.payload := io.input.writeData.payload
  }

  //Wire writeRsp
  val writeRspIndex = OHToUInt(pendingSels)
  io.input.writeRsp.valid := io.outputs.map(_.writeRsp.valid).asBits.orR || (if(decodingErrorPossible) errorSlave.io.axilite.writeRsp.valid else False)
  io.input.writeRsp.payload := MuxOH(pendingSels,io.outputs.map(_.writeRsp.payload))
  if(decodingErrorPossible) {
    when(pendingError){
      io.input.writeRsp.resp := errorSlave.io.axilite.writeRsp.resp
    }
    errorSlave.io.axilite.writeRsp.ready := io.input.writeRsp.ready
  }
  io.outputs.foreach(_.writeRsp.ready := io.input.writeRsp.ready)
}





case class AxiLite4SharedDecoder(axiConfig: AxiLite4Config,
                             readDecodings : Seq[SizeMapping],
                             writeDecodings : Seq[SizeMapping],
                             sharedDecodings : Seq[SizeMapping],
                             pendingMax : Int = 7) extends Component{
  assert(!SizeMapping.verifyOverlapping(readDecodings++sharedDecodings), "AxiLite4 address decoding overlapping")
  assert(!SizeMapping.verifyOverlapping(writeDecodings++sharedDecodings), "AxiLite4 address decoding overlapping")

  val io = new Bundle{
    val input = slave(AxiLite4Shared(axiConfig))
    val readOutputs   = Vec(master(AxiLite4ReadOnly(axiConfig)),readDecodings.size)
    val writeOutputs  = Vec(master(AxiLite4WriteOnly(axiConfig)),writeDecodings.size)
    val sharedOutputs = Vec(master(AxiLite4Shared(axiConfig)),sharedDecodings.size)
  }

  val cmdAllowedStart = Bool()

  val pendingCmdCounter = CounterMultiRequest(
    log2Up(pendingMax+1),
    (io.input.sharedCmd.fire -> (_ + 1)),
    (io.input.writeRsp.fire -> (_ - 1)),
    ((io.input.readRsp.fire) -> (_ - 1))
  )

  val pendingDataCounter = CounterUpDown(
    stateCount = pendingMax+1,
    incWhen = cmdAllowedStart && io.input.sharedCmd.write,
    decWhen = io.input.writeData.fire
  )

  val decodings = readDecodings ++ writeDecodings ++ sharedDecodings
  val readRange   = 0 to readDecodings.size -1
  val writeRange  = readRange.high + 1 to readRange.high + writeDecodings.size
  val sharedRange = writeRange.high + 1 to writeRange.high + sharedDecodings.size

  val decodedCmdSels = Cat(
    sharedDecodings.map(_.hit(io.input.sharedCmd.addr)).asBits,
    writeDecodings.map(_.hit(io.input.sharedCmd.addr) &&  io.input.sharedCmd.write).asBits,
    readDecodings.map(_.hit(io.input.sharedCmd.addr)  && !io.input.sharedCmd.write).asBits
  )
  val decodedCmdError = decodedCmdSels === 0
  val pendingSels  = RegNextWhen(decodedCmdSels,cmdAllowedStart)  init(0)
  val pendingError = RegNextWhen(decodedCmdError,cmdAllowedStart)  init(False)
  val allowCmd    = pendingCmdCounter === 0 || (pendingCmdCounter =/= pendingMax && pendingSels === decodedCmdSels)
  val allowData   = pendingDataCounter =/= 0
  cmdAllowedStart := io.input.sharedCmd.valid && allowCmd && (RegInit(True) clearWhen(cmdAllowedStart) setWhen(io.input.sharedCmd.ready))

  //Decoding error managment
  val decodingErrorPossible = true
  val errorSlave = if(decodingErrorPossible) AxiLite4SharedErrorSlave(axiConfig) else null

  io.input.sharedCmd.ready := ((decodedCmdSels & (io.readOutputs.map(_.readCmd.ready) ++ io.writeOutputs.map(_.writeCmd.ready) ++ io.sharedOutputs.map(_.sharedCmd.ready)).asBits).orR || (decodedCmdError && errorSlave.io.axilite.sharedCmd.ready)) && allowCmd
  errorSlave.io.axilite.sharedCmd.valid := io.input.sharedCmd.valid && decodedCmdError && allowCmd
  errorSlave.io.axilite.sharedCmd.payload := io.input.sharedCmd.payload

  //Wire readCmd
  for((output,sel) <- (io.readOutputs,decodedCmdSels(readRange).asBools).zipped){
    output.readCmd.valid := io.input.sharedCmd.valid && sel && allowCmd
    output.readCmd.payload.assignSomeByName(io.input.sharedCmd.payload)
  }
  //Wire writeCmd
  for((output,sel) <- (io.writeOutputs,decodedCmdSels(writeRange).asBools).zipped){
    output.writeCmd.valid := io.input.sharedCmd.valid && sel && allowCmd
    output.writeCmd.payload.assignSomeByName(io.input.sharedCmd.payload)
  }
  //Wire sharedCmd
  for((output,sel) <- (io.sharedOutputs,decodedCmdSels(sharedRange).asBools).zipped){
    output.sharedCmd.valid := io.input.sharedCmd.valid && sel && allowCmd
    output.sharedCmd.payload.assignSomeByName(io.input.sharedCmd.payload)
  }

  io.input.writeData.ready := ((pendingSels(sharedRange) ## (pendingSels(writeRange)) & (io.writeOutputs.map(_.writeData.ready) ++ io.sharedOutputs.map(_.writeData.ready)).asBits).orR || (pendingError && errorSlave.io.axilite.writeData.ready)) && allowData
  //Wire writeWriteData
  errorSlave.io.axilite.writeData.valid := io.input.writeData.valid && pendingError && allowData
  errorSlave.io.axilite.writeData.payload := io.input.writeData.payload
  for((output,sel) <- (io.writeOutputs,pendingSels(writeRange).asBools).zipped){
    output.writeData.valid   := io.input.writeData.valid && sel && allowData
    output.writeData.payload := io.input.writeData.payload
  }
  //Wire sharedWriteData
  for((output,sel) <- (io.sharedOutputs,pendingSels(sharedRange).asBools).zipped){
    output.writeData.valid   := io.input.writeData.valid && sel && allowData
    output.writeData.payload := io.input.writeData.payload
  }

  //Wire writeRsp
  val writeRspIndex = OHToUInt(pendingSels(sharedRange) ## pendingSels(writeRange))
  io.input.writeRsp.valid :=   (io.writeOutputs.map(_.writeRsp.valid)   ++ io.sharedOutputs.map(_.writeRsp.valid)).asBits.orR || errorSlave.io.axilite.writeRsp.valid
  io.input.writeRsp.payload := (io.writeOutputs.map(_.writeRsp.payload) ++ io.sharedOutputs.map(_.writeRsp.payload)).apply(writeRspIndex)
  when(pendingError){
    io.input.writeRsp.resp := errorSlave.io.axilite.writeRsp.resp
  }
  errorSlave.io.axilite.writeRsp.ready := io.input.writeRsp.ready
  io.writeOutputs.foreach(_.writeRsp.ready := io.input.writeRsp.ready)
  io.sharedOutputs.foreach(_.writeRsp.ready := io.input.writeRsp.ready)

  //Wire ReadRsp
  val readRspIndex = OHToUInt(pendingSels(sharedRange) ## pendingSels(readRange))
  io.input.readRsp.valid   := (io.readOutputs.map(_.readRsp.valid)   ++ io.sharedOutputs.map(_.readRsp.valid)).asBits.orR || errorSlave.io.axilite.readRsp.valid
  io.input.readRsp.payload := (io.readOutputs.map(_.readRsp.payload) ++ io.sharedOutputs.map(_.readRsp.payload)).apply(readRspIndex)
  when(pendingError){
    io.input.readRsp.resp := errorSlave.io.axilite.readRsp.resp
  }
  errorSlave.io.axilite.readRsp.ready := io.input.readRsp.ready
  io.readOutputs.foreach(_.readRsp.ready := io.input.readRsp.ready)
  io.sharedOutputs.foreach(_.readRsp.ready := io.input.readRsp.ready)
}

