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
  import CamelTestProcessors.{failWith => failWithErrorMessage, _}

  CamelTestProcessors.processorConcurrencyStrategy = Naive

  override def beforeAll = router.start
  override def afterAll = router.stop

  def support = afterWord("support")

  "scalaz.camel.Camel" should support {
    "non-blocking routing with asynchronous Jetty endpoints" in {

      // non-blocking server route with asynchronous CPS processors
      // and a Jetty endpoint using Jetty continuations.
      from("jetty:http://localhost:8766/test") {
        convertBodyToString >=> repeatBody
      }

      // non-blocking server route with asynchronous CPS processors
      // and a Jetty endpoint using an asynchronous HTTP client.
      from("direct:test-1") {
        to("jetty:http://localhost:8766/test") >=> appendToBody("-done")
      }

      // the only blocking operation here (waits for an answer)
      template.requestBody("direct:test-1", "test") must equal("testtest-done")
    }

    "passing the latest update of messages to error handlers" in {
      // latest update before failure is conversion of body to string
      from("jetty:http://localhost:8761/test") {
        attempt {
          convertBodyToString >=> failWithErrorMessage("failure")
        } fallback {
          case e: Exception => appendToBody("-handled")
        }
      }

      template.requestBody("http://localhost:8761/test", "test", classOf[String]) must equal ("test-handled")
    }
  }

}