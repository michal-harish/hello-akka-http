/*
 * Copyright 2016 Michal Harish, michal.harish@gmail.com
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.amient.affinity.core.http

import io.amient.affinity.avro.record.{AvroRecord, Fixed}
import org.scalatest.{FlatSpec, Matchers}

case class TestRecord(a: String, b: Int) extends AvroRecord

case class LongCompoundKey(@Fixed version: Long, @Fixed(2) country: String, @Fixed(4) city: String, value: Double) extends AvroRecord

class EncoderSpec extends FlatSpec with Matchers {

  behavior of "Encoder"

  it should "decorate AvroRecord with type and data fields" in {
    Encoder.json(TestRecord("hello", 123)) should be ("{\"type\":\"io.amient.affinity.core.http.TestRecord\",\"data\":{\"a\":\"hello\",\"b\":123}}")
  }

  it should "automatically format Maps and unwrap neted records recursively" in {
    Encoder.json(Map(
      "a" -> "hello",
      "b" -> 123,
      "c" -> TestRecord("nested", 777)
    )) should be ("{\"a\":\"hello\",\"b\":123,\"c\":{\"type\":\"io.amient.affinity.core.http.TestRecord\",\"data\":{\"a\":\"nested\",\"b\":777}}}")
  }

  it should "automatically format primitives and options of primitives" in {
    Encoder.json(null) should be ("null")
    Encoder.json(100) should be ("100")
    Encoder.json(1000L) should be ("1000")
    Encoder.json("text") should be ("\"text\"")
    Encoder.json(None) should be ("null")
    Encoder.json(Some(100)) should be ("100")
  }

  it should "unwarp options of records recursively" in {
    Encoder.json(Some(TestRecord("hello", 777))) should be ("{\"type\":\"io.amient.affinity.core.http.TestRecord\",\"data\":{\"a\":\"hello\",\"b\":777}}")
  }

  it should "correctly translate collections of avro records with fixed fields" in {
    val list = List(LongCompoundKey(100L, "UK", "C001", 9.9))
    list.toString should be ("List(LongCompoundKey(100,UK,C001,9.9))")
    val sameJson = "[{\"type\":\"io.amient.affinity.core.http.LongCompoundKey\",\"data\":{\"version\":\"AAAAAAAAAGQ=\",\"country\":\"VUs=\",\"city\":\"QzAwMQ==\",\"value\":9.9}}]"
    Encoder.json(list) should be(sameJson)
  }

}
