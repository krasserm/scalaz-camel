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
trait CamelTestProcessors { this: Conv =>
  import scalaz.concurrent.Strategy
  import Scalaz._

  /** Concurrency strategy for each created processor (defaults to Strategy.Sequential) */
  var processorConcurrencyStrategy: Strategy = Strategy.Sequential

  //
  // Direct-style processors: Message => Message (may throw exception)
  //

  /** Fails with Exception and error message em (direct-style processor). */
  def ds_failWithMessage(em: String): Message => Message = (m: Message) => throw new Exception(em)

  /** Appends o to message body (direct-style processor) */
  def ds_appendToBody(o: Any)(implicit mgnt: ContextMgnt) = (m: Message) => m.appendToBody(o)

  /** Prints message to stdout (direct-style processor) */
  def ds_printMessage = (m: Message) => { println(m); m }

  //
  // CPS (continuation-passing style) processors: (Message, MessageValidation => Unit) => Unit
  //

  /** Fails with Exception and error message em. */
  def failWithMessage(em: String): MessageProcessor = cps(ds_failWithMessage(em))

  /** Converts message body to String */
  def convertBodyToString(implicit mgnt: ContextMgnt) = cps(m => m.bodyTo[String])

  /** Appends o to message body */
  def appendToBody(o: Any)(implicit mgnt: ContextMgnt) = cps(ds_appendToBody(o))

  /** Prints message to stdout */
  def printMessage = cps(ds_printMessage)

  /**  Repeats message body (using String concatenation) */
  def repeatBody = new RepeatBodyProcessor(processorConcurrencyStrategy)

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

  /** Creates an CPS processor direct-style processor */
  def cps(p: Message => Message): MessageProcessor = messageProcessor(p, processorConcurrencyStrategy)
}