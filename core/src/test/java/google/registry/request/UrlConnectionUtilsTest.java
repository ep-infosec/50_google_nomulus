// Copyright 2022 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.request;

import static com.google.common.net.HttpHeaders.CONTENT_LENGTH;
import static com.google.common.net.HttpHeaders.CONTENT_TYPE;
import static com.google.common.net.MediaType.CSV_UTF_8;
import static com.google.common.truth.Truth.assertThat;
import static google.registry.request.UrlConnectionUtils.setPayloadMultipart;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import javax.net.ssl.HttpsURLConnection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Tests for {@link UrlConnectionUtils}. */
public class UrlConnectionUtilsTest {

  private final Random random = mock(Random.class);

  @BeforeEach
  void beforeEach() {
    doAnswer(
            info -> {
              Arrays.fill((byte[]) info.getArguments()[0], (byte) 0);
              return null;
            })
        .when(random)
        .nextBytes(any(byte[].class));
  }

  @Test
  void testSetPayloadMultipart() throws Exception {
    HttpsURLConnection connection = mock(HttpsURLConnection.class);
    ByteArrayOutputStream connectionOutputStream = new ByteArrayOutputStream();
    when(connection.getOutputStream()).thenReturn(connectionOutputStream);
    setPayloadMultipart(
        connection,
        "lol",
        "cat",
        CSV_UTF_8,
        "The nice people at the store say hello. ヘ(◕。◕ヘ)",
        random);
    ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
    verify(connection, times(2)).setRequestProperty(keyCaptor.capture(), valueCaptor.capture());
    List<String> addedKeys = keyCaptor.getAllValues();
    assertThat(addedKeys).containsExactly(CONTENT_TYPE, CONTENT_LENGTH);
    List<String> addedValues = valueCaptor.getAllValues();
    assertThat(addedValues)
        .containsExactly(
            "multipart/form-data;"
                + " boundary=\"------------------------------AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\"",
            "294");
    String payload =
        "--------------------------------AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\r\n"
            + "Content-Disposition: form-data; name=\"lol\"; filename=\"cat\"\r\n"
            + "Content-Type: text/csv; charset=utf-8\r\n"
            + "\r\n"
            + "The nice people at the store say hello. ヘ(◕。◕ヘ)\r\n"
            + "--------------------------------AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA--\r\n";
    verify(connection).setDoOutput(true);
    verify(connection).getOutputStream();
    assertThat(connectionOutputStream.toByteArray()).isEqualTo(payload.getBytes(UTF_8));
    verifyNoMoreInteractions(connection);
  }

  @Test
  void testSetPayloadMultipart_boundaryInPayload() {
    HttpsURLConnection connection = mock(HttpsURLConnection.class);
    String payload = "I screamed------------------------------AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAHHH";
    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> setPayloadMultipart(connection, "lol", "cat", CSV_UTF_8, payload, random));
    assertThat(thrown)
        .hasMessageThat()
        .contains(
            "Multipart data contains autogenerated boundary: "
                + "------------------------------AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
  }
}
