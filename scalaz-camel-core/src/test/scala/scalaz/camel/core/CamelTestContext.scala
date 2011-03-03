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
package scalaz.camel.core

import collection.mutable.Map

import org.apache.camel.component.mock.MockEndpoint
import org.apache.camel.impl.DefaultCamelContext

/**
 * @author Martin Krasser
 */
trait CamelTestContext extends Camel with CamelTestProcessors {
  import scalaz.concurrent.Strategy._

  dispatchConcurrencyStrategy = Sequential
  multicastConcurrencyStrategy = Sequential
  processorConcurrencyStrategy = Sequential

  val context = new DefaultCamelContext
  val template = context.createProducerTemplate

  implicit val router = new Router(context)

  val mocks = Map[String, MockEndpoint]()
  def mock(s: String) = {
    mocks.get(s) match {
      case Some(ep) => ep
      case None     => {
        val ep = context.getEndpoint("mock:%s" format s, classOf[MockEndpoint])
        mocks.put(s, ep)
        ep
      }
    }
  }
}