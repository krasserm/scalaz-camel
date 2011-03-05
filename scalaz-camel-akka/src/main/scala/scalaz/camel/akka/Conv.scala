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

import akka.actor.ActorRef
import akka.actor.Sender

import scalaz._

import scalaz.camel.core.Conv._
import scalaz.camel.core.Message

/**
 * @author Martin Krasser
 */
trait Conv {
  import Scalaz._

  /**
   * Converts <code>actor</code> into a <code>MessageProcessor</code>. The created processor
   * supports multiple replies from <code>actor</code>.
   */
  def messageProcessor(actor: ActorRef): MessageProcessor =
    (m: Message, k: MessageValidation => Unit) => {
      if (m.exchange.oneway) {
        actor.!(m); k(m.success)
      } else {
        actor.!(m)(Some(new Sender(k).start))
      } 
    }
}

