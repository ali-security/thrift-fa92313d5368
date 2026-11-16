/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.thrift.protocol;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import org.apache.thrift.TDeserializer;
import org.apache.thrift.TException;
import org.apache.thrift.transport.TIOStreamTransport;
import org.apache.thrift.transport.TMemoryInputTransport;
import org.junit.jupiter.api.Test;
import thrift.test.Bonk;

public class TestTCompactProtocol extends ProtocolTestBase {
  @Override
  protected TProtocolFactory getFactory() {
    return new TCompactProtocol.Factory();
  }

  @Override
  protected boolean canBeUsedNaked() {
    return true;
  }

  @Test
  public void testOOMDenialOfService() throws Exception {
    // Struct header, Integer.MAX_VALUE length, and only one real
    // byte of data
    byte[] bytes = {24, -1, -1, -1, -17, 49};
    TDeserializer deser = new TDeserializer(new TCompactProtocol.Factory(1000));
    Bonk bonk = new Bonk();
    try {
      deser.deserialize(bonk, bytes);
    } catch (TException e) {
      // Ignore as we are only checking for OOM in the failure case
    }
  }

  @Test
  public void testReadVarint64FastPathRejectsOverlong() throws Exception {
    byte[] buf = new byte[11];
    Arrays.fill(buf, (byte) 0x80); // 11 continuation bytes, no terminator
    TCompactProtocol proto = new TCompactProtocol(new TMemoryInputTransport(buf));
    TProtocolException ex = assertThrows(TProtocolException.class, proto::readI64);
    assertEquals(TProtocolException.INVALID_DATA, ex.getType());
  }

  @Test
  public void testReadVarint64FastPathAcceptsValid10Byte() throws Exception {
    byte[] buf = new byte[10];
    Arrays.fill(buf, (byte) 0x80);
    buf[9] = 0x01; // terminating byte
    TCompactProtocol proto = new TCompactProtocol(new TMemoryInputTransport(buf));
    assertDoesNotThrow(proto::readI64);
  }

  @Test
  public void testReadVarint32FastPathRejectsOverlong() throws Exception {
    byte[] buf = new byte[6];
    Arrays.fill(buf, (byte) 0x80); // 6 continuation bytes, no terminator
    TCompactProtocol proto = new TCompactProtocol(new TMemoryInputTransport(buf));
    TProtocolException ex = assertThrows(TProtocolException.class, proto::readI32);
    assertEquals(TProtocolException.INVALID_DATA, ex.getType());
  }

  @Test
  public void testReadVarint32FastPathAcceptsValid5Byte() throws Exception {
    byte[] buf = new byte[5];
    Arrays.fill(buf, (byte) 0x80);
    buf[4] = 0x01; // terminating byte
    TCompactProtocol proto = new TCompactProtocol(new TMemoryInputTransport(buf));
    assertDoesNotThrow(proto::readI32);
  }

  // The unbuffered (slow) path of readVarint32/readVarint64 is taken whenever the
  // transport cannot expose its buffer, e.g. a plain stream transport. It must be
  // bounded too, otherwise a malicious peer can stream continuation bytes forever.
  @Test
  public void testReadVarint64SlowPathRejectsOverlong() throws Exception {
    byte[] buf = new byte[11];
    Arrays.fill(buf, (byte) 0x80);
    TCompactProtocol proto =
        new TCompactProtocol(new TIOStreamTransport(new ByteArrayInputStream(buf)));
    assertEquals(-1, proto.getTransport().getBytesRemainingInBuffer());
    TProtocolException ex = assertThrows(TProtocolException.class, proto::readI64);
    assertEquals(TProtocolException.INVALID_DATA, ex.getType());
  }

  @Test
  public void testReadVarint64SlowPathAcceptsValid10Byte() throws Exception {
    byte[] buf = new byte[10];
    Arrays.fill(buf, (byte) 0x80);
    buf[9] = 0x01;
    TCompactProtocol proto =
        new TCompactProtocol(new TIOStreamTransport(new ByteArrayInputStream(buf)));
    assertDoesNotThrow(proto::readI64);
  }

  @Test
  public void testReadVarint32SlowPathRejectsOverlong() throws Exception {
    byte[] buf = new byte[6];
    Arrays.fill(buf, (byte) 0x80);
    TCompactProtocol proto =
        new TCompactProtocol(new TIOStreamTransport(new ByteArrayInputStream(buf)));
    assertEquals(-1, proto.getTransport().getBytesRemainingInBuffer());
    TProtocolException ex = assertThrows(TProtocolException.class, proto::readI32);
    assertEquals(TProtocolException.INVALID_DATA, ex.getType());
  }

  @Test
  public void testReadVarint32SlowPathAcceptsValid5Byte() throws Exception {
    byte[] buf = new byte[5];
    Arrays.fill(buf, (byte) 0x80);
    buf[4] = 0x01;
    TCompactProtocol proto =
        new TCompactProtocol(new TIOStreamTransport(new ByteArrayInputStream(buf)));
    assertDoesNotThrow(proto::readI32);
  }

  /**
   * A stream that yields continuation bytes (0x80) forever, i.e. a varint that never terminates.
   * Reads are counted so the test can also prove the decoder gave up almost immediately.
   */
  private static class EndlessContinuationStream extends InputStream {
    int reads = 0;

    @Override
    public int read() throws IOException {
      reads++;
      if (reads > 10000) {
        throw new IOException("readVarint did not stop: the decoder is still looping");
      }
      return 0x80;
    }
  }

  @Test
  public void testReadVarint32DoesNotLoopForeverOnEndlessStream() throws Exception {
    EndlessContinuationStream stream = new EndlessContinuationStream();
    TCompactProtocol proto = new TCompactProtocol(new TIOStreamTransport(stream));
    TProtocolException ex = assertThrows(TProtocolException.class, proto::readI32);
    assertEquals(TProtocolException.INVALID_DATA, ex.getType());
    assertTrue(stream.reads <= 5, "readVarint32 read " + stream.reads + " bytes");
  }

  @Test
  public void testReadVarint64DoesNotLoopForeverOnEndlessStream() throws Exception {
    EndlessContinuationStream stream = new EndlessContinuationStream();
    TCompactProtocol proto = new TCompactProtocol(new TIOStreamTransport(stream));
    TProtocolException ex = assertThrows(TProtocolException.class, proto::readI64);
    assertEquals(TProtocolException.INVALID_DATA, ex.getType());
    assertTrue(stream.reads <= 10, "readVarint64 read " + stream.reads + " bytes");
  }

  public static void main(String args[]) throws Exception {
    new TestTCompactProtocol().benchmark();
  }
}
