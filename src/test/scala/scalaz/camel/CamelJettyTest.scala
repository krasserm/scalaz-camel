/*
 * Copyright 2010-2011 the original author or authors.
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

import org.scalatest.{WordSpec, BeforeAndAfterAll}
import org.scalatest.matchers.MustMatchers

import scalaz._
import scalaz.concurrent.Strategy._

/**
 * @author Martin Krasser
 */
class CamelJettyTest extends CamelTestContext with WordSpec with MustMatchers with BeforeAndAfterAll {
  import Scalaz._
  import Camel._
  import CamelTestProcessors._

  CamelTestProcessors.processorConcurrencyStrategy = Naive

  override def beforeAll = router.start
  override def afterAll = router.stop

  def support = afterWord("support")

  "scalaz.camel.Camel" should support {
    "non-blocking routing with asynchronous Jetty endpoints" in {

      // non-blocking server route using
      // - Jetty-continuation enabled endpoint and
      // - asynchronous message processors
      from("jetty:http://localhost:8766/test") route {
        convertBodyToString >=> repeatBody
      }

      // non-blocking client route using
      // - asynchronous Jetty HTTP client and
      // - asynchronous message processor
      from("direct:test-1") route {
        to("jetty:http://localhost:8766/test") >=> appendToBody("-done")
      }

      // the only blocking operation (waits for an answer)
      template.requestBody("direct:test-1", "test") must equal("testtest-done")
    }

    "caching of input streams so that original message can be used in error handlers" in {
      from("jetty:http://localhost:8761/test") route {
        convertBodyToString >=> failWith("failure")
      } onError classOf[Exception] route {
        appendToBody("-handled") >=> markHandled
      }

      template.requestBody("http://localhost:8761/test", "test", classOf[String]) must equal ("test-handled")
    }
  }

}