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

import org.apache.camel.builder.RouteBuilder
import org.apache.camel.impl.DefaultCamelContext

import org.scalatest.matchers.MustMatchers
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, WordSpec}

import scalaz.concurrent.Strategy._

/**
 * @author Martin Krasser
 */
class CamelJmsTest extends WordSpec with MustMatchers with BeforeAndAfterAll with BeforeAndAfterEach {
  import org.apache.camel.component.mock.MockEndpoint
  import org.apache.camel.spring.SpringCamelContext._

  import Camel._
  import CamelTestProcessors._

  Camel.dispatchConcurrencyStrategy = Sequential
  Camel.multicastConcurrencyStrategy = Sequential
  CamelTestProcessors.processorConcurrencyStrategy = Naive

  val context = springCamelContext("/context.xml")
  val template = context.createProducerTemplate
  implicit val router = new Router(context)

  override def beforeAll = router.start
  override def afterAll = router.stop
  override def afterEach = mock.reset

  def mock = context.getEndpoint("mock:mock", classOf[MockEndpoint])

  def support = afterWord("support")

  "scalaz.camel.Camel" should support {

    "communication with jms endpoints" in {
      from("jms:queue:test") route {
        appendToBody("-1") >=> appendToBody("-2") >=> printMessage >=> to("mock:mock")
      }

      from("direct:test") route {
        to("jms:queue:test") >=> printMessage
      }

      mock.expectedBodiesReceived("a-1-2", "b-1-2", "c-1-2")
      template.sendBody("direct:test", "a")
      template.sendBody("direct:test", "b")
      template.sendBody("direct:test", "c")
      mock.assertIsSatisfied
    }

    "fast failure of Kleisli routes" in {
      from("jms:queue:test-failure") route {
        appendToBody("-1") >=> choose {
          case Message("a-1", _) => failWith("failure")
          case Message("b-1", _) => printMessage
        } >=> to("mock:mock")
      }

      from("direct:test-failure") route {
        to("jms:queue:test-failure") >=> printMessage
      }

      mock.expectedBodiesReceived("b-1")
      template.sendBody("direct:test-failure", "a")
      template.sendBody("direct:test-failure", "b")
      mock.assertIsSatisfied
    }
  }
}