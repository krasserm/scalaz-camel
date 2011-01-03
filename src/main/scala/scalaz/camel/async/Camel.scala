package scalaz.camel.async

/**
 * Experimental support for asynchronous Camel processors and endpoint producers. Allows the
 * construction of non-blocking routes. Provides a DSL on top of a CPS-based implementation.
 *
 * @author Martin Krasser
 */
object Camel extends CamelDsl