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

import org.apache.camel.{Exchange, ExchangePattern, CamelContext, Message => CamelMessage}

/**
 * An immutable representation of a Camel message.
 *
 * @author Martin Krasser
 */
case class Message(body: Any, headers: Map[String, Any] = Map.empty) {

  private val SkipExchangeUpdate = "scalaz.camel.update.exchange.skip"

  // TODO: consider making Message a parameterized type
  // TODO: consider making Message an instance of Functor

  val exchange = MessageExchange()

  override def toString = "Message: %s" format body

  def setBody(body: Any) = Message(body, headers, exchange)

  def setHeaders(headers: Map[String, Any]) = Message(body, headers, exchange)

  def addHeaders(headers: Map[String, Any]) = Message(body, this.headers ++ headers, exchange)

  def addHeader(header: (String, Any)) = Message(body, headers + header, exchange)

  def removeHeader(headerName: String) = Message(body, headers - headerName, exchange)

  def setExchange(exch: MessageExchange) = Message(body, headers, exch).setSkipExchangeUpdate(true)

  def setExchangeFrom(m: Message) = setExchange(m.exchange)

  def setOneway(oneway: Boolean) = setExchange(exchange.setOneway(oneway))

  def setException(e: Exception) = setExchange(exchange.setException(Some(e)))

  def exception: Option[Exception] = exchange.exception

  def headers(names: Set[String]): Map[String, Any] = headers.filter(names contains _._1)

  def header(name: String): Option[Any] = headers.get(name)

  def headerAs[A](name: String)(implicit m: Manifest[A], mgnt: ContextMgnt): Option[A] =
    header(name).map(convertTo[A](m.erasure.asInstanceOf[Class[A]], mgnt.context) _)

  def bodyAs[A](implicit m: Manifest[A], mgnt: ContextMgnt): A =
    convertTo[A](m.erasure.asInstanceOf[Class[A]], mgnt.context)(body)

  // TODO: remove once Message is a Functor
  def bodyTo[A](implicit m: Manifest[A], mgnt: ContextMgnt): Message =
    Message(convertTo[A](m.erasure.asInstanceOf[Class[A]], mgnt.context)(body), headers, exchange)

  // TODO: remove once Message is a Functor
  def transform[A](transformer: A => Any)(implicit m: Manifest[A], mgnt: ContextMgnt) =
    setBody(transformer(bodyAs[A]))

  // TODO: remove once Message is a Functor
  def appendToBody(body: Any)(implicit mgnt: ContextMgnt) =
    setBody(bodyAs[String] + convertTo[String](classOf[String], mgnt.context)(body))

  private[camel] def exceptionHandled =
    Message(body, headers, exchange.setException(None))

  // Experimental
  private[camel] def setSkipExchangeUpdate(skip: Boolean) =
    if (skip) addHeader(SkipExchangeUpdate, skip) else removeHeader(SkipExchangeUpdate)

  // Experimental
  private[camel] def skipExchangeUpdate = header(SkipExchangeUpdate) match {
    case Some(v) => v.asInstanceOf[Boolean]
    case None    => false
  }

  private def convertTo[A](c: Class[A], context: CamelContext)(a: Any): A =
    context.getTypeConverter.mandatoryConvertTo[A](c, a)
}

/**
 * An immutable representation of a Camel exchange.
 *
 * @author Martin Krasser
 */
case class MessageExchange(oneway: Boolean, exception: Option[Exception]) {
  def setOneway(o: Boolean) = MessageExchange(o, exception)

  def setException(e: Option[Exception]) = MessageExchange(oneway, e)
}

/**
 * @author Martin Krasser
 */
object Message {
  /** Creates a MessageConverter from a Camel message */
  implicit def camelMessageToConverter(cm: CamelMessage): MessageConverter = new MessageConverter(cm)

  /** Create a Message with body, headers and a message exchange */
  def apply(body: Any, headers: Map[String, Any], exch: MessageExchange): Message = new Message(body, headers) {
    override val exchange = exch
  }
}

/**
 * @author Martin Krasser
 */
object MessageExchange {
  /** Creates a MessageExchangeConverter from a Camel exchange */
  implicit def camelExchangeToConverter(ce: Exchange) = new MessageExchangeConverter(ce)

  /** Create a default MessageExchange with oneway set to false and no exception */
  def apply(): MessageExchange = MessageExchange(false, None)
}

/**
 * Converts between <code>scalaz.camel.Message</code> and <code>org.apache.camel.Message</code>.
 *
 * @author Martin Krasser
 */
class MessageConverter(val cm: CamelMessage) {
  import scala.collection.JavaConversions._
  import MessageExchange._

  def fromMessage(m: Message): CamelMessage = {
    cm.getExchange.fromMessageExchange(m.exchange)
    cm.setBody(m.body)
    for (h <- m.headers) cm.getHeaders.put(h._1, h._2.asInstanceOf[AnyRef])
    cm
  }

  def toMessage: Message = toMessage(Map.empty)
  def toMessage(headers: Map[String, Any]): Message =
    Message(cm.getBody, cmHeaders(cm, headers), cm.getExchange.toMessageExchange)

  private def cmHeaders(cm: CamelMessage, headers: Map[String, Any]) = headers ++ cm.getHeaders
}

/**
 * Converts between <code>scalaz.camel.MessageExchange</code> and <code>org.apache.camel.Exchange</code>.
 *
 * @author Martin Krasser
 */
class MessageExchangeConverter(val ce: Exchange) {
  def fromMessageExchange(me: MessageExchange) = {
    ce.setPattern(if (me.oneway) ExchangePattern.InOnly else ExchangePattern.InOut)
    ce.setException(me.exception match {
        case Some(e) => e
        case None    => null
      })
  }

  def toMessageExchange = MessageExchange(
    if (ce.getPattern.isOutCapable) false else true,
    if (ce.getException == null) None else Some(ce.getException)
  )
}