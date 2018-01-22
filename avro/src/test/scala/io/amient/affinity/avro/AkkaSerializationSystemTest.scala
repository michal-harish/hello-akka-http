package io.amient.affinity.avro

import akka.actor.ActorSystem
import akka.serialization.SerializationExtension
import com.typesafe.config.{ConfigFactory, ConfigValueFactory}
import io.amient.affinity.avro.record.{AvroRecord, AvroSerde}
import org.scalatest.FlatSpec


case class ExampleType(val id: Int) extends AvroRecord {
  override def hashCode(): Int = id.hashCode()
}

class AkkaSerializationSystemTest extends FlatSpec with EmbeddedCfRegistry {

  val config = ConfigFactory.defaultReference
    .withValue(ConfluentSchemaRegistry.Conf.Avro.ConfluentSchemaRegistryUrl.path, ConfigValueFactory.fromAnyRef(registryUrl))
    .withValue(AvroSerde.Conf.Avro.Class.path, ConfigValueFactory.fromAnyRef(classOf[ConfluentSchemaRegistry].getName))

  assert(config.getString(new ConfluentSchemaRegistry.Conf().Avro.ConfluentSchemaRegistryUrl.path) == registryUrl)

  override def numPartitions = 2

  "Confluent Schema Registry " should "be available via akka SerializationExtension" in {
    val system = ActorSystem.create("CfTest", config)
    try {
      val serialization = SerializationExtension(system)
      val serde = serialization.serializerFor(classOf[ExampleType])
      assert(serde.fromBinary(serde.toBinary(ExampleType(101))) == ExampleType(101))
    } finally {
      system.terminate()
    }
  }
}