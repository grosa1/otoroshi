package otoroshi.utils.netty

import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import io.netty.channel.Channel
import io.netty.handler.codec.http._
import io.netty.handler.ssl._
import org.reactivestreams.Publisher
import otoroshi.env.Env
import otoroshi.next.proxy.ProxyEngine
import otoroshi.script.RequestHandler
import otoroshi.ssl.{ClientAuth, DynamicSSLEngineProvider}
import otoroshi.utils.reactive.ReactiveStreamUtils
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.http.{HttpChunk, HttpEntity}
import play.api.libs.typedmap.TypedMap
import play.api.mvc.request.{Cell, RemoteConnection, RequestAttrKey, RequestTarget}
import play.api.mvc.{Cookies, Headers, Request, Results}
import reactor.core.publisher.Mono
import reactor.netty.http.server.HttpServerRequest

import java.net.{InetAddress, URI}
import java.security.cert.X509Certificate
import java.security.{Provider, SecureRandom}
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl._
import scala.jdk.CollectionConverters._

object ReactorNettyRemoteConnection {
  val logger = Logger("otoroshi-experiments-reactor-netty-server-remote-connection")
}

class ReactorNettyRemoteConnection(req: HttpServerRequest, val secure: Boolean, sessionOpt: Option[SSLSession]) extends RemoteConnection {
  lazy val remoteAddress: InetAddress = req.remoteAddress().getAddress
  lazy val clientCertificateChain: Option[Seq[X509Certificate]] = {
    if (secure) {
      sessionOpt match {
        case None =>
          ReactorNettyRemoteConnection.logger.warn(s"Something weird happened with the TLS session: it does not exists ...")
          None
        case Some(session) => {
          if (session.isValid) {
            // TODO: get the session from channel, but no channel access yet
            // private val sslHandler                       = Option(channel.pipeline().get(classOf[SslHandler]))
            // lazy val clientCertificateChain: Option[Seq[X509Certificate]] = {
            //   try {
            //     sslHandler.map { handler =>
            //       handler.engine.getSession.getPeerCertificates.toSeq.collect { case x509: X509Certificate => x509 }
            //     }
            //   } catch {
            //     case e: SSLPeerUnverifiedException => None
            //   }
            // }
            val certs = try {
              session.getPeerCertificates.toSeq.collect { case c: X509Certificate => c }
            } catch {
              case e: SSLPeerUnverifiedException => Seq.empty[X509Certificate]
            }
            if (certs.nonEmpty) {
              Some(certs)
            } else {
              None
            }
          } else {
            None
          }
        }
      }
    } else {
      None
    }
  }
}

class ReactorNettyRequestTarget(req: HttpServerRequest) extends RequestTarget {
  lazy val kUri = akka.http.scaladsl.model.Uri(uriString)
  lazy val uri: URI = new URI(uriString)
  lazy val uriString: String = req.uri()
  lazy val path: String = req.fullPath()
  lazy val queryMap: Map[String, Seq[String]] = kUri.query().toMultiMap.mapValues(_.toSeq)
}

object ReactorNettyRequest {
  val counter = new AtomicLong(0L)
}

class ReactorNettyRequest(req: HttpServerRequest, secure: Boolean, sessionOpt: Option[SSLSession]) extends Request[Source[ByteString, _]] {

  import scala.collection.JavaConverters._

  lazy val attrs = TypedMap.apply(
    RequestAttrKey.Id      -> ReactorNettyRequest.counter.incrementAndGet(),
    RequestAttrKey.Cookies -> Cell(Cookies(req.cookies().asScala.toSeq.flatMap {
      case (_, cookies) => cookies.asScala.map {
        case cookie: io.netty.handler.codec.http.cookie.DefaultCookie => {
          play.api.mvc.Cookie(
            name = cookie.name(),
            value = cookie.value(),
            maxAge = Option(cookie.maxAge()).map(_.toInt),
            path = Option(cookie.path()).filter(_.nonEmpty).getOrElse("/"),
            domain = Option(cookie.domain()).filter(_.nonEmpty),
            secure = cookie.isSecure,
            httpOnly = cookie.isHttpOnly,
            sameSite = Option(cookie.sameSite()).map {
              case e if e == io.netty.handler.codec.http.cookie.CookieHeaderNames.SameSite.None =>  play.api.mvc.Cookie.SameSite.None
              case e if e == io.netty.handler.codec.http.cookie.CookieHeaderNames.SameSite.Strict => play.api.mvc.Cookie.SameSite.Strict
              case e if e == io.netty.handler.codec.http.cookie.CookieHeaderNames.SameSite.Lax => play.api.mvc.Cookie.SameSite.Lax
              case _ => play.api.mvc.Cookie.SameSite.None
            }
          )
        }
        case cookie => {
          play.api.mvc.Cookie(
            name = cookie.name(),
            value = cookie.value(),
            maxAge = Option(cookie.maxAge()).map(_.toInt),
            path = Option(cookie.path()).filter(_.nonEmpty).getOrElse("/"),
            domain = Option(cookie.domain()).filter(_.nonEmpty),
            secure = cookie.isSecure,
            httpOnly = cookie.isHttpOnly,
            sameSite = None
          )
        }
      }
    }))
  )
  lazy val method: String = req.method().toString
  lazy val version: String = req.version().toString
  lazy val headers: Headers = Headers(
    (req.requestHeaders().entries().asScala.map(e => (e.getKey, e.getValue)) ++ sessionOpt.map(s => ("Tls-Session-Info", s.toString))): _*
  )
  lazy val body: Source[ByteString, _] = Source.fromPublisher(req.receive().retain()).map(bb => ByteString(bb.array()))
  lazy val connection: RemoteConnection = new ReactorNettyRemoteConnection(req, secure, sessionOpt)
  lazy val target: RequestTarget = new ReactorNettyRequestTarget(req)
}

case class HttpServerBodyResponse(body: Publisher[Array[Byte]], contentType: Option[String], contentLength: Option[Long], chunked: Boolean)

class HttpServer(env: Env) {

  import reactor.core.publisher.Flux
  import reactor.netty.http.HttpProtocol
  import reactor.netty.http.server._

  implicit private val ec = env.otoroshiExecutionContext
  implicit private val mat = env.otoroshiMaterializer
  implicit private val ev = env

  private val currentSession = new ThreadLocal[SSLSession]() // TODO: need to test if it actually works as intended

  private val logger = Logger("otoroshi-experiments-reactor-netty-server")

  private val engine: ProxyEngine = env.scriptManager.getAnyScript[RequestHandler](s"cp:${classOf[ProxyEngine].getName}").right.get.asInstanceOf[ProxyEngine]

  private lazy val cipherSuites =
    env.configuration
      .getOptionalWithFileSupport[Seq[String]]("otoroshi.ssl.cipherSuites")
      .filterNot(_.isEmpty)
  private lazy val protocols    =
    env.configuration
      .getOptionalWithFileSupport[Seq[String]]("otoroshi.ssl.protocols")
      .filterNot(_.isEmpty)
  private lazy val clientAuth = {
    val auth = env.configuration
      .getOptionalWithFileSupport[String]("otoroshi.ssl.fromOutside.clientAuth")
      .flatMap(ClientAuth.apply)
      .getOrElse(ClientAuth.None)
    if (DynamicSSLEngineProvider.logger.isDebugEnabled)
      DynamicSSLEngineProvider.logger.debug(s"Otoroshi client auth: ${auth}")
    auth
  }

  private def handle(req: HttpServerRequest, res: HttpServerResponse, secure: Boolean, channel: Channel): Publisher[Void] = {
    // TODO: handle standard otoroshi urls like in handlers.scala
    // val sslHandler = Option(channel.pipeline().get(classOf[SslHandler]))
    // val sslSession = sslHandler.map(_.engine.getSession) // Does not seems to work as the SslHandler is not available on the pipeline :(
    val sessionOpt = Option(currentSession.get())
    currentSession.remove()
    val isWebSocket = req.requestHeaders().contains("Sec-WebSocket-Version")
    if (isWebSocket) {
      // TODO: handle websocket calls
      res.status(500).headers(new DefaultHttpHeaders().add("Content-Type", "application/json")).sendString(Mono.just("""{"error":"websocket calls not supported yet"}"""))
      // res.sendWebsocket((wsInbound, wsOutbound) => wsOutbound.send(wsInbound.receive().retain()))
    } else {
      ReactiveStreamUtils.FluxUtils.fromFPublisher[Void] {
        val otoReq = new ReactorNettyRequest(req, secure, sessionOpt)
        engine.handle(otoReq, _ => Results.InternalServerError("bad default routing").vfuture).map { result =>
          val bresponse: HttpServerBodyResponse = result.body match {
            case HttpEntity.NoEntity => HttpServerBodyResponse(Flux.empty[Array[Byte]](), None, None, false)
            case HttpEntity.Strict(data, contentType) => HttpServerBodyResponse(Flux.just(Seq(data.toArray[Byte]): _*), contentType, Some(data.size.toLong), false)
            case HttpEntity.Chunked(chunks, contentType) => {
              val publisher = chunks.collect {
                case HttpChunk.Chunk(data) => data.toArray[Byte]
              }.runWith(Sink.asPublisher(false))
              HttpServerBodyResponse(publisher, contentType, None, true)
            }
            case HttpEntity.Streamed(data, contentLength, contentType) => {
              val publisher = data.map(_.toArray[Byte]).runWith(Sink.asPublisher(false))
              HttpServerBodyResponse(publisher, contentType, contentLength, false)
            }
          }
          val headers = new DefaultHttpHeaders()
          result.header.headers.map {
            case (key, value) => headers.add(key, value)
          }
          bresponse.contentType.foreach(ct => headers.add("Content-Type", ct))
          bresponse.contentLength.foreach(cl => headers.addInt("Content-Length", cl.toInt))
          res
            .status(result.header.status)
            .headers(headers)
            .applyOnIf(result.newCookies.nonEmpty) { r =>
              result.newCookies.map { cookie =>
                val nettyCookie = new io.netty.handler.codec.http.cookie.DefaultCookie(cookie.name, cookie.value)
                nettyCookie.setPath(cookie.path)
                nettyCookie.setHttpOnly(cookie.httpOnly)
                nettyCookie.setSecure(cookie.secure)
                cookie.domain.foreach(d => nettyCookie.setDomain(d))
                cookie.maxAge.foreach(d => nettyCookie.setMaxAge(d.toLong))
                cookie.sameSite.foreach {
                  case play.api.mvc.Cookie.SameSite.None => nettyCookie.setSameSite(io.netty.handler.codec.http.cookie.CookieHeaderNames.SameSite.None)
                  case play.api.mvc.Cookie.SameSite.Strict => nettyCookie.setSameSite(io.netty.handler.codec.http.cookie.CookieHeaderNames.SameSite.Strict)
                  case play.api.mvc.Cookie.SameSite.Lax => nettyCookie.setSameSite(io.netty.handler.codec.http.cookie.CookieHeaderNames.SameSite.Lax)
                }
                r.addCookie(nettyCookie)
              }
              r
            }
            .chunkedTransfer(bresponse.chunked)
            .keepAlive(true)
            .sendByteArray(bresponse.body)
        }
      }
    }
  }

  private def setupSslContext(): SSLContext = {
    new SSLContext(
      new SSLContextSpi() {
        override def engineCreateSSLEngine(): SSLEngine                     = DynamicSSLEngineProvider.createSSLEngine(clientAuth, cipherSuites, protocols, None)
        override def engineCreateSSLEngine(s: String, i: Int): SSLEngine    = engineCreateSSLEngine()
        override def engineInit(
                                 keyManagers: Array[KeyManager],
                                 trustManagers: Array[TrustManager],
                                 secureRandom: SecureRandom
                               ): Unit                                                             = ()
        override def engineGetClientSessionContext(): SSLSessionContext     =
          DynamicSSLEngineProvider.currentServer.getClientSessionContext
        override def engineGetServerSessionContext(): SSLSessionContext     =
          DynamicSSLEngineProvider.currentServer.getServerSessionContext
        override def engineGetSocketFactory(): SSLSocketFactory             =
          DynamicSSLEngineProvider.currentServer.getSocketFactory
        override def engineGetServerSocketFactory(): SSLServerSocketFactory =
          DynamicSSLEngineProvider.currentServer.getServerSocketFactory
      },
      new Provider(
        "[NETTY] Otoroshi SSlEngineProvider delegate",
        "1.0",
        "[NETTY] A provider that delegates calls to otoroshi dynamic one"
      )                   {},
      "[NETTY] Otoroshi SSLEngineProvider delegate"
    ) {}
  }

  def start(): Unit = {

    // TODO: start from config

    logger.debug("")
    logger.debug(s"Starting the Reactor Netty Server !!!")
    logger.debug(s" - https://0.0.0.0:${env.httpsPort + 50}") // TODO: from config
    logger.debug(s" - http://0.0.0.0:${env.httpPort + 50}") // TODO: from config
    logger.debug("")

    val serverHttps = HttpServer
      .create()
      .host("0.0.0.0")
      .accessLog(true) // TODO: from config
      .wiretap(false) // TODO: from config
      .port(env.httpsPort + 50) // TODO: from config
      .protocol(HttpProtocol.HTTP11, HttpProtocol.H2C)
      .doOnChannelInit { (observer, channel, socket) =>
        val engine = setupSslContext().createSSLEngine()
        engine.setHandshakeApplicationProtocolSelector((e, protocols) => {
          val session = e.getHandshakeSession
          if (currentSession.get() != null) {
            logger.warn(s"Something weird happened with the TLS session: it's not clean ...")
          }
          currentSession.set(session)
          protocols match {
            case ps if ps.contains("h2") => "h2"
            case ps if ps.contains("spdy/3") => "spdy/3"
            case _ => "http/1.1"
          }
        })
        // we do not use .secure() because of no dynamic sni support and use SslHandler instead !
        channel.pipeline().addFirst(new SslHandler(engine))
      }
      .handle { (req, res) =>
        val channel = NettyHelper.getChannel(req)
        handle(req, res, true, channel)
      }
      .bindNow()
    val serverHttp = HttpServer
      .create()
      .host("0.0.0.0")
      .noSSL()
      .accessLog(true) // TODO: from config
      .wiretap(false) // TODO: from config
      .port(env.httpPort + 50) // TODO: from config
      .protocol(HttpProtocol.H2C, HttpProtocol.HTTP11)
      .handle { (req, res) =>
        val channel = NettyHelper.getChannel(req)
        handle(req, res, false, channel)
      }
      .bindNow()
    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      serverHttp.disposeNow()
      serverHttps.disposeNow()
    }))
  }
}