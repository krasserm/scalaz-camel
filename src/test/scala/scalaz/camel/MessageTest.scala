/*
 * Copyright 2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package scalaz.camel

import java.io.InputStream

import org.apache.camel.NoTypeConversionAvailableException
import org.apache.camel.impl.DefaultCamelContext
import org.junit.Test
import org.scalatest.BeforeAndAfterAll
import org.scalatest.junit.JUnitSuite
import org.scalatest.matchers.MustMatchers

/**
 * @author Martin Krasser
 */
class MessageTest extends JUnitSuite with BeforeAndAfterAll with MustMatchers {
  implicit val context = new DefaultCamelContext

  override protected def beforeAll = context.start

  @Test def testBodyAs {
    Message(1.4).bodyAs[String] must equal("1.4")
    evaluating { Message(1.4).bodyAs[InputStream] } must produce [NoTypeConversionAvailableException]
  }

  @Test def testHeader {
    val message = Message("test" , Map("test" -> 1.4))
    message.header("test") must equal(Some(1.4))
    message.header("blah") must equal(None)
  }

  @Test def testHeaderAs {
    val message = Message("test" , Map("test" -> 1.4))
    message.headerAs[String]("test") must equal(Some("1.4"))
    message.headerAs[String]("blah") must equal(None)
  }

  @Test def testHeadersSet = {
    val message = Message("test" , Map("A" -> "1", "B" -> "2"))
    message.headers(Set("B")) must equal(Map("B" -> "2"))
  }

  @Test def testTransformBody {
    val message = Message("a" , Map("A" -> "1"))
    message.transformBody[String](body => body + "b") must equal(Message("ab", Map("A" -> "1")))
  }

  @Test def testAppendBody {
    val message = Message("a" , Map("A" -> "1"))
    message.appendBody("b") must equal(Message("ab", Map("A" -> "1")))
  }

  @Test def testSetBody {
    val message = Message("a" , Map("A" -> "1"))
    message.setBody("b") must equal(Message("b", Map("A" -> "1")))
  }

  @Test def testSetHeaders {
    val message = Message("a" , Map("A" -> "1"))
    message.setHeaders(Map("C" -> "3")) must equal(Message("a", Map("C" -> "3")))
  }

  @Test def testAddHeader {
    val message = Message("a" , Map("A" -> "1"))
    message.addHeader("B" -> "2") must equal(Message("a", Map("A" -> "1", "B" -> "2")))
  }

  @Test def testAddHeaders {
    val message = Message("a" , Map("A" -> "1"))
    message.addHeaders(Map("B" -> "2")) must equal(Message("a", Map("A" -> "1", "B" -> "2")))
  }

  @Test def testRemoveHeader {
    val message = Message("a" , Map("A" -> "1", "B" -> "2"))
    message.removeHeader("B") must equal(Message("a", Map("A" -> "1")))
  }

  @Test def testSetException {
    val exception = new Exception("test")
    val message = Message("a").setException(exception)
    message.exception must equal(Some(exception))
    Message("a").exception must equal(None)
  }

  @Test def testExceptionHandled {
    val exception = new Exception("test")
    val message = Message("a").setException(exception)
    message.exceptionHandled.exception must equal(None)
  }
}
