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

import org.apache.camel.{AsyncCallback, AsyncProcessor, Exchange, Processor}

import scalaz._

/**
 * @author Martin Krasser
 */
object CamelTestProcessors {
  import scalaz.concurrent.Strategy
  import Scalaz._
  import Camel.MessageProcessor
  import Camel.messageProcessor

  /** Concurrency strategy for each created processor (defaults to Strategy.Sequential) */
  var processorConcurrencyStrategy: Strategy = Strategy.Sequential

  /** Fails with Exception and error message em. */
  def failWith(em: String): MessageProcessor = ap(failWithSync(em))

  /** Fails with Exception and error message em (sync processor). */
  def failWithSync(em: String): Message => Message = (m: Message) => throw new Exception(em)

  /** Fails with exception e */
  def failWith(e: Exception): MessageProcessor = ap(m => throw e)

  /** Marks this message as error-handled */
  def markHandled: MessageProcessor = ap(m => m.exceptionHandled)

  /** Converts message body to String */
  def convertBodyToString(implicit mgnt: ContextMgnt) = ap(m => m.bodyTo[String])

  /** Appends o to message body */
  def appendToBody(o: Any)(implicit mgnt: ContextMgnt) = ap(appendToBodySync(o))

  /** Appends o to message body (sync processor) */
  def appendToBodySync(o: Any)(implicit mgnt: ContextMgnt) = (m: Message) => m.appendBody(o)

  /** Prints message to stdout */
  def printMessage = ap(printMessageSync)

  /** Prints message to stdout (sync processor) */
  def printMessageSync = (m: Message) => { println(m); m }

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

  /** Creates an asynchronous processor from a Message => Message function */
  def ap(p: Message => Message): MessageProcessor = Camel.messageProcessor(p, processorConcurrencyStrategy)
}