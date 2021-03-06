package com.github.dollschasingmen.confluent.extensions.connect.transforms

import org.apache.kafka.common.config.{ ConfigDef, ConfigException }
import org.apache.kafka.connect.connector.ConnectRecord
import org.apache.kafka.connect.data.Schema
import org.apache.kafka.connect.transforms.util.SimpleConfig
import java.util._

import com.github.dollschasingmen.confluent.extensions.connect.transforms.util.{ CopyMethods, SchemaEvolvingCache }

import org.apache.kafka.connect.transforms.util.Requirements.requireMap
import org.apache.kafka.connect.transforms.util.Requirements.requireSinkRecord
import org.apache.kafka.connect.transforms.util.Requirements.requireStruct

/**
 * "Insert the wall clock time the connect processes the record with a configurable field name.
 *
 * @tparam R
 */
class InsertWallclockTimestampField[R <: ConnectRecord[R]]
    extends org.apache.kafka.connect.transforms.Transformation[R] with CopyMethods {

  private val PURPOSE = "wall clock timestamp field insertion"

  private object ConfigName {
    val TIMESTAMP_FIELD = "timestamp.field"
  }

  private var wallClockTsField: Option[String] = None

  private val CONFIG_DEF = new ConfigDef()
    .define(
      ConfigName.TIMESTAMP_FIELD,
      ConfigDef.Type.STRING,
      null,
      ConfigDef.Importance.HIGH,
      "Field name for wall clock timestamp"
    )

  private val cache: SchemaEvolvingCache = new SchemaEvolvingCache(schema => {
    val builder = copySchema(schema)
    builder.field(wallClockTsField.get, Schema.INT64_SCHEMA)
    builder.build
  })

  override def config(): ConfigDef = CONFIG_DEF

  override def configure(props: java.util.Map[String, _]): Unit = {
    val config = new SimpleConfig(CONFIG_DEF, props)

    wallClockTsField = Option(config.getString(ConfigName.TIMESTAMP_FIELD))

    if (wallClockTsField.isEmpty) {
      throw new ConfigException(s"No value specified for ${ConfigName.TIMESTAMP_FIELD}")
    }

    cache.reset()
  }

  override def apply(record: R): R = {
    requireSinkRecord(record, PURPOSE)

    Option(record.valueSchema) match {
      case Some(_) => applyWithSchema(record)
      case None    => applySchemaLess(record)
    }
  }

  override def close(): Unit = cache.reset()

  private def applySchemaLess(record: R): R = {
    val value = requireMap(record.value, PURPOSE)
    val updatedValue = new java.util.HashMap[String, AnyRef](value)
    updatedValue.put(wallClockTsField.get, new java.lang.Long(Calendar.getInstance().getTimeInMillis))
    record.newRecord(record.topic, record.kafkaPartition, record.keySchema, record.key, null, updatedValue, record.timestamp)
  }

  private def applyWithSchema(record: R): R = {
    val value = requireStruct(record.value, PURPOSE)
    val evolvedSchema = cache.getOrElseUpdate(value.schema())

    val updatedValue = copyStructWithSchema(value, evolvedSchema)
    updatedValue.put(wallClockTsField.get, new java.lang.Long(Calendar.getInstance().getTimeInMillis))

    record.newRecord(record.topic, record.kafkaPartition, record.keySchema, record.key, evolvedSchema, updatedValue, record.timestamp)
  }
}
