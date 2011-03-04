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
package scalaz.camel.akka

import scalaz.camel.core._

import org.apache.camel.impl.DefaultCamelContext

/**
 * @author Martin Krasser
 */
trait AkkaTestContext extends Camel with Akka with AkkaTestProcessors {
  import scalaz.concurrent.Strategy._

  dispatchConcurrencyStrategy = Sequential
  multicastConcurrencyStrategy = Sequential

  val context = new DefaultCamelContext
  val template = context.createProducerTemplate

  implicit val router = new Router(context) with ActorMgnt
}