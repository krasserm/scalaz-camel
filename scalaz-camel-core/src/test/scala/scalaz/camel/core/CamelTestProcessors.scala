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

import org.apache.camel.{AsyncCallback, AsyncProcessor, Exchange, Processor}

import scalaz._

/**
 * @author Martin Krasser
 */
object CamelTestProcessors {
  import scalaz.concurrent.Strategy
  import Scalaz._

  import Camel.MessageProcessor

  def appendToBody(o: Any)(implicit mgnt: ContextMgnt): MessageProcessor = (m: Message) => m.appendToBody(o).right

  /** Camel processor that repeats the body of the input message */
  class RepeatBodyProcessor(s: Strategy) extends AsyncProcessor {
    def process(exchange: Exchange) = {
      val body = exchange.getIn.getBody(classOf[String])
      exchange.getIn.setBody(body + body)
    }

    def process(exchange: Exchange, callback: AsyncCallback) = {
      s.apply {
        process(exchange)
        callback.done(false)
      }
      false
    }

    def sp = this.asInstanceOf[Processor]
  }
}