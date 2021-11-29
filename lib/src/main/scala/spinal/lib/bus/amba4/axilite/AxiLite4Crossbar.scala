package spinal.lib.bus.amba4.axilite

import spinal.core._
import spinal.lib._
import spinal.lib.bus.misc.SizeMapping

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.collection.Seq

case class AxiLite4CrossbarSlaveConnection(master : AxiLite4Bus)
case class AxiLite4CrossbarSlaveConfig(mapping : SizeMapping){
  val connections = ArrayBuffer[AxiLite4CrossbarSlaveConnection]()
}

case class AxiLite4CrossbarFactory() {
  val slavesConfigs = mutable.LinkedHashMap[AxiLite4Bus,AxiLite4CrossbarSlaveConfig]()
  val axiLite4SlaveToReadWriteOnly = mutable.HashMap[AxiLite4,Seq[AxiLite4Bus]]()
  val sharedBridger = mutable.HashMap[AxiLite4Shared,(AxiLite4Shared,AxiLite4Shared) => Unit]()
  val readOnlyBridger = mutable.HashMap[AxiLite4ReadOnly,(AxiLite4ReadOnly,AxiLite4ReadOnly) => Unit]()
  val writeOnlyBridger = mutable.HashMap[AxiLite4WriteOnly,(AxiLite4WriteOnly,AxiLite4WriteOnly) => Unit]()
  val masters = ArrayBuffer[AxiLite4Bus]()
  var lowLatency = false

  def decoderToArbiterLink(bus : AxiLite4ReadOnly) = if(!lowLatency) bus.arValidPipe() else bus
  def decoderToArbiterLink(bus : AxiLite4WriteOnly) = if(!lowLatency) bus.awValidPipe() else bus
  def decoderToArbiterLink(bus : AxiLite4Shared) = if(!lowLatency) bus.arwValidPipe() else bus

  def addSlave(axi: AxiLite4Bus,mapping: SizeMapping) : this.type = {
    axi match {
      case axi: AxiLite4 => {
        val readOnly = AxiLite4ReadOnly(axi.config).setCompositeName(axi, "readOnly", true)
        val writeOnly = AxiLite4WriteOnly(axi.config).setCompositeName(axi, "writeOnly", true)
        readOnly >> axi
        writeOnly >> axi
        axiLite4SlaveToReadWriteOnly(axi) = readOnly :: writeOnly :: Nil
        addSlave(readOnly,mapping)
        addSlave(writeOnly,mapping)
      }
      case _ => {
        slavesConfigs(axi) = AxiLite4CrossbarSlaveConfig(mapping)
      }
    }
    this
  }

  def addSlaves(orders : (AxiLite4Bus,SizeMapping)*) : this.type = {
    orders.foreach(order => addSlave(order._1,order._2))
    this
  }

  def addConnection(axi: AxiLite4Bus,slaves: Seq[AxiLite4Bus]) : this.type = {
    val translatedSlaves = slaves.map(_ match{
      case that : AxiLite4 => axiLite4SlaveToReadWriteOnly(that)
      case that : AxiLite4Bus => that :: Nil
    }).flatten
    axi match {
      case axi : AxiLite4 => {
        addConnection(axi.toReadOnly().setCompositeName(axi, "readOnly", true),translatedSlaves.filter(!_.isInstanceOf[AxiLite4WriteOnly]))
        addConnection(axi.toWriteOnly().setCompositeName(axi, "writeOnly", true),translatedSlaves.filter(!_.isInstanceOf[AxiLite4ReadOnly]))
      }
      case axi : AxiLite4WriteOnly => {
        translatedSlaves.filter(!_.isInstanceOf[AxiLite4ReadOnly]).foreach(slavesConfigs(_).connections += AxiLite4CrossbarSlaveConnection(axi))
        masters += axi
      }
      case axi : AxiLite4ReadOnly => {
        translatedSlaves.filter(!_.isInstanceOf[AxiLite4WriteOnly]).foreach(slavesConfigs(_).connections += AxiLite4CrossbarSlaveConnection(axi))
        masters += axi
      }
      case axi : AxiLite4Shared => {
        translatedSlaves.foreach(slavesConfigs(_).connections += AxiLite4CrossbarSlaveConnection(axi))
        masters += axi
      }
    }
    this
  }

  def addConnection(order: (AxiLite4Bus,Seq[AxiLite4Bus])) : this.type = addConnection(order._1,order._2)

  def addConnections(orders : (AxiLite4Bus,Seq[AxiLite4Bus])*) : this.type = {
    orders.foreach(addConnection(_))
    this
  }

  def addPipelining(axi : AxiLite4Shared)(bridger : (AxiLite4Shared,AxiLite4Shared) => Unit): this.type ={
    this.sharedBridger(axi) = bridger
    this
  }
  def addPipelining(axi : AxiLite4ReadOnly)(bridger : (AxiLite4ReadOnly,AxiLite4ReadOnly) => Unit): this.type ={
    this.readOnlyBridger(axi) = bridger
    this
  }
  def addPipelining(axi : AxiLite4WriteOnly)(bridger : (AxiLite4WriteOnly,AxiLite4WriteOnly) => Unit): this.type ={
    this.writeOnlyBridger(axi) = bridger
    this
  }

  def addPipelining(axi : AxiLite4)(ro : (AxiLite4ReadOnly,AxiLite4ReadOnly) => Unit)(wo : (AxiLite4WriteOnly,AxiLite4WriteOnly) => Unit): this.type ={
    val b = axiLite4SlaveToReadWriteOnly(axi)
    val rAxi = b(0).asInstanceOf[AxiLite4ReadOnly]
    val wAxi = b(1).asInstanceOf[AxiLite4WriteOnly]
    addPipelining(rAxi)(ro)
    addPipelining(wAxi)(wo)
    this
  }


  def build(): Unit ={
    val masterToDecodedSlave = mutable.HashMap[AxiLite4Bus,Map[AxiLite4Bus,AxiLite4Bus]]()

    def applyName(bus : Bundle,name : String, onThat : Nameable) : Unit = {
      if(bus.component == Component.current)
        onThat.setCompositeName(bus,name)
      else if(bus.isNamed)
        onThat.setCompositeName(bus.component,bus.getName() + "_" + name)
    }

    val decoders = for(master <- masters) yield master match {
      case master : AxiLite4ReadOnly => new Area{
        val slaves = slavesConfigs.filter{
          case (slave,config) => config.connections.exists(connection => connection.master == master)
        }.toSeq

        val decoder = AxiLite4ReadOnlyDecoder(
          axiConfig = master.config,
          decodings = slaves.map(_._2.mapping)
        )
        applyName(master,"decoder",decoder)
        masterToDecodedSlave(master) = (slaves.map(_._1),decoder.io.outputs.map(decoderToArbiterLink)).zipped.toMap
        readOnlyBridger.getOrElse[(AxiLite4ReadOnly,AxiLite4ReadOnly) => Unit](master,_ >> _).apply(master,decoder.io.input)
        readOnlyBridger.remove(master)
      }
      case master : AxiLite4WriteOnly => new Area{
        val slaves = slavesConfigs.filter{
          case (slave,config) => config.connections.exists(connection => connection.master == master)
        }.toSeq
        val decoder = AxiLite4WriteOnlyDecoder(
          axiConfig = master.config,
          decodings = slaves.map(_._2.mapping)
        )
        applyName(master,"decoder",decoder)

        masterToDecodedSlave(master) = (slaves.map(_._1),decoder.io.outputs.map(decoderToArbiterLink)).zipped.toMap
        writeOnlyBridger.getOrElse[(AxiLite4WriteOnly,AxiLite4WriteOnly) => Unit](master,_ >> _).apply(master,decoder.io.input)
        writeOnlyBridger.remove(master)
      }
      case master : AxiLite4Shared => new Area{
        val slaves = slavesConfigs.filter{
          case (slave,config) => config.connections.exists(connection => connection.master == master)
        }.toSeq
        val readOnlySlaves = slaves.filter(_._1.isInstanceOf[AxiLite4ReadOnly])
        val writeOnlySlaves = slaves.filter(_._1.isInstanceOf[AxiLite4WriteOnly])
        val sharedSlaves = slaves.filter(_._1.isInstanceOf[AxiLite4Shared])
        val decoder = AxiLite4SharedDecoder(
          axiConfig = master.config,
          readDecodings = readOnlySlaves.map(_._2.mapping),
          writeDecodings = writeOnlySlaves.map(_._2.mapping),
          sharedDecodings = sharedSlaves.map(_._2.mapping)
        )
        applyName(master,"decoder",decoder)

        masterToDecodedSlave(master) = (
          readOnlySlaves.map(_._1) ++ writeOnlySlaves.map(_._1) ++ sharedSlaves.map(_._1)
            -> List(decoder.io.readOutputs.map(decoderToArbiterLink).toSeq , decoder.io.writeOutputs.map(decoderToArbiterLink).toSeq , decoder.io.sharedOutputs.map(decoderToArbiterLink).toSeq).flatten
        ).zipped.toMap

        sharedBridger.getOrElse[(AxiLite4Shared,AxiLite4Shared) => Unit](master,_ >> _).apply(master,decoder.io.input)
        sharedBridger.remove(master)
      }
    }

    val arbiters = for((slave,config) <- slavesConfigs.toSeq.sortBy(_._1.asInstanceOf[Bundle].getInstanceCounter)) yield slave match {
      case slave : AxiLite4ReadOnly => new Area{
        val readConnections = config.connections
        readConnections.size match {
          case 0 => PendingError(s"$slave has no master}")
          case 1 if readConnections.head.master.isInstanceOf[AxiLite4ReadOnly] => readConnections.head.master match {
            case m : AxiLite4ReadOnly => slave << masterToDecodedSlave(m)(slave).asInstanceOf[AxiLite4ReadOnly]
//            case m : AxiLite4Shared => slave << m.toAxiLite4ReadOnly()
          }
          case _ => new Area {
            val arbiter = AxiLite4ReadOnlyArbiter(
              config = slave.config,
              inputsCount = readConnections.length
            )
            applyName(slave,"arbiter",arbiter)
            for ((input, master) <- (arbiter.io.inputs, readConnections).zipped) {
              if(!masterToDecodedSlave(master.master)(slave).isInstanceOf[AxiLite4ReadOnly])
                println("???")
              input << masterToDecodedSlave(master.master)(slave).asInstanceOf[AxiLite4ReadOnly]
            }
            readOnlyBridger.getOrElse[(AxiLite4ReadOnly,AxiLite4ReadOnly) => Unit](slave,_ >> _).apply(arbiter.io.output,slave)
            readOnlyBridger.remove(slave)
          }
        }
      }
      case slave : AxiLite4WriteOnly => {
        val writeConnections = config.connections
        config.connections.size match {
          case 0 => PendingError(s"$slave has no master}")
          case 1 if writeConnections.head.master.isInstanceOf[AxiLite4WriteOnly] => writeConnections.head.master match {
            case m : AxiLite4WriteOnly => slave << masterToDecodedSlave(m)(slave).asInstanceOf[AxiLite4WriteOnly]
//            case m : AxiLite4Shared => slave << m.toAxiLite4WriteOnly()
          }
          case _ => new Area {
            val arbiter = AxiLite4WriteOnlyArbiter(
              config = slave.config,
              inputsCount = writeConnections.length,
              routeBufferSize = 4
            )
            applyName(slave,"arbiter",arbiter)
            for ((input, master) <- (arbiter.io.inputs, writeConnections).zipped) {
              input << masterToDecodedSlave(master.master)(slave).asInstanceOf[AxiLite4WriteOnly]
            }
            writeOnlyBridger.getOrElse[(AxiLite4WriteOnly,AxiLite4WriteOnly) => Unit](slave,_ >> _).apply(arbiter.io.output,slave)
            writeOnlyBridger.remove(slave)
          }
        }
      }
      case slave : AxiLite4Shared => {
        val connections = config.connections
        val readConnections = connections.filter(_.master.isInstanceOf[AxiLite4ReadOnly])
        val writeConnections = connections.filter(_.master.isInstanceOf[AxiLite4WriteOnly])
        val sharedConnections = connections.filter(_.master.isInstanceOf[AxiLite4Shared])
//        if(readConnections.size + sharedConnections.size == 0){
//          PendingError(s"$slave has no master able to read it}")
//          return
//        }

//        if(writeConnections.size + sharedConnections.size == 0){
//          PendingError(s"$slave has no master able to write it}")
//          return
//        }

        if(readConnections.size == 0 && writeConnections.size == 0 && sharedConnections.size == 0){
          slave << sharedConnections.head.master.asInstanceOf[AxiLite4Shared]
        }else{
          new Area {
            val arbiter = AxiLite4SharedArbiter(
              config = slave.config,
              readInputsCount = readConnections.size,
              writeInputsCount = writeConnections.size,
              sharedInputsCount = sharedConnections.size,
              routeBufferSize = 4
            )
            applyName(slave,"arbiter",arbiter)

            for ((input, master) <- (arbiter.io.readInputs, readConnections).zipped) {
              input << masterToDecodedSlave(master.master)(slave).asInstanceOf[AxiLite4ReadOnly]
            }
            for ((input, master) <- (arbiter.io.writeInputs, writeConnections).zipped) {
              input << masterToDecodedSlave(master.master)(slave).asInstanceOf[AxiLite4WriteOnly]
            }
            for ((input, master) <- (arbiter.io.sharedInputs, sharedConnections).zipped) {
              input << masterToDecodedSlave(master.master)(slave).asInstanceOf[AxiLite4Shared]
            }
            sharedBridger.getOrElse[(AxiLite4Shared,AxiLite4Shared) => Unit](slave,_ >> _).apply(arbiter.io.output,slave)
            sharedBridger.remove(slave)
          }
        }
      }
    }
  }
}
