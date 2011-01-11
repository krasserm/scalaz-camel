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
import org.scalatest.{BeforeAndAfterAll, WordSpec}

import scalaz.concurrent.Strategy._

/**
 * @author Martin Krasser
 */
class CamelSetupTest extends WordSpec with MustMatchers with BeforeAndAfterAll {
  import Camel._
  import CamelTestProcessors._

  Camel.dispatchConcurrencyStrategy = Sequential
  Camel.scatterConcurrencyStrategy = Sequential
  CamelTestProcessors.processorConcurrencyStrategy = Naive

  val context = new DefaultCamelContext
  val template = context.createProducerTemplate
  implicit val router = new Router(context)

  override def afterAll = router.stop
  override def beforeAll = {
    // also setup these routes before router start
    from("direct:predef-1") route { appendToBody("-p1") }
    from("direct:predef-2") route { appendToBody("-p2") }
  }

  "scalaz.camel.Camel" when {
    "given an implicit router that has not been started" must {
      "allow setup of routes" in {
        from("direct:test-1") route {
          to("direct:predef-1") >=> appendToBody("-1")
        }
      }
    }
    "given an implicit router that has been started" must {
      "allow setup of routes" in {
        router.start
        from("direct:test-2") route {
          to("direct:predef-2") >=> appendToBody("-2")
        }

        template.requestBody("direct:test-1", "test") must equal("test-p1-1")
        template.requestBody("direct:test-2", "test") must equal("test-p2-2")
      }
    }
  }
}