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

import java.util.concurrent.{BlockingQueue, CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger

import org.apache.camel.{Exchange, AsyncCallback, AsyncProcessor}

import scalaz._
import Scalaz._
import Message._

import concurrent.Promise
import concurrent.Strategy

/**
 * Message processors representing enterprise integration patterns (EIPs).
 *
 * @author Martin Krasser
 */
trait DslEip { this: Conv =>

  /**
   * Name of the position message header needed by scatter-gather. Needed to
   * preserve the order of messages that are distributed to destinations.
   */
  val Position = "scalaz.camel.multicast.position"

  /**
   * Concurrency strategy for distributing messages to destinations
   * with the multicast and scatter-gather EIPs.
   */
  protected def multicastStrategy: Strategy

  /**
   * Creates a message processor that changes the message's exchange pattern to one-way.
   */
  def oneway = messageProcessor { m: Message => m.setOneway(true) }

  /**
   * Creates a message processor that changes the message's exchange pattern to two-way.
   */
  def twoway = messageProcessor { m: Message => m.setOneway(false) }

  /**
   * Creates a message processor that routes messages based on pattern matching. Implements
   * the content-based router EIP.
   */
  def choose(f: PartialFunction[Message, MessageValidationResponderKleisli]): MessageProcessor =
    (m: Message, k: MessageValidation => Unit) => {
      f.lift(m) match {
        case Some(r) => messageProcessor(r)(m, k)
        case None    => k(m.success)
      }
    }

  /**
   * Creates a message processor that distributes messages to given destinations. The created
   * processor applies the concurrency strategy returned by <code>multicastStrategy</code>
   * to distribute messages. Distributed messages are not combined, instead n responses
   * are sent where n is the number of destinations. Implements the static recipient-list EIP.
   */
  def multicast(destinations: MessageValidationResponderKleisli*): MessageProcessor =
    (m: Message, k: MessageValidation => Unit) => {
      0 until destinations.size foreach { i =>
        multicastStrategy.apply {
          destinations(i) apply m.success respond { mv => k(mv ∘ (_.addHeader(Position -> i))) }
        }
      }
    }

  /**
   * Creates a message processor that generates a sequence of messages using <code>f</code> and
   * sends n responses taken from the generated message sequence. Implements the splitter EIP.
   */
  def split(f: Message => Seq[Message]): MessageProcessor =
    (m: Message, k: MessageValidation => Unit) => {
      try {
        f(m) foreach { r => k(r.success) }
      } catch {
        case e: Exception => k(m.setException(e).fail)
      }
    }

  /**
   * Creates a message processor that filters messages if <code>f</code> returns None and sends
   * a response if <code>f</code> returns Some message. Allows providers of <code>f</code> to
   * aggregate messages and continue processing with a combined message, for example.
   * Implements the aggregator EIP.
   */
  def aggregate(f: Message => Option[Message]): MessageProcessor =
    (m: Message, k: MessageValidation => Unit) => {
      try {
        f(m) match {
          case Some(r) => k(r.success)
          case None    => { /* do not continue */ }
        }
      } catch {
        case e: Exception => k(m.setException(e).fail)
      }
    }

  /**
   * Creates a message processor that filters messages by evaluating predicate <code>p</code>. If
   * <code>p</code> evaluates to <code>true</code> a response is sent, otherwise the message is
   * filtered. Implements the filter EIP.
   */
  def filter(p: Message => Boolean) = aggregate { m: Message =>
    if (p(m)) Some(m) else None
  }

  /**
   * Creates a builder for a scatter-gather processor.  Implements the scatter-gather EIP.
   *
   * @see ScatterDefinition
   */
  def scatter(destinations: MessageValidationResponderKleisli*) = new ScatterDefinition(destinations: _*)

  /**
   * Builder for a scatter-gather processor.
   *
   * @see scatter
   */
  class ScatterDefinition(destinations: MessageValidationResponderKleisli*) {

    /**
     * Creates a message processor that scatters messages to <code>destinations</code> and
     * gathers and combines them using <code>combine</code>. Messages are scattered to
     * <code>destinations</code> using the concurrency strategy returned by
     * <code>multicastStrategy</code>. Implements the scatter-gather EIP.
     *
     * @see scatter
     */
    def gather(combine: (Message, Message) => Message): MessageValidationResponderKleisli = {
      val mcp = multicastProcessor(destinations.toList, combine)
      responderKleisli((m: Message, k: MessageValidation => Unit) => mcp(m, k))
    }
  }

  /** 
   * Creates a message processor that sets exception <code>e</code> on the input message and
   * generates a failure.
   */
  def failWith(e: Exception): MessageProcessor =
    (m: Message, k: MessageValidation => Unit) => k(m.setException(e).fail)

  // ------------------------------------------
  //  Internal
  // ------------------------------------------

  /**
   * Creates a message processor that distributes messages to destinations (using multicast) and gathers
   * and combines the responses using an aggregator with <code>gatherFunction</code>.
   */
  private def multicastProcessor(destinations: List[MessageValidationResponderKleisli], combine: (Message, Message) => Message): MessageProcessor = {
    (m: Message, k: MessageValidation => Unit) => {
      val sgm = multicast(destinations: _*)
      val sga = aggregate(gatherFunction(combine, destinations.size))
      responderKleisli(sgm) >=> responderKleisli(sga) apply m.success respond k
    }
  }

  /**
   * Creates an aggregation function that gathers and combines multicast responses. The function has
   * a side-effect because it collects messages in a data structure that is created for each created
   * gather function.
   */
  private def gatherFunction(combine: (Message, Message) => Message, count: Int): Message => Option[Message] = {
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

trait DslAttempt { this: Conv =>

  type AttemptHandler1 = PartialFunction[Exception, MessageValidationResponderKleisli]
  type AttemptHandlerN = PartialFunction[(Exception, RetryState), MessageValidationResponderKleisli]

  /**
   * Captures the state of (repeated) message routing attempts. A retry state is defined by
   * <ul>
   * <li>the attempted route <code>r</code></li>
   * <li>the fallback routes returned by <code>h</code></li>
   * <li>the remaining number of retries (can be modified by application code)</li>
   * <li>the original message <code>orig</code> used as input for the attempted route</li>
   * </ul>
   */
  case class RetryState(r: MessageValidationResponderKleisli, h: AttemptHandlerN, count: Int, orig: Message) {
    def next = RetryState(r, h, count - 1, orig)
  }

  /**
   * Creates a message processor that extracts the original message from retry state <code>s</code>.
   */
  def orig(s: RetryState): MessageProcessor =
    (m: Message, k: MessageValidation => Unit) => k(s.orig.success)

  /**
   * Creates a builder for an attempt-fallback processor. The processor makes a single attempt
   * to apply route <code>r</code> to an input message.
   */
  def attempt(r: MessageValidationResponderKleisli) = new AttemptDefinition0(r)

  /**
   * Creates a builder for an attempt(n)-fallback processor. The processor can be used to make n
   * attempts to apply route <code>r</code> to an input message.
   *
   * @see orig
   * @see retry
   */
  def attempt(count: Int)(r: MessageValidationResponderKleisli) = new AttemptDefinitionN(r, count - 1)

  /**
   * Creates a message processor that makes an additional attempt to apply <code>s.r</code>
   * (the initially attempted route) to its input message. The message processor decreases
   * <code>s.count</code> (the retry count by one). A retry attempt is only made if an exception
   * is set on the message an <code>s.h</code> (a retry handler) is defined at that exception.
   */
  def retry(s: RetryState): MessageProcessor =
    (m: Message, k: MessageValidation => Unit) => {
      s.r apply m.success respond { mv => mv match {
        case Success(_) => k(mv)
        case Failure(m) => {
          for {
            e <- m.exception
            r <- s.h.lift(e, s.next)
          } {
            if (s.count > 0) r apply m.exceptionHandled.success respond k else k(mv)
          }
        }
      }}

    }

  /**
   * Builder for an attempt-retry processor.
   */
  class AttemptDefinition0(r: MessageValidationResponderKleisli) {
    /**
     * Creates an attempt-retry processor using retry handlers defined by <code>h</code>.
     */
    def fallback(h: AttemptHandler1): MessageProcessor =
      (m: Message, k: MessageValidation => Unit) => {
        r apply m.success respond { mv => mv match {
          case Success(_) => k(mv)
          case Failure(m) => {
            for {
              e <- m.exception
              r <- h.lift(e)
            } {
              r apply m.exceptionHandled.success respond k
            }
          }
        }}
      }
  }

  /**
   * Builder for an attempt(n)-retry processor.
   */
  class AttemptDefinitionN(r: MessageValidationResponderKleisli, count: Int) {
    /**
     * Creates an attempt(n)-retry processor using retry handlers defined by <code>h</code>.
     */
    def fallback(h: AttemptHandlerN): MessageProcessor =
      (m: Message, k: MessageValidation => Unit) => {
        retry(new RetryState(r, h, count, m))(m, k)
      }
  }
}

/**
 * DSL for endpoint management.
 *
 * @author Martin Krasser
 */
trait DslEndpoint { this: Conv =>

  /**
   * Creates a consumer for an endpoint represented by <code>uri</code> and connects it to the route
   * <code>r</code>. This method has a side-effect because it registers the created consumer at the
   * Camel context for lifecycle management.
   */
  def from(uri: String)(r: MessageValidationResponderKleisli)(implicit em: EndpointMgnt, cm: ContextMgnt): Unit =
    em.createConsumer(uri, new RouteProcessor(r))

  /**
   * Creates a CPS processor that acts as a producer to the endpoint represented by <code>uri</code>.
   * This method has a side-effect because it registers the created producer at the Camel context for
   * lifecycle management.
   */
  def to(uri: String)(implicit em: EndpointMgnt, cm: ContextMgnt): MessageProcessor = messageProcessor(uri, em, cm)

  private class RouteProcessor(val p: MessageValidationResponderKleisli) extends AsyncProcessor {
    import RouteProcessor._

    /**
     *  Synchronous message processing.
     */
    def process(exchange: Exchange) = {
      val latch = new CountDownLatch(1)
      process(exchange, new AsyncCallback() {
        def done(doneSync: Boolean) = {
          latch.countDown
        }
      })
      latch.await
    }

    /**
     * Asynchronous message processing (may be synchronous as well if all message processor are synchronous
     * processors and all concurrency strategies are configured to be <code>Sequential</code>).
     */
    def process(exchange: Exchange, callback: AsyncCallback) =
      if (exchange.getPattern.isOutCapable) processInOut(exchange, callback) else processInOnly(exchange, callback)

    private def processInOut(exchange: Exchange, callback: AsyncCallback) = {
      route(exchange.getIn.toMessage, once(respondTo(exchange, callback)))
      false
    }

    private def processInOnly(exchange: Exchange, callback: AsyncCallback) = {
      route(exchange.getIn.toMessage.setOneway(true), ((mv: MessageValidation) => { /* ignore any result */ }))
      callback.done(true)
      true
    }

    private def route(message: Message, k: MessageValidation => Unit): Unit = p apply message.success respond k
  }

  private object RouteProcessor {
    def respondTo(exchange: Exchange, callback: AsyncCallback): MessageValidation => Unit = (mv: MessageValidation ) => mv match {
      case Success(m) => respond(m, exchange, callback)
      case Failure(m) => respond(m, exchange, callback)
    }

    def respond(message: Message, exchange: Exchange, callback: AsyncCallback): Unit = {
      message.exception ∘ (exchange.setException(_))
      exchange.getIn.fromMessage(message)
      exchange.getOut.fromMessage(message)
      callback.done(false)
    }

    def once(k: MessageValidation => Unit): MessageValidation => Unit = {
      val done = new java.util.concurrent.atomic.AtomicBoolean(false)
      (mv: MessageValidation) => if (!done.getAndSet(true)) k(mv)
    }
  }
}

/**
 * DSL for convenient access to responses generated by Kleisli routes (i.e. when no endpoint
 * consumer is created via <code>from(...)</code>).
 *
 * @author Martin Krasser
 */
trait DslAccess { this: Conv =>

  /**
   * Provides convenient access to message validation responses generated by responder r.
   * Hides continuation-passing style (CPS) usage of responder r.
   *
   * @see Camel.responderToResponseAccess
   */
  class ValidationResponseAccess(r: Responder[MessageValidation]) {
    /** Obtain response from responder r (blocking) */
    def response: MessageValidation = responseQueue.take

    /** Obtain response from responder r (blocking with timeout) */
    def response(timeout: Long, unit:  TimeUnit): MessageValidation = responseQueue.poll(timeout, unit)

    /** Obtain response promise from responder r */
    def responsePromise(implicit s: Strategy): Promise[MessageValidation] = promise(responseQueue.take)

    /** Obtain response queue from responder r */
    def responseQueue: BlockingQueue[MessageValidation] = {
      val queue = new java.util.concurrent.LinkedBlockingQueue[MessageValidation](10)
      r respond { mv => queue.put(mv) }
      queue
    }
  }

  /**
   * Provides convenient access to message validation responses generated by an
   * application of responder Kleisli p (e.g. a Kleisli route) to a message m.
   * Hides continuation-passing style (CPS) usage of responder Kleisli p.
   *
   * @see Camel.responderKleisliToResponseAccessKleisli
   */
  class ValidationResponseAccessKleisli(p: MessageValidationResponderKleisli) {
    /** Obtain response from responder Kleisli p for message m (blocking) */
    def responseFor(m: Message) =
      new ValidationResponseAccess(p apply m.success).response

    /** Obtain response from responder Kleisli p for message m (blocking with timeout) */
    def responseFor(m: Message, timeout: Long, unit:  TimeUnit) =
      new ValidationResponseAccess(p apply m.success).response(timeout: Long, unit:  TimeUnit)

    /** Obtain response promise from responder Kleisli p for message m */
    def responsePromiseFor(m: Message)(implicit s: Strategy) =
      new ValidationResponseAccess(p apply m.success).responsePromise

    /** Obtain response queue from responder Kleisli p for message m */
    def responseQueueFor(m: Message) =
      new ValidationResponseAccess(p apply m.success).responseQueue
  }
}
