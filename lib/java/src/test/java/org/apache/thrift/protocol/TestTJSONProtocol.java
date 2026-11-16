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

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.apache.thrift.TConfiguration;
import org.apache.thrift.TException;
import org.apache.thrift.transport.TIOStreamTransport;
import org.apache.thrift.transport.TMemoryBuffer;
import org.apache.thrift.transport.TTransportException;
import org.junit.jupiter.api.Test;

public class TestTJSONProtocol extends ProtocolTestBase {
  @Override
  protected TProtocolFactory getFactory() {
    return new TJSONProtocol.Factory();
  }

  @Override
  protected boolean canBeUsedNaked() {
    return false;
  }

  @Test
  public void testEscapedUnicode() throws TException {
    String jsonString = "\"hello unicode \\u0e01\\ud834\\udd1e world\"";
    String expectedString = "hello unicode \u0e01\ud834\udd1e world";

    TMemoryBuffer buffer = new TMemoryBuffer(1000);
    TJSONProtocol protocol = new TJSONProtocol(buffer);
    buffer.write(jsonString.getBytes(StandardCharsets.UTF_8));

    assertEquals(expectedString, protocol.readString());
  }

  @Test
  public void testExactlySizedBuffer() throws TException {
    // Regression test for https://issues.apache.org/jira/browse/THRIFT-5383.
    // Ensures that a JSON string can be read after writing to a buffer just
    // large enough to contain it.
    String inputString = "abcdefg";
    TMemoryBuffer buffer = new TMemoryBuffer(inputString.length() + 2);

    TJSONProtocol protocol = new TJSONProtocol(buffer);
    protocol.writeString(inputString);
    String outputString = protocol.readString();

    assertEquals(inputString, outputString);
  }

  private static String repeat(char c, int n) {
    char[] chars = new char[n];
    Arrays.fill(chars, c);
    return new String(chars);
  }

  /**
   * Builds a TJSONProtocol reading from a small-maxMessageSize TIOStreamTransport. TIOStreamTransport
   * does no per-read message-size accounting (unlike TMemoryInputTransport, whose own decrement
   * already bounds a read), so it both isolates TJSONProtocol's own bound and is the real network
   * read path on which a delimited JSON string is otherwise unbounded.
   */
  private TJSONProtocol jsonProtoReading(String json, int maxSize) throws Exception {
    TConfiguration config = TConfiguration.custom().setMaxMessageSize(maxSize).build();
    TIOStreamTransport transport =
        new TIOStreamTransport(
            config, new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    return new TJSONProtocol(transport);
  }

  @Test
  public void testJSONProtocol_stringWithinMaxMessageSize() throws Exception {
    TJSONProtocol proto = jsonProtoReading("\"" + repeat('A', 512) + "\"", 4096);
    assertDoesNotThrow(proto::readString, "a JSON string within maxMessageSize must be readable");
  }

  @Test
  public void testJSONProtocol_stringExceedsMaxMessageSize() throws Exception {
    // A quote-delimited JSON string carries no declared length to reject up front,
    // so this exercises the running byte-count bound in readJSONString().
    TJSONProtocol proto = jsonProtoReading("\"" + repeat('A', 4096) + "\"", 1024);
    assertThrows(
        TTransportException.class,
        proto::readString,
        "an oversized JSON string must be bounded by maxMessageSize");
  }

  @Test
  public void testJSONProtocol_numberExceedsMaxMessageSize() throws Exception {
    // Terminate the numeric run with a non-numeric byte so the unpatched path stops
    // cleanly at the terminator rather than on EOF, isolating the size bound.
    TJSONProtocol proto = jsonProtoReading(repeat('1', 4096) + " ", 1024);
    assertThrows(
        TTransportException.class,
        proto::readI64,
        "an oversized JSON numeric literal must be bounded by maxMessageSize");
  }
}
