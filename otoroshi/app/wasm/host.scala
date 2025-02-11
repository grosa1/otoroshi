package otoroshi.wasm

import akka.http.scaladsl.model.Uri
import akka.stream.Materializer
import akka.util.ByteString
import org.extism.sdk._
import org.joda.time.DateTime
import otoroshi.cluster.ClusterConfig
import otoroshi.env.Env
import otoroshi.events.WasmLogEvent
import otoroshi.models._
import otoroshi.next.models.NgTarget
import otoroshi.next.plugins.api.NgCachedConfigContext
import otoroshi.utils.cache.types.LegitTrieMap
import otoroshi.utils.json.JsonOperationsHelper
import otoroshi.utils.syntax.implicits._
import otoroshi.utils.{ConcurrentMutableTypedMap, RegexPool, TypedMap}
import play.api.libs.json._

import java.nio.charset.StandardCharsets
import java.util.Optional
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.{Duration, DurationInt, FiniteDuration}
import scala.concurrent.{Await, ExecutionContext, Future}

object Utils {
  def rawBytePtrToString(plugin: ExtismCurrentPlugin, offset: Long, arrSize: Long): String = {
    val memoryLength = LibExtism.INSTANCE.extism_current_plugin_memory_length(plugin.pointer, arrSize)
    val arr          = plugin
      .memory()
      .share(offset, memoryLength)
      .getByteArray(0, arrSize.toInt)
    new String(arr, StandardCharsets.UTF_8)
  }

  def contextParamsToString(plugin: ExtismCurrentPlugin, params: LibExtism.ExtismVal*) = {
    rawBytePtrToString(plugin, params(0).v.i64, params(1).v.i32)
  }

  def contextParamsToJson(plugin: ExtismCurrentPlugin, params: LibExtism.ExtismVal*) = {
    Json.parse(rawBytePtrToString(plugin, params(0).v.i64, params(1).v.i32))
  }
}

case class EnvUserData(
    env: Env,
    executionContext: ExecutionContext,
    mat: Materializer,
    config: WasmConfig,
    ctx: Option[NgCachedConfigContext] = None
) extends HostUserData

case class StateUserData(
    env: Env,
    executionContext: ExecutionContext,
    mat: Materializer,
    cache: LegitTrieMap[String, LegitTrieMap[String, ByteString]]
)                          extends HostUserData
case class EmptyUserData() extends HostUserData

object LogLevel extends Enumeration {
  type LogLevel = Value

  val LogLevelTrace, LogLevelDebug, LogLevelInfo, LogLevelWarn, LogLevelError, LogLevelCritical, LogLevelMax = Value
}

object Status extends Enumeration {
  type Status = Value

  val StatusOK, StatusNotFound, StatusBadArgument, StatusEmpty, StatusCasMismatch, StatusInternalFailure,
      StatusUnimplemented = Value
}

case class HostFunctionWithAuthorization(
    function: HostFunction[_ <: HostUserData],
    authorized: WasmAuthorizations => Boolean
)

trait AwaitCapable {
  def await[T](future: Future[T], atMost: FiniteDuration = 5.seconds)(implicit env: Env): T = {
    Await.result(future, atMost) // TODO: atMost from env
  }
}

object HFunction {

  def defineEmptyFunction(fname: String, returnType: LibExtism.ExtismValType, params: LibExtism.ExtismValType*)(
      f: (ExtismCurrentPlugin, Array[LibExtism.ExtismVal], Array[LibExtism.ExtismVal]) => Unit
  ): org.extism.sdk.HostFunction[EmptyUserData] = {
    defineFunction[EmptyUserData](fname, None, returnType, params: _*)((p1, p2, p3, _) => f(p1, p2, p3))
  }

  def defineClassicFunction(
      fname: String,
      config: WasmConfig,
      returnType: LibExtism.ExtismValType,
      maybeContext: Option[NgCachedConfigContext],
      params: LibExtism.ExtismValType*
  )(
      f: (ExtismCurrentPlugin, Array[LibExtism.ExtismVal], Array[LibExtism.ExtismVal], EnvUserData) => Unit
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): org.extism.sdk.HostFunction[EnvUserData] = {
    val ev = EnvUserData(env, ec, mat, config, maybeContext)
    defineFunction[EnvUserData](fname, ev.some, returnType, params: _*)((p1, p2, p3, _) => f(p1, p2, p3, ev))
  }

  def defineContextualFunction(
      fname: String,
      config: WasmConfig,
      maybeContext: Option[NgCachedConfigContext]
  )(
      f: (ExtismCurrentPlugin, Array[LibExtism.ExtismVal], Array[LibExtism.ExtismVal], EnvUserData) => Unit
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): org.extism.sdk.HostFunction[EnvUserData] = {
    val ev = EnvUserData(env, ec, mat, config, maybeContext)
    defineFunction[EnvUserData](
      fname,
      ev.some,
      LibExtism.ExtismValType.I64,
      LibExtism.ExtismValType.I64,
      LibExtism.ExtismValType.I64
    )((p1, p2, p3, _) => f(p1, p2, p3, ev))
  }

  def defineFunction[A <: HostUserData](
      fname: String,
      data: Option[A],
      returnType: LibExtism.ExtismValType,
      params: LibExtism.ExtismValType*
  )(
      f: (ExtismCurrentPlugin, Array[LibExtism.ExtismVal], Array[LibExtism.ExtismVal], Option[A]) => Unit
  ): org.extism.sdk.HostFunction[A] = {
    new org.extism.sdk.HostFunction[A](
      fname,
      Array(params: _*),
      Array(returnType),
      new ExtismFunction[A] {
        override def invoke(
            plugin: ExtismCurrentPlugin,
            params: Array[LibExtism.ExtismVal],
            returns: Array[LibExtism.ExtismVal],
            data: Optional[A]
        ): Unit = {
          f(plugin, params, returns, if (data.isEmpty) None else Some(data.get()))
        }
      },
      data match {
        case None    => Optional.empty[A]()
        case Some(d) => Optional.of(d)
      }
    )
  }
}

object Logging extends AwaitCapable {

  def proxyLog() = HFunction.defineEmptyFunction(
    "proxy_log",
    LibExtism.ExtismValType.I32,
    LibExtism.ExtismValType.I32,
    LibExtism.ExtismValType.I64,
    LibExtism.ExtismValType.I64
  ) { (plugin, params, returns) =>
    val logLevel = LogLevel(params(0).v.i32)

    val messageData = Utils.rawBytePtrToString(plugin, params(1).v.i64, params(2).v.i64)

    System.out.println(String.format("[%s]: %s", logLevel.toString, messageData))

    returns(0).v.i32 = Status.StatusOK.id
  }

  def proxyLogWithEvent(config: WasmConfig, ctx: Option[NgCachedConfigContext])(implicit
      env: Env,
      executionContext: ExecutionContext,
      mat: Materializer
  ): org.extism.sdk.HostFunction[EnvUserData] = {
    HFunction.defineClassicFunction(
      "proxy_log_event",
      config,
      LibExtism.ExtismValType.I64,
      ctx,
      LibExtism.ExtismValType.I64,
      LibExtism.ExtismValType.I64
    ) { (plugin, params, returns, ud) =>
      val data  = Utils.contextParamsToJson(plugin, params: _*)
      val event = WasmLogEvent(
        `@id` = ud.env.snowflakeGenerator.nextIdStr(),
        `@service` = ud.ctx.map(e => e.route.name).getOrElse(""),
        `@serviceId` = ud.ctx.map(e => e.route.id).getOrElse(""),
        `@timestamp` = DateTime.now(),
        route = ud.ctx.map(_.route),
        fromFunction = (data \ "function").asOpt[String].getOrElse("unknown function"),
        message = (data \ "message").asOpt[String].getOrElse("--"),
        level = (data \ "level").asOpt[Int].map(r => LogLevel(r)).getOrElse(LogLevel.LogLevelDebug).toString
      )
      event.toAnalytics()
      returns(0).v.i32 = Status.StatusOK.id
    }
  }

  def getFunctions(config: WasmConfig, ctx: Option[NgCachedConfigContext])(implicit
      env: Env,
      executionContext: ExecutionContext,
      mat: Materializer
  ): Seq[HostFunctionWithAuthorization] = {
    Seq(
      HostFunctionWithAuthorization(proxyLog(), _ => true),
      HostFunctionWithAuthorization(proxyLogWithEvent(config, ctx), _ => true)
    )
  }
}

object Http extends AwaitCapable {

  def proxyHttpCall(config: WasmConfig)(implicit env: Env, executionContext: ExecutionContext, mat: Materializer) = {
    HFunction.defineContextualFunction("proxy_http_call", config, None) {
      (
          plugin: ExtismCurrentPlugin,
          params: Array[LibExtism.ExtismVal],
          returns: Array[LibExtism.ExtismVal],
          hostData: EnvUserData
      ) =>
        {
          val context = Json.parse(Utils.contextParamsToString(plugin, params: _*))

          val url          = (context \ "url").asOpt[String].getOrElse("https://mirror.otoroshi.io")
          val allowedHosts = hostData.config.allowedHosts
          val urlHost      = Uri(url).authority.host.toString()
          val allowed      = allowedHosts.isEmpty || allowedHosts.contains("*") || allowedHosts.exists(h =>
            RegexPool(h).matches(urlHost)
          )
          if (allowed) {
            val builder                  = hostData.env.Ws
              .url(url)
              .withMethod((context \ "method").asOpt[String].getOrElse("GET"))
              .withHttpHeaders((context \ "headers").asOpt[Map[String, String]].getOrElse(Map.empty).toSeq: _*)
              .withRequestTimeout(
                Duration(
                  (context \ "request_timeout").asOpt[Long].getOrElse(hostData.env.clusterConfig.worker.timeout),
                  TimeUnit.MILLISECONDS
                )
              )
              .withFollowRedirects((context \ "follow_redirects").asOpt[Boolean].getOrElse(false))
              .withQueryStringParameters((context \ "query").asOpt[Map[String, String]].getOrElse(Map.empty).toSeq: _*)
            val bodyAsBytes              = context.select("body_bytes").asOpt[Array[Byte]].map(bytes => ByteString(bytes))
            val bodyBase64               = context.select("body_base64").asOpt[String].map(str => ByteString(str).decodeBase64)
            val bodyJson                 = context.select("body_json").asOpt[JsValue].map(str => ByteString(str.stringify))
            val bodyStr                  = context
              .select("body_str")
              .asOpt[String]
              .orElse(context.select("body").asOpt[String])
              .map(str => ByteString(str))
            val body: Option[ByteString] = bodyStr.orElse(bodyJson).orElse(bodyBase64).orElse(bodyAsBytes)
            val request                  = body match {
              case Some(bytes) => builder.withBody(bytes)
              case None        => builder
            }
            val out                      = Await.result(
              request
                .execute()
                .map { res =>
                  val body                         = res.bodyAsBytes.encodeBase64.utf8String
                  val headers: Map[String, String] = res.headers.mapValues(_.head)
                  Json.obj(
                    "status"      -> res.status,
                    "headers"     -> headers,
                    "body_base64" -> body
                  )
                },
              Duration(hostData.config.authorizations.proxyHttpCallTimeout, TimeUnit.MILLISECONDS)
            )
            plugin.returnString(returns(0), Json.stringify(out))
          } else {
            plugin.returnString(
              returns(0),
              Json.stringify(
                Json.obj(
                  "status"      -> 403,
                  "headers"     -> Json.obj("content-type" -> "text/plain"),
                  "body_base64" -> ByteString(s"you cannot access host: ${urlHost}").encodeBase64.utf8String
                )
              )
            )
          }
        }
    }
  }

  def getAttributes(config: WasmConfig, attrs: Option[TypedMap])(implicit
      env: Env,
      ec: ExecutionContext,
      mat: Materializer
  ): HostFunction[EnvUserData] = {
    HFunction.defineClassicFunction(
      "proxy_get_attrs",
      config,
      LibExtism.ExtismValType.I64,
      None,
      LibExtism.ExtismValType.I64
    ) { (plugin, _, returns, hostData) =>
      attrs match {
        case None     => plugin.returnBytes(returns(0), Array.empty[Byte])
        case Some(at) => plugin.returnBytes(returns(0), at.json.stringify.byteString.toArray)
      }
    }
  }

  def getAttribute(config: WasmConfig, attrs: Option[TypedMap])(implicit
      env: Env,
      ec: ExecutionContext,
      mat: Materializer
  ): HostFunction[EnvUserData] = {
    HFunction.defineContextualFunction("proxy_get_attr", config, None) {
      (
          plugin: ExtismCurrentPlugin,
          params: Array[LibExtism.ExtismVal],
          returns: Array[LibExtism.ExtismVal],
          hostData: EnvUserData
      ) =>
        {
          attrs match {
            case None     => plugin.returnBytes(returns(0), Array.empty[Byte])
            case Some(at) =>
              val key = Utils.contextParamsToString(plugin, params: _*)
              at.json.select(key).asOpt[JsValue] match {
                case None        => plugin.returnBytes(returns(0), Array.empty[Byte])
                case Some(value) => plugin.returnBytes(returns(0), value.stringify.byteString.toArray)
              }
          }
        }
    }
  }

  def clearAttributes(config: WasmConfig, attrs: Option[TypedMap])(implicit
      env: Env,
      ec: ExecutionContext,
      mat: Materializer
  ): HostFunction[EnvUserData] = {
    HFunction.defineClassicFunction(
      "proxy_clear_attrs",
      config,
      LibExtism.ExtismValType.I64,
      None,
      LibExtism.ExtismValType.I64
    ) { (plugin, _, returns, hostData) =>
      attrs match {
        case None     => plugin.returnInt(returns(0), 0)
        case Some(at) => {
          at.clear()
          plugin.returnInt(returns(0), 0)
        }
      }
    }
  }

  private lazy val possibleAttributes: Map[String, otoroshi.plugins.AttributeSetter[_]] = Seq(
    otoroshi.plugins.AttributeSetter(
      otoroshi.next.plugins.Keys.ResponseAddHeadersKey,
      json => json.asObject.value.mapValues(_.asString).toSeq
    ),
    otoroshi.plugins.AttributeSetter(
      otoroshi.next.plugins.Keys.JwtInjectionKey,
      json => JwtInjection.fromJson(json).right.get
    ),
    otoroshi.plugins.AttributeSetter(
      otoroshi.next.plugins.Keys.PreExtractedApikeyTupleKey,
      json => ApikeyTuple.fromJson(json).right.get
    ),
    otoroshi.plugins.AttributeSetter(otoroshi.plugins.Keys.UserKey, json => PrivateAppsUser.fmt.reads(json).get),
    otoroshi.plugins.AttributeSetter(otoroshi.plugins.Keys.ApiKeyKey, json => ApiKey._fmt.reads(json).get),
    otoroshi.plugins.AttributeSetter(otoroshi.plugins.Keys.ExtraAnalyticsDataKey, json => json),
    otoroshi.plugins.AttributeSetter(otoroshi.plugins.Keys.GatewayEventExtraInfosKey, json => json),
    otoroshi.plugins.AttributeSetter(
      otoroshi.plugins.Keys.PreExtractedRequestTargetKey,
      json => Target.format.reads(json).get
    ),
    otoroshi.plugins.AttributeSetter(
      otoroshi.plugins.Keys.PreExtractedRequestTargetsKey,
      json => json.asArray.value.map(v => NgTarget.fmt.reads(v).get)
    ),
    otoroshi.plugins.AttributeSetter(otoroshi.plugins.Keys.ElCtxKey, json => json.asObject.value.mapValues(_.asString))
  )
    .map(s => (s.key.displayName, s))
    .collect { case (Some(k), s) =>
      (k, s)
    }
    .toMap

  def setAttribute(config: WasmConfig, attrs: Option[TypedMap])(implicit
      env: Env,
      ec: ExecutionContext,
      mat: Materializer
  ): HostFunction[EnvUserData] = {
    HFunction.defineContextualFunction("proxy_set_attr", config, None) {
      (
          plugin: ExtismCurrentPlugin,
          params: Array[LibExtism.ExtismVal],
          returns: Array[LibExtism.ExtismVal],
          hostData: EnvUserData
      ) =>
        {
          attrs match {
            case Some(at: ConcurrentMutableTypedMap) => {
              val context = Json.parse(Utils.contextParamsToString(plugin, params: _*))
              val key     = context.select("key").asString
              val value   = context.select("value").asValue
              try {
                possibleAttributes.get(key).foreach { setter =>
                  at.m.put(setter.key, setter.f(value))
                }
              } catch {
                case t: Throwable => t.printStackTrace()
              }
              plugin.returnInt(returns(0), 1)
            }
            case _                                   => plugin.returnInt(returns(0), 0)
          }
        }
    }
  }

  def delAttribute(config: WasmConfig, attrs: Option[TypedMap])(implicit
      env: Env,
      ec: ExecutionContext,
      mat: Materializer
  ): HostFunction[EnvUserData] = {
    HFunction.defineContextualFunction("proxy_del_attr", config, None) {
      (
          plugin: ExtismCurrentPlugin,
          params: Array[LibExtism.ExtismVal],
          returns: Array[LibExtism.ExtismVal],
          hostData: EnvUserData
      ) =>
        {
          attrs match {
            case Some(at: ConcurrentMutableTypedMap) => {
              val key = Utils.contextParamsToString(plugin, params: _*)
              at.m.keySet.find(_.displayName.contains(key)).foreach(tk => at.remove(tk))
              plugin.returnInt(returns(0), 1)
            }
            case _                                   => plugin.returnInt(returns(0), 0)
          }
        }
    }
  }

  def getFunctions(config: WasmConfig, attrs: Option[TypedMap])(implicit
      env: Env,
      executionContext: ExecutionContext,
      mat: Materializer
  ): Seq[HostFunctionWithAuthorization] = {
    Seq(
      HostFunctionWithAuthorization(proxyHttpCall(config), _.httpAccess),
      HostFunctionWithAuthorization(getAttributes(config, attrs), _ => true),
      HostFunctionWithAuthorization(getAttribute(config, attrs), _ => true),
      HostFunctionWithAuthorization(setAttribute(config, attrs), _ => true),
      HostFunctionWithAuthorization(delAttribute(config, attrs), _ => true),
      HostFunctionWithAuthorization(clearAttributes(config, attrs), _ => true)
    )
  }
}

object DataStore extends AwaitCapable {

  def proxyDataStoreAllMatching(
      pluginRestricted: Boolean = false,
      prefix: Option[String] = None,
      config: WasmConfig
  )(implicit env: Env, executionContext: ExecutionContext, mat: Materializer): HostFunction[EnvUserData] = {
    val prefixName = if (pluginRestricted) "plugin_" else ""
    HFunction.defineContextualFunction(s"proxy_${prefixName}datastore_all_matching", config, None) {
      (
          plugin: ExtismCurrentPlugin,
          params: Array[LibExtism.ExtismVal],
          returns: Array[LibExtism.ExtismVal],
          hostData: EnvUserData
      ) =>
        {
          val key    = Utils.contextParamsToString(plugin, params: _*)
          val path   = prefix.map(p => s"wasm:$p:").getOrElse("")
          val future = env.datastores.rawDataStore.allMatching(s"${hostData.env.storageRoot}:$path$key").map { values =>
            values.map(v => JsString(v.encodeBase64.utf8String))
          }
          val res    = await(future)
          plugin.returnBytes(returns(0), ByteString(JsArray(res).stringify).toArray)
        }
    }
  }

  def proxyDataStoreKeys(pluginRestricted: Boolean = false, prefix: Option[String] = None, config: WasmConfig)(implicit
      env: Env,
      executionContext: ExecutionContext,
      mat: Materializer
  ): HostFunction[EnvUserData] = {
    val prefixName = if (pluginRestricted) "plugin_" else ""
    HFunction.defineContextualFunction(s"proxy_${prefixName}datastore_keys", config, None) {
      (
          plugin: ExtismCurrentPlugin,
          params: Array[LibExtism.ExtismVal],
          returns: Array[LibExtism.ExtismVal],
          hostData: EnvUserData
      ) =>
        {
          val key    = Utils.contextParamsToString(plugin, params: _*)
          val path   = prefix.map(p => s"wasm:$p:").getOrElse("")
          val future = env.datastores.rawDataStore.keys(s"${hostData.env.storageRoot}:$path$key").map { values =>
            JsArray(values.map(JsString.apply)).stringify
          }
          val out    = await(future)
          plugin.returnString(returns(0), out)
        }
    }
  }

  def proxyDataStoreGet(pluginRestricted: Boolean = false, prefix: Option[String] = None, config: WasmConfig)(implicit
      env: Env,
      executionContext: ExecutionContext,
      mat: Materializer
  ): HostFunction[EnvUserData] = {
    val prefixName = if (pluginRestricted) "plugin_" else ""
    HFunction.defineContextualFunction(s"proxy_${prefixName}datastore_get", config, None) {
      (
          plugin: ExtismCurrentPlugin,
          params: Array[LibExtism.ExtismVal],
          returns: Array[LibExtism.ExtismVal],
          hostData: EnvUserData
      ) =>
        {
          val key    = Utils.contextParamsToString(plugin, params: _*)
          val path   = prefix.map(p => s"wasm:$p:").getOrElse("")
          val future = env.datastores.rawDataStore.get(s"${hostData.env.storageRoot}:$path$key")
          val out    = await(future)
          val bytes  = out.map(_.toArray).getOrElse(Array.empty[Byte])
          plugin.returnBytes(returns(0), bytes)
        }
    }
  }

  def proxyDataStoreExists(
      pluginRestricted: Boolean = false,
      prefix: Option[String] = None,
      config: WasmConfig
  )(implicit env: Env, executionContext: ExecutionContext, mat: Materializer): HostFunction[EnvUserData] = {
    val prefixName = if (pluginRestricted) "plugin_" else ""
    HFunction.defineContextualFunction(s"proxy_${prefixName}datastore_exists", config, None) {
      (
          plugin: ExtismCurrentPlugin,
          params: Array[LibExtism.ExtismVal],
          returns: Array[LibExtism.ExtismVal],
          hostData: EnvUserData
      ) =>
        {
          val key    = Utils.contextParamsToString(plugin, params: _*)
          val path   = prefix.map(p => s"wasm:$p:").getOrElse("")
          val future = env.datastores.rawDataStore.exists(s"${hostData.env.storageRoot}:$path$key")
          val out    = await(future)
          plugin.returnInt(returns(0), if (out) 1 else 0)
        }
    }
  }

  def proxyDataStorePttl(pluginRestricted: Boolean = false, prefix: Option[String] = None, config: WasmConfig)(implicit
      env: Env,
      executionContext: ExecutionContext,
      mat: Materializer
  ): HostFunction[EnvUserData] = {
    val prefixName = if (pluginRestricted) "plugin_" else ""
    HFunction.defineContextualFunction(s"proxy_${prefixName}datastore_pttl", config, None) {
      (
          plugin: ExtismCurrentPlugin,
          params: Array[LibExtism.ExtismVal],
          returns: Array[LibExtism.ExtismVal],
          hostData: EnvUserData
      ) =>
        {
          val key    = Utils.contextParamsToString(plugin, params: _*)
          val path   = prefix.map(p => s"wasm:$p:").getOrElse("")
          val future = env.datastores.rawDataStore.pttl(s"${hostData.env.storageRoot}:$path$key")
          returns(0).v.i64 = await(future)
        }
    }
  }

  def proxyDataStoreSetnx(pluginRestricted: Boolean = false, prefix: Option[String] = None, config: WasmConfig)(implicit
      env: Env,
      executionContext: ExecutionContext,
      mat: Materializer
  ): HostFunction[EnvUserData] = {
    val prefixName = if (pluginRestricted) "plugin_" else ""
    HFunction.defineContextualFunction(s"proxy_${prefixName}datastore_setnx", config, None) {
      (
          plugin: ExtismCurrentPlugin,
          params: Array[LibExtism.ExtismVal],
          returns: Array[LibExtism.ExtismVal],
          hostData: EnvUserData
      ) =>
        {
          val data   = Utils.contextParamsToJson(plugin, params: _*)
          val path   = prefix.map(p => s"wasm:$p:").getOrElse("")
          val key    = (data \ "key").as[String]
          val value  = (data \ "value")
            .asOpt[String]
            .map(ByteString.apply)
            .orElse(data.select("value_base64").asOpt[String].map(s => ByteString(s).decodeBase64))
            .get
          val ttl    = (data \ "ttl").asOpt[Long]
          val future = env.datastores.rawDataStore.setnx(s"${hostData.env.storageRoot}:$path$key", value, ttl)
          val out    = await(future)
          plugin.returnInt(returns(0), if (out) 1 else 0)
        }
    }
  }

  def proxyDataStoreSet(pluginRestricted: Boolean = false, prefix: Option[String] = None, config: WasmConfig)(implicit
      env: Env,
      executionContext: ExecutionContext,
      mat: Materializer
  ): HostFunction[EnvUserData] = {
    val prefixName = if (pluginRestricted) "plugin_" else ""
    HFunction.defineContextualFunction(s"proxy_${prefixName}datastore_set", config, None) {
      (
          plugin: ExtismCurrentPlugin,
          params: Array[LibExtism.ExtismVal],
          returns: Array[LibExtism.ExtismVal],
          hostData: EnvUserData
      ) =>
        {
          val data   = Utils.contextParamsToJson(plugin, params: _*)
          val path   = prefix.map(p => s"wasm:$p:").getOrElse("")
          val key    = (data \ "key").as[String]
          val value  = (data \ "value")
            .asOpt[String]
            .map(ByteString.apply)
            .orElse(data.select("value_base64").asOpt[String].map(s => ByteString(s).decodeBase64))
            .get
          val ttl    = (data \ "ttl").asOpt[Long]
          val future = env.datastores.rawDataStore.set(s"${hostData.env.storageRoot}:$path$key", value, ttl)
          val out    = await(future)
          plugin.returnInt(returns(0), if (out) 1 else 0)
        }
    }
  }

  def proxyDataStoreDel(pluginRestricted: Boolean = false, prefix: Option[String] = None, config: WasmConfig)(implicit
      env: Env,
      executionContext: ExecutionContext,
      mat: Materializer
  ): HostFunction[EnvUserData] = {
    val prefixName = if (pluginRestricted) "plugin_" else ""
    HFunction.defineContextualFunction(s"proxy_${prefixName}datastore_del", config, None) {
      (
          plugin: ExtismCurrentPlugin,
          params: Array[LibExtism.ExtismVal],
          returns: Array[LibExtism.ExtismVal],
          hostData: EnvUserData
      ) =>
        {
          val data   = Utils.contextParamsToJson(plugin, params: _*)
          val path   = prefix.map(p => s"wasm:$p:").getOrElse("")
          val future = env.datastores.rawDataStore
            .del(
              (data \ "keys").asOpt[Seq[String]].getOrElse(Seq.empty).map(r => s"${hostData.env.storageRoot}:$path$r")
            )
          val out    = await(future)
          returns(0).v.i64 = out
        }
    }
  }

  def proxyDataStoreIncrby(
      pluginRestricted: Boolean = false,
      prefix: Option[String] = None,
      config: WasmConfig
  )(implicit env: Env, executionContext: ExecutionContext, mat: Materializer): HostFunction[EnvUserData] = {
    val prefixName = if (pluginRestricted) "plugin_" else ""
    HFunction.defineContextualFunction(s"proxy_${prefixName}datastore_incrby", config, None) {
      (
          plugin: ExtismCurrentPlugin,
          params: Array[LibExtism.ExtismVal],
          returns: Array[LibExtism.ExtismVal],
          hostData: EnvUserData
      ) =>
        {
          val data   = Utils.contextParamsToJson(plugin, params: _*)
          val path   = prefix.map(p => s"wasm:$p:").getOrElse("")
          val key    = (data \ "key").as[String]
          val incr   = (data \ "incr").asOpt[String].map(_.toInt).getOrElse((data \ "incr").asOpt[Int].getOrElse(0))
          val future = env.datastores.rawDataStore.incrby(s"${hostData.env.storageRoot}:$path$key", incr)
          val out    = await(future)
          returns(0).v.i64 = out
        }
    }
  }

  def proxyDataStorePexpire(
      pluginRestricted: Boolean = false,
      prefix: Option[String] = None,
      config: WasmConfig
  )(implicit env: Env, executionContext: ExecutionContext, mat: Materializer): HostFunction[EnvUserData] = {
    val prefixName = if (pluginRestricted) "plugin_" else ""
    HFunction.defineContextualFunction(s"proxy_${prefixName}datastore_pexpire", config, None) {
      (
          plugin: ExtismCurrentPlugin,
          params: Array[LibExtism.ExtismVal],
          returns: Array[LibExtism.ExtismVal],
          hostData: EnvUserData
      ) =>
        {
          val data   = Utils.contextParamsToJson(plugin, params: _*)
          val path   = prefix.map(p => s"wasm:$p:").getOrElse("")
          val key    = (data \ "key").as[String]
          val pttl   = (data \ "pttl").asOpt[String].map(_.toInt).getOrElse((data \ "pttl").asOpt[Int].getOrElse(0))
          val future = env.datastores.rawDataStore.pexpire(s"${hostData.env.storageRoot}:$path$key", pttl)
          val out    = await(future)
          plugin.returnInt(returns(0), if (out) 1 else 0)
        }
    }
  }

  def getFunctions(config: WasmConfig, pluginId: String)(implicit
      env: Env,
      executionContext: ExecutionContext,
      mat: Materializer
  ): Seq[HostFunctionWithAuthorization] =
    Seq(
      HostFunctionWithAuthorization(proxyDataStoreKeys(config = config), _.globalDataStoreAccess.read),
      HostFunctionWithAuthorization(proxyDataStoreGet(config = config), _.globalDataStoreAccess.read),
      HostFunctionWithAuthorization(proxyDataStoreExists(config = config), _.globalDataStoreAccess.read),
      HostFunctionWithAuthorization(proxyDataStorePttl(config = config), _.globalDataStoreAccess.read),
      HostFunctionWithAuthorization(proxyDataStoreSet(config = config), _.globalDataStoreAccess.write),
      HostFunctionWithAuthorization(proxyDataStoreSetnx(config = config), _.globalDataStoreAccess.write),
      HostFunctionWithAuthorization(proxyDataStoreDel(config = config), _.globalDataStoreAccess.write),
      HostFunctionWithAuthorization(proxyDataStoreIncrby(config = config), _.globalDataStoreAccess.write),
      HostFunctionWithAuthorization(proxyDataStorePexpire(config = config), _.globalDataStoreAccess.write),
      HostFunctionWithAuthorization(proxyDataStoreAllMatching(config = config), _.globalDataStoreAccess.read),
      HostFunctionWithAuthorization(
        proxyDataStoreKeys(config = config, pluginRestricted = true, prefix = pluginId.some),
        _.pluginDataStoreAccess.read
      ),
      HostFunctionWithAuthorization(
        proxyDataStoreGet(config = config, pluginRestricted = true, prefix = pluginId.some),
        _.pluginDataStoreAccess.read
      ),
      HostFunctionWithAuthorization(
        proxyDataStoreExists(config = config, pluginRestricted = true, prefix = pluginId.some),
        _.pluginDataStoreAccess.read
      ),
      HostFunctionWithAuthorization(
        proxyDataStorePttl(config = config, pluginRestricted = true, prefix = pluginId.some),
        _.pluginDataStoreAccess.read
      ),
      HostFunctionWithAuthorization(
        proxyDataStoreAllMatching(config = config, pluginRestricted = true, prefix = pluginId.some),
        _.pluginDataStoreAccess.read
      ),
      HostFunctionWithAuthorization(
        proxyDataStoreSet(config = config, pluginRestricted = true, prefix = pluginId.some),
        _.pluginDataStoreAccess.write
      ),
      HostFunctionWithAuthorization(
        proxyDataStoreSetnx(config = config, pluginRestricted = true, prefix = pluginId.some),
        _.pluginDataStoreAccess.write
      ),
      HostFunctionWithAuthorization(
        proxyDataStoreDel(config = config, pluginRestricted = true, prefix = pluginId.some),
        _.pluginDataStoreAccess.write
      ),
      HostFunctionWithAuthorization(
        proxyDataStoreIncrby(config = config, pluginRestricted = true, prefix = pluginId.some),
        _.pluginDataStoreAccess.write
      ),
      HostFunctionWithAuthorization(
        proxyDataStorePexpire(config = config, pluginRestricted = true, prefix = pluginId.some),
        _.pluginDataStoreAccess.write
      )
    )
}

object State {

  private val cache: LegitTrieMap[String, LegitTrieMap[String, ByteString]] =
    new LegitTrieMap[String, LegitTrieMap[String, ByteString]]()

  def getClusterState(cc: ClusterConfig): JsValue = cc.json

  def getProxyState(
      config: WasmConfig
  )(implicit env: Env, executionContext: ExecutionContext, mat: Materializer): HostFunction[EnvUserData] = {
    HFunction.defineClassicFunction(
      "proxy_state",
      config,
      LibExtism.ExtismValType.I64,
      None,
      LibExtism.ExtismValType.I64
    ) { (plugin, _, returns, hostData) =>
      {
        val proxyState = hostData.env.proxyState

        val state = Json
          .obj(
            "raw_routes"          -> JsArray(proxyState.allRawRoutes().map(_.json)),
            "routes"              -> JsArray(proxyState.allRoutes().map(_.json)),
            "routeCompositions"   -> JsArray(proxyState.allRouteCompositions().map(_.json)),
            "apikeys"             -> JsArray(proxyState.allApikeys().map(_.json)),
            "ngbackends"          -> JsArray(proxyState.allBackends().map(_.json)),
            "jwtVerifiers"        -> JsArray(proxyState.allJwtVerifiers().map(_.json)),
            "certificates"        -> JsArray(proxyState.allCertificates().map(_.json)),
            "authModules"         -> JsArray(proxyState.allAuthModules().map(_.json)),
            "services"            -> JsArray(proxyState.allServices().map(_.json)),
            "teams"               -> JsArray(proxyState.allTeams().map(_.json)),
            "tenants"             -> JsArray(proxyState.allTenants().map(_.json)),
            "serviceGroups"       -> JsArray(proxyState.allServiceGroups().map(_.json)),
            "dataExporters"       -> JsArray(proxyState.allDataExporters().map(_.json)),
            "otoroshiAdmins"      -> JsArray(proxyState.allOtoroshiAdmins().map(_.json)),
            "backofficeSessions"  -> JsArray(proxyState.allBackofficeSessions().map(_.json)),
            "privateAppsSessions" -> JsArray(proxyState.allPrivateAppsSessions().map(_.json)),
            "tcpServices"         -> JsArray(proxyState.allTcpServices().map(_.json)),
            "scripts"             -> JsArray(proxyState.allScripts().map(_.json)),
            "wasmPlugins"         -> JsArray(proxyState.allWasmPlugins().map(_.json))
          )
          .stringify

        plugin.returnString(returns(0), state)
      }
    }
  }
  def proxyStateGetValue(
      config: WasmConfig
  )(implicit env: Env, executionContext: ExecutionContext, mat: Materializer): HostFunction[EnvUserData] = {
    HFunction.defineContextualFunction("proxy_state_value", config, None) { (plugin, params, returns, userData) =>
      {
        val context = Utils.contextParamsToJson(plugin, params: _*)

        val entity             = (context \ "entity").asOpt[String].getOrElse("")
        val id: Option[String] = (context \ "id").asOpt[String]

        plugin.returnString(
          returns(0),
          ((entity, id) match {
            case ("raw_routes", None)          => JsArray(userData.env.proxyState.allRawRoutes().map(_.json))
            case ("routes", None)              => JsArray(userData.env.proxyState.allRoutes().map(_.json))
            case ("routeCompositions", None)   => JsArray(userData.env.proxyState.allRouteCompositions().map(_.json))
            case ("apikeys", None)             => JsArray(userData.env.proxyState.allApikeys().map(_.json))
            case ("ngbackends", None)          => JsArray(userData.env.proxyState.allBackends().map(_.json))
            case ("jwtVerifiers", None)        => JsArray(userData.env.proxyState.allJwtVerifiers().map(_.json))
            case ("certificates", None)        => JsArray(userData.env.proxyState.allCertificates().map(_.json))
            case ("authModules", None)         => JsArray(userData.env.proxyState.allAuthModules().map(_.json))
            case ("services", None)            => JsArray(userData.env.proxyState.allServices().map(_.json))
            case ("teams", None)               => JsArray(userData.env.proxyState.allTeams().map(_.json))
            case ("tenants", None)             => JsArray(userData.env.proxyState.allTenants().map(_.json))
            case ("serviceGroups", None)       => JsArray(userData.env.proxyState.allServiceGroups().map(_.json))
            case ("dataExporters", None)       => JsArray(userData.env.proxyState.allDataExporters().map(_.json))
            case ("otoroshiAdmins", None)      => JsArray(userData.env.proxyState.allOtoroshiAdmins().map(_.json))
            case ("backofficeSessions", None)  => JsArray(userData.env.proxyState.allBackofficeSessions().map(_.json))
            case ("privateAppsSessions", None) => JsArray(userData.env.proxyState.allPrivateAppsSessions().map(_.json))
            case ("tcpServices", None)         => JsArray(userData.env.proxyState.allTcpServices().map(_.json))
            case ("scripts", None)             => JsArray(userData.env.proxyState.allScripts().map(_.json))

            case ("raw_routes", Some(key))          => userData.env.proxyState.rawRoute(key).map(_.json).getOrElse(JsNull)
            case ("routes", Some(key))              => userData.env.proxyState.route(key).map(_.json).getOrElse(JsNull)
            case ("routeCompositions", Some(key))   =>
              userData.env.proxyState.routeComposition(key).map(_.json).getOrElse(JsNull)
            case ("apikeys", Some(key))             => userData.env.proxyState.apikey(key).map(_.json).getOrElse(JsNull)
            case ("ngbackends", Some(key))          => userData.env.proxyState.backend(key).map(_.json).getOrElse(JsNull)
            case ("jwtVerifiers", Some(key))        => userData.env.proxyState.jwtVerifier(key).map(_.json).getOrElse(JsNull)
            case ("certificates", Some(key))        => userData.env.proxyState.certificate(key).map(_.json).getOrElse(JsNull)
            case ("authModules", Some(key))         => userData.env.proxyState.authModule(key).map(_.json).getOrElse(JsNull)
            case ("services", Some(key))            => userData.env.proxyState.service(key).map(_.json).getOrElse(JsNull)
            case ("teams", Some(key))               => userData.env.proxyState.team(key).map(_.json).getOrElse(JsNull)
            case ("tenants", Some(key))             => userData.env.proxyState.tenant(key).map(_.json).getOrElse(JsNull)
            case ("serviceGroups", Some(key))       => userData.env.proxyState.serviceGroup(key).map(_.json).getOrElse(JsNull)
            case ("dataExporters", Some(key))       => userData.env.proxyState.dataExporter(key).map(_.json).getOrElse(JsNull)
            case ("otoroshiAdmins", Some(key))      =>
              userData.env.proxyState.otoroshiAdmin(key).map(_.json).getOrElse(JsNull)
            case ("backofficeSessions", Some(key))  =>
              userData.env.proxyState.backofficeSession(key).map(_.json).getOrElse(JsNull)
            case ("privateAppsSessions", Some(key)) =>
              userData.env.proxyState.privateAppsSession(key).map(_.json).getOrElse(JsNull)
            case ("tcpServices", Some(key))         => userData.env.proxyState.tcpService(key).map(_.json).getOrElse(JsNull)
            case ("scripts", Some(key))             => userData.env.proxyState.script(key).map(_.json).getOrElse(JsNull)

            case (_, __) => JsNull
          }).stringify
        )
      }
    }
  }

  def getProxyConfig(
      config: WasmConfig
  )(implicit env: Env, executionContext: ExecutionContext, mat: Materializer): HostFunction[EnvUserData] = {
    HFunction.defineClassicFunction(
      "proxy_config",
      config,
      LibExtism.ExtismValType.I64,
      None,
      LibExtism.ExtismValType.I64
    ) { (plugin, _, returns, hostData) =>
      {
        val cc = hostData.env.configurationJson.stringify
        plugin.returnString(returns(0), cc)
      }
    }
  }

  def getGlobalProxyConfig(
      config: WasmConfig
  )(implicit env: Env, executionContext: ExecutionContext, mat: Materializer) = {
    HFunction.defineClassicFunction(
      "proxy_global_config",
      config,
      LibExtism.ExtismValType.I64,
      None,
      LibExtism.ExtismValType.I64
    ) { (plugin, _, returns, hostData) =>
      {
        val cc = hostData.env.datastores.globalConfigDataStore.latest().json.stringify
        plugin.returnString(returns(0), cc)
      }
    }
  }

  def getClusterState(
      config: WasmConfig
  )(implicit env: Env, executionContext: ExecutionContext, mat: Materializer): HostFunction[EnvUserData] = {
    HFunction.defineClassicFunction(
      "proxy_cluster_state",
      config,
      LibExtism.ExtismValType.I64,
      None,
      LibExtism.ExtismValType.I64
    ) { (plugin, _, returns, hostData) =>
      {
        val cc = hostData.env.clusterConfig
        plugin.returnString(returns(0), getClusterState(cc).stringify)
      }
    }
  }

  def proxyClusteStateGetValue(
      config: WasmConfig
  )(implicit env: Env, executionContext: ExecutionContext, mat: Materializer): HostFunction[EnvUserData] = {
    HFunction.defineContextualFunction("proxy_cluster_state_value", config, None) {
      (plugin, params, returns, userData) =>
        {
          val path = Utils.contextParamsToString(plugin, params: _*)

          val cc = userData.env.clusterConfig
          plugin.returnString(returns(0), JsonOperationsHelper.getValueAtPath(path, getClusterState(cc))._2.stringify)
        }
    }
  }

  def proxyGlobalMapSet(pluginRestricted: Boolean = false, pluginId: Option[String] = None)(implicit
      env: Env,
      executionContext: ExecutionContext,
      mat: Materializer
  ): HostFunction[StateUserData] = {
    HFunction.defineFunction[StateUserData](
      if (pluginRestricted) "proxy_plugin_map_set" else "proxy_global_map_set",
      StateUserData(env, executionContext, mat, cache).some,
      LibExtism.ExtismValType.I64,
      LibExtism.ExtismValType.I64,
      LibExtism.ExtismValType.I64
    ) { (plugin, params, returns, userData: Option[StateUserData]) =>
      {
        userData.map(hostData => {
          val data = Json.parse(Utils.rawBytePtrToString(plugin, params(0).v.i64, params(1).v.i32))

          val key   = (data \ "key").as[String]
          val value = (data \ "value").as[String]

          val id = pluginId.getOrElse("global")
          hostData.cache.get(id) match {
            case Some(state) =>
              state.put(key, ByteString(value))
              hostData.cache.put(id, state)
            case None        =>
              val state = new LegitTrieMap[String, ByteString]()
              state.put(key, ByteString(value))
              hostData.cache.put(id, state)
          }

          plugin.returnInt(returns(0), Status.StatusOK.id)
        })
      }
    }
  }

  def proxyGlobalMapDel(pluginRestricted: Boolean = false, pluginId: Option[String] = None)(implicit
      env: Env,
      executionContext: ExecutionContext,
      mat: Materializer
  ): HostFunction[StateUserData] = {
    HFunction.defineFunction[StateUserData](
      if (pluginRestricted) "proxy_plugin_map_del" else "proxy_global_map_del",
      StateUserData(env, executionContext, mat, cache).some,
      LibExtism.ExtismValType.I64,
      LibExtism.ExtismValType.I64,
      LibExtism.ExtismValType.I64
    ) { (plugin, params, returns, userData: Option[StateUserData]) =>
      {
        userData.map(hostData => {
          val key = Utils.rawBytePtrToString(plugin, params(0).v.i64, params(1).v.i32)
          val id  = pluginId.getOrElse("global")
          hostData.cache.get(id) match {
            case Some(state) =>
              state.remove(key)
              hostData.cache.put(id, state)
            case None        =>
              val state = new LegitTrieMap[String, ByteString]()
              state.remove(key)
              hostData.cache.put(id, state)
          }
          plugin.returnString(returns(0), Status.StatusOK.toString)
        })
      }
    }
  }

  def proxyGlobalMapGet(pluginRestricted: Boolean = false, pluginId: Option[String] = None)(implicit
      env: Env,
      executionContext: ExecutionContext,
      mat: Materializer
  ): HostFunction[StateUserData] = {
    HFunction.defineFunction[StateUserData](
      if (pluginRestricted) "proxy_plugin_map_get" else "proxy_global_map_get",
      StateUserData(env, executionContext, mat, cache).some,
      LibExtism.ExtismValType.I64,
      LibExtism.ExtismValType.I64,
      LibExtism.ExtismValType.I64
    ) { (plugin, params, returns, userData: Option[StateUserData]) =>
      {
        userData.map(hostData => {
          val key = Utils.rawBytePtrToString(plugin, params(0).v.i64, params(1).v.i32)

          val id = pluginId.getOrElse("global")

          plugin.returnBytes(
            returns(0),
            hostData.cache.get(id) match {
              case Some(state) => state.get(key).map(_.toArray).getOrElse(Array.empty[Byte])
              case None        => Array.empty[Byte]
            }
          )
        })
      }
    }
  }

  def proxyGlobalMap(pluginRestricted: Boolean = false, pluginId: Option[String] = None)(implicit
      env: Env,
      executionContext: ExecutionContext,
      mat: Materializer
  ): HostFunction[StateUserData] = {
    HFunction.defineFunction[StateUserData](
      if (pluginRestricted) "proxy_plugin_map" else "proxy_global_map",
      StateUserData(env, executionContext, mat, cache).some,
      LibExtism.ExtismValType.I64,
      LibExtism.ExtismValType.I64
    ) { (plugin, _, returns, userData: Option[StateUserData]) =>
      {
        userData.map(hostData => {
          val id = pluginId.getOrElse("global")
          plugin.returnString(
            returns(0),
            hostData.cache.get(id) match {
              case Some(state) =>
                state
                  .foldLeft(Json.obj()) { case (values, element) =>
                    values ++ Json.obj(element._1 -> element._2.encodeBase64.utf8String)
                  }
                  .stringify
              case None        => ""
            }
          )
        })
      }
    }
  }

  def getFunctions(config: WasmConfig, pluginId: String)(implicit
      env: Env,
      executionContext: ExecutionContext,
      mat: Materializer
  ): Seq[HostFunctionWithAuthorization] =
    Seq(
      HostFunctionWithAuthorization(getProxyState(config), _.proxyStateAccess),
      HostFunctionWithAuthorization(proxyStateGetValue(config), _.proxyStateAccess),
      HostFunctionWithAuthorization(getGlobalProxyConfig(config), _.proxyStateAccess),
      HostFunctionWithAuthorization(getClusterState(config), _.configurationAccess),
      HostFunctionWithAuthorization(proxyClusteStateGetValue(config), _.configurationAccess),
      HostFunctionWithAuthorization(getProxyConfig(config), _.configurationAccess),
      HostFunctionWithAuthorization(proxyGlobalMapDel(), _.globalMapAccess.write),
      HostFunctionWithAuthorization(proxyGlobalMapSet(), _.globalMapAccess.write),
      HostFunctionWithAuthorization(proxyGlobalMapGet(), _.globalMapAccess.read),
      HostFunctionWithAuthorization(proxyGlobalMap(), _.globalMapAccess.read),
      HostFunctionWithAuthorization(proxyGlobalMapDel(pluginRestricted = true, pluginId.some), _.pluginMapAccess.write),
      HostFunctionWithAuthorization(proxyGlobalMapSet(pluginRestricted = true, pluginId.some), _.pluginMapAccess.write),
      HostFunctionWithAuthorization(proxyGlobalMapGet(pluginRestricted = true, pluginId.some), _.pluginMapAccess.read),
      HostFunctionWithAuthorization(proxyGlobalMap(pluginRestricted = true, pluginId.some), _.pluginMapAccess.read)
    )
}

object HostFunctions {

  def getFunctions(config: WasmConfig, ctx: Option[NgCachedConfigContext], pluginId: String, attrs: Option[TypedMap])(
      implicit
      env: Env,
      executionContext: ExecutionContext
  ): Array[HostFunction[_ <: HostUserData]] = {

    implicit val mat = env.otoroshiMaterializer

    val functions =
      Logging.getFunctions(config, ctx) ++
      Http.getFunctions(config, attrs) ++
      State.getFunctions(config, pluginId) ++
      DataStore.getFunctions(config, pluginId) ++
      OPA.getFunctions(config, ctx)

    functions.collect {
      case func if func.authorized(config.authorizations) => func.function
    }.toArray
  }
}
