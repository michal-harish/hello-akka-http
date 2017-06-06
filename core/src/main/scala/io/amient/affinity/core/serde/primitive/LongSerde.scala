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

package io.amient.affinity.core.serde.primitive

import io.amient.affinity.core.serde.Serde
import io.amient.affinity.core.util.ByteUtils

class LongSerde extends Serde[Long] {

  override def identifier: Int = 102

  override def fromBytes(bytes: Array[Byte]): Long = ByteUtils.asLongValue(bytes)

  override def toBytes(obj: Long): Array[Byte] = ByteUtils.putLongValue(obj, new Array[Byte](8),0)

  override def close(): Unit = ()

}