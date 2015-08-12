/*
 * Copyright 2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ratpack.http.client

import ratpack.http.MutableHeaders

import java.util.zip.GZIPInputStream

import static ratpack.http.ResponseChunks.stringChunks
import static ratpack.http.internal.HttpHeaderConstants.CONTENT_ENCODING
import static ratpack.stream.Streams.publish

class HttpProxySpec extends HttpClientSpec {

  def "can proxy a client response"() {
    given:
    otherApp {
      get("foo") {
        response.headers.add("x-foo-header", "foo")
        render "bar"
      }
    }

    and:
    handlers {
      get { HttpClient httpClient ->
        httpClient.requestStream(otherAppUrl("foo")) {
        } then { StreamedResponse responseStream ->
          responseStream.forwardTo(response)
        }
      }
    }

    expect:
    rawResponse() == """HTTP/1.1 200 OK
x-foo-header: foo
content-type: text/plain;charset=UTF-8
transfer-encoding: chunked

3
bar
0

"""
  }

  def "can proxy a client chunked response"() {
    given:
    otherApp {
      get("foo") {
        response.headers.add("x-foo-header", "foo")
        render stringChunks(
          publish(["bar"] * 3)
        )
      }
    }

    and:
    handlers {
      get { HttpClient httpClient ->
        httpClient.requestStream(otherAppUrl("foo")) {
        } then { StreamedResponse responseStream ->
          responseStream.forwardTo(response)
        }
      }
    }

    expect:
    rawResponse() == """HTTP/1.1 200 OK
x-foo-header: foo
content-type: text/plain;charset=UTF-8
transfer-encoding: chunked

3
bar
3
bar
3
bar
0

"""
  }

  def "can mutate response headers while proxying"() {
    given:
    otherApp {
      get("foo") {
        response.headers.add("x-foo-header", "foo")
        render stringChunks(
          publish(["bar"] * 3)
        )
      }
    }

    and:
    handlers {
      get { HttpClient httpClient ->
        httpClient.requestStream(otherAppUrl("foo")) {
        } then { StreamedResponse responseStream ->
          responseStream.forwardTo(response) {MutableHeaders headers ->
            headers.remove("x-foo-header")
            headers.add("x-bar-header", "bar")
          }
        }
      }
    }

    expect:
    rawResponse() == """HTTP/1.1 200 OK
content-type: text/plain;charset=UTF-8
x-bar-header: bar
transfer-encoding: chunked

3
bar
3
bar
3
bar
0

"""
  }

  def "can proxy a client error"() {
    given:
    otherApp {
      get("foo") {
        response.headers.add("x-foo-header", "foo")
        clientError(404)
      }
    }

    and:
    handlers {
      get { HttpClient httpClient ->
        httpClient.requestStream(otherAppUrl("foo")) {
        } then { StreamedResponse responseStream ->
          responseStream.forwardTo(response)
        }
      }
    }

    expect:
    rawResponse() == """HTTP/1.1 404 Not Found
x-foo-header: foo
content-type: text/plain
transfer-encoding: chunked

10
Client error 404
0

"""
  }

  def "can proxy a server error"() {
    given:
    otherApp {
      get("foo") {
        response.headers.add("x-foo-header", "foo")
        error(new Throwable("A server error occurred"))
      }
    }

    and:
    handlers {
      get { HttpClient httpClient ->
        httpClient.requestStream(otherAppUrl("foo")) {
        } then { StreamedResponse responseStream ->
          responseStream.forwardTo(response)
        }
      }
    }

    expect:
    rawResponse().with {
      startsWith("""HTTP/1.1 500 Internal Server Error
x-foo-header: foo
content-type: text/plain
transfer-encoding: chunked
""")
      contains("A server error occurred")
    }
  }

  def "can proxy compressed responses"() {
    given:
    otherApp {
      get("foo") {
        response.send("bar")
      }
    }

    and:
    handlers {
      get { HttpClient httpClient ->
        httpClient.request(otherAppUrl("foo")) { RequestSpec rs ->
          rs.decompressResponse(false)
          rs.headers.copy(request.headers)
        } then { ReceivedResponse receivedResponse ->
          receivedResponse.forwardTo(response)
        }
      }
    }

    when:
    def response = requestSpec { RequestSpec rs ->
      rs.decompressResponse(false)
      rs.headers { MutableHeaders h ->
        h.add("Accept-Encoding", "compress, gzip")
      }
    }.get()

    then:
    response.headers.get(CONTENT_ENCODING) == "gzip"
    new GZIPInputStream(response.body.inputStream).bytes == "bar".bytes
  }


  def "can proxy a post request"() {
    given:
    otherApp {
      post("foo") {
        request.body.then {
          response.send it.text
        }
      }
    }

    and:
    handlers {
      post { HttpClient httpClient ->
        httpClient.request(otherAppUrl("foo")) { RequestSpec rs ->
          rs.method("POST")
          rs.body { outgoingRequestBody ->
            request.body.then { incomingRequestBody ->
              outgoingRequestBody.buffer(incomingRequestBody.buffer)
            }
          }
        } then { ReceivedResponse receivedResponse ->
          receivedResponse.forwardTo(response)
        }
      }
    }

    when:
    def response = requestSpec { RequestSpec rs ->
      rs.body.text("bar")
    }.post()

    then:
    response.body.text == "bar"
  }
}
