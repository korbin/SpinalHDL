package spinal.lib.bus.amba4.axilite

import spinal.core._
import spinal.lib.slave

/**
 * Created by spinalvm on 13.06.17.
 */
case class AxiLite4WriteOnlyErrorSlave(axiConfig: AxiLite4Config) extends Component{
  val io = new Bundle{
    val axilite = slave(AxiLite4WriteOnly(axiConfig))
  }
  val consumeData = RegInit(False)
  val sendRsp     = RegInit(False)

  io.axilite.writeCmd.ready := !(consumeData || sendRsp)
  when(io.axilite.writeCmd.fire){
    consumeData := True
  }

  io.axilite.writeData.ready := consumeData
  when(io.axilite.writeData.fire){
    consumeData := False
    sendRsp := True
  }

  io.axilite.writeRsp.valid := sendRsp
  io.axilite.writeRsp.setDECERR
  when(io.axilite.writeRsp.fire){
    sendRsp := False
  }
}


case class AxiLite4ReadOnlyErrorSlave(axiConfig: AxiLite4Config) extends Component{
  val io = new Bundle{
    val axilite = slave(AxiLite4ReadOnly(axiConfig))
  }

  val sendRsp       = RegInit(False)
  val remaining     = Reg(UInt(8 bits))
  val remainingZero = remaining === 0

  io.axilite.readCmd.ready := !sendRsp
  when(io.axilite.readCmd.fire){
    sendRsp := True
    remaining := U(0)
  }

  io.axilite.readRsp.valid := sendRsp
  io.axilite.readRsp.setDECERR

  when(sendRsp) {
    when(io.axilite.readRsp.ready) {
      remaining := remaining - 1
      when(remainingZero) {
        sendRsp := False
      }
    }
  }
}

case class AxiLite4SharedErrorSlave(axiConfig: AxiLite4Config) extends Component{
  val io = new Bundle{
    val axilite = slave(AxiLite4Shared(axiConfig))
  }

  val consumeData   = RegInit(False)
  val sendReadRsp   = RegInit(False)
  val sendWriteRsp  = RegInit(False)
  val remaining     = Reg(UInt(8 bits))
  val remainingZero = remaining === 0

  io.axilite.sharedCmd.ready := !(consumeData || sendWriteRsp || sendReadRsp )
  when(io.axilite.sharedCmd.fire){
    consumeData :=  io.axilite.sharedCmd.write
    sendReadRsp := !io.axilite.sharedCmd.write
    remaining := U(0)
  }

  //Write data
  io.axilite.writeData.ready := consumeData
  when(io.axilite.writeData.fire){
    consumeData := False
    sendWriteRsp := True
  }

  //Write rsp
  io.axilite.writeRsp.valid := sendWriteRsp
  io.axilite.writeRsp.setDECERR
  when(io.axilite.writeRsp.fire){
    sendWriteRsp := False
  }

  //Read rsp
  io.axilite.readRsp.valid := sendReadRsp
  io.axilite.readRsp.setDECERR
  when(sendReadRsp) {
    when(io.axilite.readRsp.ready) {
      remaining := remaining - 1
      when(remainingZero) {
        sendReadRsp := False
      }
    }
  }
}

