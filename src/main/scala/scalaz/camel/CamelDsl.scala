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

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

import org.apache.camel.{Exchange, AsyncCallback, AsyncProcessor}

import scalaz._
import Scalaz._
import Message._

import concurrent.Promise
import concurrent.Strategy

/**
 * @author Martin Krasser
 */
trait CamelDslEip extends CamelConv {

  /**
   * Name of the position header needed used by scatter
   */
  val Position = "scalaz.camel.multicast.position"

  /**
   * Concurrency strategy for distributing messages to
   * destinations with the scatter and multicast EIPs.
   */
  protected def scatterStrategy: Strategy

  // ------------------------------------------
  //  EIPs
  // ------------------------------------------

  /**
   * Converts messages to one-way messages
   */
  def oneway = responderKleisli(messageProcessor { m: Message => m.setOneway(true) } )

  /**
   * Converts messages to two-way messages
   */
  def twoway = responderKleisli(messageProcessor { m: Message => m.setOneway(false) } )

  /**
   * The content-based router EIP.
   */
  def choose(f: PartialFunction[Message, MessageValidationResponderKleisli]): MessageValidationResponderKleisli =
    responderKleisli((m: Message, k: MessageValidation => Unit) => messageProcessor(f(m))(m, k))

  /**
   * The recipient-list EIP. It applies the concurrency strategy returned by
   * <code>scatterStrategy</code> to distribute messages to their destinations.
   * Distributed messages are combine using <code>combine</code>. The combined
   * message is dispatched to the next message processor.
   */
  def multicast(destinations: MessageValidationResponderKleisli*)(combine: (Message, Message) => Message): MessageValidationResponderKleisli = {
    val mcp = multicastProcessor(destinations.toList, combine)
    responderKleisli((m: Message, k: MessageValidation => Unit) => mcp(m, k))
  }

  /**
   * Distributes messages to given destinations. It applies the concurrency strategy
   * returned by <code>scatterStrategy</code> to distribute messages. Distributed
   * messages are not combined, all are dispatched individually to the next message
   * processor.
   */
  def scatter(destinations: MessageValidationResponderKleisli*) = responderKleisli {
    (m: Message, k: MessageValidation => Unit) => {
      0 until destinations.size foreach { i =>
        scatterStrategy.apply {
          destinations(i) apply m.success respond { mv => k(mv ∘ (_.addHeader(Position -> i))) }
        }
      }
    }
  }

  /**
   * Continues processing if <code>f</code> returns some message. Allows
   * providers of <code>f</code> to gather messages and continue processing
   * with a combined message, for example.
   */
  def gather(f: Message => Option[Message]) = responderKleisli {
    (m: Message, k: MessageValidation => Unit) => {
      f(m) match {
        case Some(r) => k(r.success)
        case None    => { /* do not continue */ }
      }
    }
  }

  // ------------------------------------------
  //  Internal
  // ------------------------------------------

  /** Creates a function that distributes messages to destinations and combines the responses */
  private def multicastProcessor(destinations: List[MessageValidationResponderKleisli], combine: (Message, Message) => Message): MessageProcessor = {
    (m: Message, k: MessageValidation => Unit) => {
      val mcScatter = scatter(destinations: _*)
      val mcGather  = gather(multicastGather(combine, destinations.size))
      // ... let's eat our own dog food ...
      mcScatter >=> mcGather apply m.success respond k
    }
  }

  /** Creates a function that collects and combines multicast responses (used with gather EIP) */
  def multicastGather(combine: (Message, Message) => Message, count: Int): Message => Option[Message] = {
    val ct = new AtomicInteger(count)
    val ma = Array.fill[Message](count)(null)
    (m: Message) => {
      for (pos <- m.header(Position).asInstanceOf[Option[Int]]) {
        ma.synchronized(ma(pos) = m)
      }
      if (ct.decrementAndGet == 0) {
        val ml = ma.synchronized(ma.toList)
        Some(ml.tail.foldLeft(ml.head)((m1, m2) => combine(m1, m2).removeHeader(Position)))
      } else {
        None
      }
    }
  }
}

/**
 * @author Martin Krasser
 */
trait CamelDslRoute extends CamelConv {

  // ------------------------------------------
  //  Route initiation and endpoints
  // ------------------------------------------

  /** Creates an endpoint producer */
  def to(uri: String)(implicit em: EndpointMgnt, cm: ContextMgnt) = messageProcessor(uri, em, cm)

  /** Defines the starting point of a route from the given endpoint */
  def from(uri: String)(implicit em: EndpointMgnt, cm: ContextMgnt) = new MainRouteDefinition(uri, em, cm)

  /** Represents the starting point of a route from an endpoint defined by <code>uri</code> */
  class MainRouteDefinition(uri: String, em: EndpointMgnt, cm: ContextMgnt) {
    /** Connects a route to the endpoint consumer */
    def route(r: MessageValidationResponderKleisli): ErrorRouteDefinition = {
      val processor = new RouteProcessor(r, cm) with ErrorRouteDefinition
      val consumer = em.createConsumer(uri, processor)
      processor
    }
  }

  /** Respresents the starting point of error handling routes for given exception classes */
  trait ErrorRouteDefinition {
    import collection.mutable.Buffer

    case class ErrorHandler(c: Class[_ <: Exception], r: MessageValidationResponderKleisli)

    private[camel] val errorHandlers = Buffer[ErrorHandler]()
    private[camel] var errorClass: Class[_ <: Exception] = _

    /** Associates the error handling route r with the current exception class */
    def route(r: MessageValidationResponderKleisli) = {
      errorHandlers append ErrorHandler(errorClass, r)
      this
    }

    /** Sets the current exception class to which an error handling route should be associated */
    def onError[A <: Exception](c: Class[A]) = {
      errorClass = c
      this
    }
  }

  private class RouteProcessor(p: MessageValidationResponderKleisli, cm: ContextMgnt) extends AsyncProcessor { this: ErrorRouteDefinition =>
    def process(exchange: Exchange, callback: AsyncCallback) =
      route(exchange.getIn.toMessage, exchange, callback, p, errorHandlers.toList)

    def process(exchange: Exchange) = {
      val latch = new CountDownLatch(1)
      process(exchange, new AsyncCallback() {
        def done(doneSync: Boolean) = {
          latch.countDown
        }
      })
      latch.await
    }

    private def route(message: Message, exchange: Exchange, callback: AsyncCallback, target: MessageValidationResponderKleisli, errorHandlers: List[ErrorHandler]): Boolean = {
      target apply message.success respond once((rv: MessageValidation) => rv match {
        case Failure(m) => {
          m.exception ∘ (exchange.setException(_))
          errorHandlers.find(_.c.isInstance(exchange.getException)) match {
            case None                     => callback.done(false)
            case Some(ErrorHandler(_, r)) => {
              exchange.setException(null)
              route(m, exchange, callback, r, Nil)
            }
          }
        }
        case Success(m) => {
          m.exception ∘ (exchange.setException(_))
          exchange.getIn.fromMessage(m)
          exchange.getOut.fromMessage(m)
          callback.done(false)
        }
      })
      false
    }

    private def once(k: MessageValidation => Unit): MessageValidation => Unit = {
      val done = new java.util.concurrent.atomic.AtomicBoolean(false)
      (mv: MessageValidation) => if (!done.getAndSet(true)) k(mv)
    }
  }
}

/**
 * @author Martin Krasser
 */
trait CamelDslAccess extends CamelConv {

  // ------------------------------------------
  //  Access to message processing results
  // ------------------------------------------

  /**
   * Provides convenient access to (asynchronous) message validation responses
   * generated by responder r. Hides continuation-passing style (CPS) usage of
   * responder r.
   *
   * @see Camel.responderToResponseAccess
   */
  class ValidationResponseAccess(r: Responder[MessageValidation]) {
    /** Obtain response from responder r (blocking) */
    def response: MessageValidation = responsePromise(Strategy.Sequential).get

    /** Obtain response promise from responder r */
    def responsePromise(implicit s: Strategy): Promise[MessageValidation] = {
      val queue = new java.util.concurrent.ArrayBlockingQueue[MessageValidation](2)
      r respond { mv => queue.put(mv) }
      promise(queue.take)
    }
  }

  /**
   * Provides convenient access to (asynchronous) message validation responses
   * generated by an application of responder Kleisli p (e.g. a Kleisli route)
   * to a message m. Hides continuation-passing style (CPS) usage of responder
   * Kleisli p.
   *
   * @see responderKleisliToResponseAccessKleisli
   */
  class ValidationResponseAccessKleisli(p: MessageValidationResponderKleisli) {
    /** Obtain response from responder Kleisli p for message m (blocking) */
    def responseFor(m: Message) = responsePromiseFor(m)(Strategy.Sequential).get

    /** Obtain response promise from responder Kleisli p for message m */
    def responsePromiseFor(m: Message)(implicit s: Strategy) =
      new ValidationResponseAccess(p apply m.success).responsePromise
  }
}
