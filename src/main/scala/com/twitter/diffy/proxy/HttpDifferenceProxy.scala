package com.twitter.diffy.proxy

import java.net.SocketAddress

import com.twitter.diffy.analysis.{DifferenceAnalyzer, JoinedDifferences, InMemoryDifferenceCollector}
import com.twitter.diffy.lifter.{HttpLifter, Message}
import com.twitter.finagle.{Http, Filter}
import com.twitter.finagle.http.{Method, Request}
import com.twitter.util.{Try, Future}
import org.jboss.netty.handler.codec.http.{HttpResponse, HttpRequest}

trait HttpDifferenceProxy extends DifferenceProxy {
  val servicePort: SocketAddress
  val lifter = new HttpLifter(settings.excludeHttpHeadersComparison)

  override type Req = HttpRequest
  override type Rep = HttpResponse
  override type Srv = HttpService

  override def serviceFactory(serverset: String, label: String) =
    HttpService(Http.newClient(serverset, label).toService)

  override lazy val server = Http.serve(servicePort, proxy)

  override def liftRequest(req: HttpRequest): Future[Message] =
    lifter.liftRequest(req)

  override def liftResponse(resp: Try[HttpResponse]): Future[Message] =
    lifter.liftResponse(resp)
}

object SimpleHttpDifferenceProxy {
  /**
   * Side effects can be dangerous if replayed on production backends. This
   * filter ignores all POST, PUT, and DELETE requests if the
   * "allowHttpSideEffects" flag is set to false.
   */
  lazy val httpSideEffectsFilter =
    Filter.mk[HttpRequest, HttpResponse, HttpRequest, HttpResponse] { (req, svc) =>
      val hasSideEffects =
        Set(Method.Post, Method.Put, Method.Delete).contains(Request(req).method)

      if (hasSideEffects) DifferenceProxy.NoResponseException else svc(req)
    }
}

/**
 * A Twitter-specific difference proxy that adds custom filters to unpickle
 * TapCompare traffic from TFE and optionally drop requests that have side
 * effects
 * @param settings    The settings needed by DifferenceProxy
 */
case class SimpleHttpDifferenceProxy (
    settings: Settings,
    collector: InMemoryDifferenceCollector,
    joinedDifferences: JoinedDifferences,
    analyzer: DifferenceAnalyzer)
  extends HttpDifferenceProxy
{
  import SimpleHttpDifferenceProxy._

  override val servicePort = settings.servicePort
  override val proxy =
    Filter.identity andThenIf
      (!settings.allowHttpSideEffects, httpSideEffectsFilter) andThen
      super.proxy
}