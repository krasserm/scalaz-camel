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

import scalaz.camel.core
import scalaz.camel.core.Conv._
import scalaz.camel.core.Message

import akka.actor.Actor
import akka.actor.ActorRef

/**
 * @author Martin Krasser
 */
object DslEip {
  type AggregationFunction = (Message, Message) => Message
  type CompletionPredicate = (Message) => Boolean
}

/**
 * @author Martin Krasser
 */
trait DslEip { this: DslEndpoint =>
  type AggregationFunction = DslEip.AggregationFunction
  type CompletionPredicate = DslEip.CompletionPredicate

  /**
   * <strong>Preliminary</strong> support for actor-based aggregator EIP.
   */
  def aggregate(implicit am: ActorMgnt) = AggregateDefinition(am)

  /**
   * <strong>Preliminary</strong> support for actor-based aggregator EIP.
   */
  case class AggregateDefinition(am: ActorMgnt, f: AggregationFunction = (m1, m2) => m2) {
    def using(f: AggregationFunction) = AggregateDefinition(am, f)
    def until(p: CompletionPredicate): MessageProcessor = to(manage(Actor.actorOf(new Aggregator(f, p)))(am))
  }

  private class Aggregator(f: AggregationFunction, p: CompletionPredicate) extends Actor {
    var current: Option[Message] = None

    def receive = {
      case message: Message => {
        val m = update(message)
        if (p(m)) self.reply(m)
      }
    }

    def update(message: Message): Message = {
      current match {
        case None    => { current = Some(message) }
        case Some(m) => { current = Some(f(m, message))}
      }
      current.get
    }
  }
}

/**
 * @author Martin Krasser
 */
trait DslEndpoint { this: core.DslEndpoint with Conv =>
  /**
   * Binds the life cycle of <code>actor</code> to that of the current CamelContext.
   */
  def manage(actor: ActorRef)(implicit am: ActorMgnt): ActorRef =
    am.manage(actor)

  /**
   * Creates a <code>MessageProcessor</code> for communicating with <code>actor</code>.
   * The created processor supports multiple replies from <code>actor</code>.
   */
  def to(actor: ActorRef): MessageProcessor =
    messageProcessor(actor)
}
