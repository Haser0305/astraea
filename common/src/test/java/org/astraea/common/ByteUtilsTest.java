/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.astraea.common;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.astraea.common.metrics.BeanObject;
import org.astraea.common.metrics.ClusterBean;
import org.astraea.common.metrics.broker.HasGauge;
import org.astraea.common.metrics.broker.LogMetrics;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class ByteUtilsTest {
  @Test
  void testShort2Bytes() {
    Assertions.assertEquals(379, ByteUtils.toShort(ByteUtils.toBytes((short) 379)));
    Assertions.assertThrows(IllegalArgumentException.class, () -> ByteUtils.toShort(new byte[0]));
  }

  @Test
  void testInt2Bytes() {
    Assertions.assertArrayEquals(new byte[] {0, 0, 4, -46}, ByteUtils.toBytes(1234));
    Assertions.assertEquals(379, ByteUtils.toInteger(ByteUtils.toBytes(379)));
    Assertions.assertThrows(IllegalArgumentException.class, () -> ByteUtils.toInteger(new byte[0]));
  }

  @Test
  void testString2Bytes() {
    var string = Utils.randomString();
    Assertions.assertEquals(string, new String(ByteUtils.toBytes(string), StandardCharsets.UTF_8));
    Assertions.assertEquals("xxx", ByteUtils.toString(ByteUtils.toBytes("xxx")));
  }

  @Test
  void testLong2Bytes() {
    Assertions.assertArrayEquals(new byte[] {0, 0, 0, 0, 0, 0, 1, 123}, ByteUtils.toBytes(379L));
    Assertions.assertEquals(379L, ByteUtils.toLong(ByteUtils.toBytes(379L)));
    Assertions.assertThrows(IllegalArgumentException.class, () -> ByteUtils.toLong(new byte[0]));
  }

  @Test
  void testFloat2Bytes() {
    Assertions.assertArrayEquals(new byte[] {63, -64, 0, 0}, ByteUtils.toBytes(1.5f));
    Assertions.assertEquals(379F, ByteUtils.toFloat(ByteUtils.toBytes(379F)));
    Assertions.assertThrows(IllegalArgumentException.class, () -> ByteUtils.toFloat(new byte[0]));
  }

  @Test
  void testDouble2Bytes() {
    Assertions.assertArrayEquals(
        new byte[] {64, 94, -58, 102, 102, 102, 102, 102}, ByteUtils.toBytes(123.1D));
    Assertions.assertEquals(379D, ByteUtils.toDouble(ByteUtils.toBytes(379D)));
    Assertions.assertThrows(IllegalArgumentException.class, () -> ByteUtils.toDouble(new byte[0]));
  }

  @Test
  void testBoolean2Bytes() {
    Assertions.assertArrayEquals(new byte[] {1}, ByteUtils.toBytes(true));
    Assertions.assertArrayEquals(new byte[] {0}, ByteUtils.toBytes(false));
  }

  @Test
  void testReadAndToBytesBeanObjects() {
    BeanObject testBeanObject =
        new BeanObject(
            "kafka.log",
            Map.of(
                "name",
                LogMetrics.Log.SIZE.metricName(),
                "type",
                "Log",
                "topic",
                "testBeans",
                "partition",
                "0"),
            Map.of("Value", 100));
    var clusterBean = ClusterBean.of(Map.of(1, List.of(HasGauge.of(testBeanObject))));

    var bytes = ByteUtils.toBytes(clusterBean.all());
    var deserializedClusterBean = ByteUtils.readClusterBean(bytes);

    Assertions.assertEquals(1, deserializedClusterBean.size());
    Assertions.assertEquals(
        testBeanObject.domainName(), deserializedClusterBean.get(1).get(0).domainName());
    Assertions.assertEquals(
        testBeanObject.createdTimestamp(),
        deserializedClusterBean.get(1).get(0).createdTimestamp());
    Assertions.assertEquals(
        testBeanObject.properties(), deserializedClusterBean.get(1).get(0).properties());
    Assertions.assertEquals(
        testBeanObject.attributes(), deserializedClusterBean.get(1).get(0).attributes());
  }
}
