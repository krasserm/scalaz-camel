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
import org.scalatest.{WordSpec, BeforeAndAfterAll}
import org.scalatest.matchers.MustMatchers

/**
 * @author Martin Krasser
 */
class MessageTest extends WordSpec with BeforeAndAfterAll with MustMatchers {
  implicit val context = new DefaultCamelContext

  override protected def beforeAll = context.start

  def support = afterWord("support")

  "A message" must support {
    "body conversion to a specified type" in {
      Message(1.4).bodyAs[String] must equal("1.4")
      evaluating { Message(1.4).bodyAs[InputStream] } must produce [NoTypeConversionAvailableException]
    }

    "header conversion to a specified type" in {
      val message = Message("test" , Map("test" -> 1.4))
      message.headerAs[String]("test") must equal(Some("1.4"))
      message.headerAs[String]("blah") must equal(None)
    }

    "getting a header by name" in {
      val message = Message("test" , Map("test" -> 1.4))
      message.header("test") must equal(Some(1.4))
      message.header("blah") must equal(None)
    }

    "getting headers by a set of name" in {
      val message = Message("test" , Map("A" -> "1", "B" -> "2"))
      message.headers(Set("B")) must equal(Map("B" -> "2"))
    }

    "transformation of the body using a transformer function" in {
      val message = Message("a" , Map("A" -> "1"))
      message.transformBody[String](body => body + "b") must equal(Message("ab", Map("A" -> "1")))
    }

    "appending to the body using a string representation" in {
      val message = Message("a" , Map("A" -> "1"))
      message.appendBody("b") must equal(Message("ab", Map("A" -> "1")))
    }

    "setting the body" in {
      val message = Message("a" , Map("A" -> "1"))
      message.setBody("b") must equal(Message("b", Map("A" -> "1")))
    }

    "setting a headers set" in {
      val message = Message("a" , Map("A" -> "1"))
      message.setHeaders(Map("C" -> "3")) must equal(Message("a", Map("C" -> "3")))
    }

    "adding a header" in {
      val message = Message("a" , Map("A" -> "1"))
      message.addHeader("B" -> "2") must equal(Message("a", Map("A" -> "1", "B" -> "2")))
    }

    "adding a headers set" in {
      val message = Message("a" , Map("A" -> "1"))
      message.addHeaders(Map("B" -> "2")) must equal(Message("a", Map("A" -> "1", "B" -> "2")))
    }

    "removing a header" in {
      val message = Message("a" , Map("A" -> "1", "B" -> "2"))
      message.removeHeader("B") must equal(Message("a", Map("A" -> "1")))
    }

    "setting the exception header" in {
      val exception = new Exception("test")
      val message = Message("a").setException(exception)
      message.exception must equal(Some(exception))
      Message("a").exception must equal(None)
    }

    "clearing the exception header" in {
      val exception = new Exception("test")
      val message = Message("a").setException(exception)
      message.exceptionHandled.exception must equal(None)
    }
  }
}
