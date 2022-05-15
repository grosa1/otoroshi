package otoroshi.next.plugins

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import com.arakelian.jq.{ImmutableJqLibrary, ImmutableJqRequest}
import otoroshi.el.GlobalExpressionLanguage
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import sangria.ast.Document
import sangria.execution.{ExceptionHandler, Executor, HandledException}
import sangria.macros.LiteralGraphQLStringContext
import sangria.parser.QueryParser
import sangria.schema.{AstSchemaBuilder, FieldResolver, InstanceCheck, Schema}
import sangria.marshalling.playJson._

import scala.concurrent.duration.DurationLong
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._
import scala.util._

case class GraphQLQueryConfig(
                    url: String,
                    headers: Map[String, String] = Map.empty,
                    method: String = "POST",
                    timeout: Long = 60000L,
                    query: String = "{\n\n}",
                    responsePath: Option[String] = None,
                    responseFilter: Option[String] = None
) extends NgPluginConfig {
  def json: JsValue = GraphQLQueryConfig.format.writes(this)
}

object GraphQLQueryConfig {
  val format = new Format[GraphQLQueryConfig] {
    override def reads(json: JsValue): JsResult[GraphQLQueryConfig] = Try {
      GraphQLQueryConfig(
        url = json.select("url").asString,
        headers = json.select("headers").asOpt[Map[String, String]].getOrElse(Map.empty),
        method = json.select("method").asOpt[String].getOrElse("POST"),
        timeout = json.select("timeout").asOpt[Long].getOrElse(60000L),
        query = json.select("query").asOpt[String].getOrElse("{\n\n}"),
        responsePath = json.select("response_path").asOpt[String],
        responseFilter = json.select("response_filter").asOpt[String],
      )
    }  match {
      case Failure(ex)    => JsError(ex.getMessage())
      case Success(value) => JsSuccess(value)
    }

    override def writes(o: GraphQLQueryConfig): JsValue = Json.obj(
      "url" -> o.url,
      "headers" -> o.headers,
      "method" -> o.method,
      "query" -> o.query,
      "timeout" -> o.timeout,
      "response_path" -> o.responsePath.map(JsString.apply).getOrElse(JsNull).asValue,
      "response_filter" -> o.responsePath.map(JsString.apply).getOrElse(JsNull).asValue,
    )
  }
}

class GraphQLQuery extends NgBackendCall {

  private val library = ImmutableJqLibrary.of()

  override def useDelegates: Boolean                       = false
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = false
  override def name: String                                = "GraphQL Query"
  override def description: Option[String]                 = "This plugin can be used to call GraphQL query endpoints and expose it as a REST endpoint".some
  override def defaultConfigObject: Option[NgPluginConfig] = GraphQLQueryConfig(url = "https://some.graphql/endpoint").some

  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Integrations)
  override def steps: Seq[NgStep]                = Seq(NgStep.CallBackend)

  def applyJq(payload: JsValue, filter: String): Either[JsValue, JsValue] = {
    val request  = ImmutableJqRequest
      .builder()
      .lib(library)
      .input(payload.stringify)
      .filter(filter)
      .build()
    val response = request.execute()
    if (response.hasErrors) {
      val errors = JsArray(response.getErrors.asScala.map(err => JsString(err)))
      Json.obj("error" -> "error while transforming response body", "details" -> errors).left
    } else {
      val rawBody = response.getOutput.byteString
      Json.parse(rawBody.utf8String).right
    }
  }

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val config = ctx.cachedConfig(internalName)(GraphQLQueryConfig.format).getOrElse(GraphQLQueryConfig(url = "https://some.graphql/endpoint"))
    val query = GlobalExpressionLanguage.apply(
      value = config.query,
      req = ctx.rawRequest.some,
      service = ctx.route.legacy.some,
      apiKey = ctx.apikey,
      user = ctx.user,
      context = ctx.attrs.get(otoroshi.plugins.Keys.ElCtxKey).getOrElse(Map.empty),
      attrs = ctx.attrs,
      env = env
    )
    env.Ws.url(config.url)
      .withRequestTimeout(config.timeout.millis)
      .withMethod(config.method)
      .withHttpHeaders(config.headers.toSeq: _*)
      .withBody(Json.obj("query" -> query, "variables" -> JsNull))
      .execute()
      .map { resp =>
        if (resp.status == 200) {
          val partialBody = resp.json.atPath(config.responsePath.getOrElse("$")).asOpt[JsValue].getOrElse(JsNull)
          config.responseFilter match {
            case None => bodyResponse(200, Map("Content-Type" -> "application/json"), Source.single(partialBody.stringify.byteString))
            case Some(filter) => applyJq(partialBody, filter) match {
              case Left(error) => bodyResponse(500, Map("Content-Type" -> "application/json"), Source.single(error.stringify.byteString))
              case Right(resp) => bodyResponse(200, Map("Content-Type" -> "application/json"), Source.single(resp.stringify.byteString))
            }
          }
        } else {
          bodyResponse(resp.status, Map("Content-Type" -> resp.contentType), resp.bodyAsSource)
        }
      }
  }
}


case class GraphQLBackendConfig(
                               schema: String,
                               initialData: Option[JsValue] = None
                             ) extends NgPluginConfig {
  def json: JsValue = GraphQLBackendConfig.format.writes(this)
}

object GraphQLBackendConfig {
  val format = new Format[GraphQLBackendConfig] {
    override def reads(json: JsValue): JsResult[GraphQLBackendConfig] = Try {
      println("read")
      println(json.select("initialData"))
      println("read end")
      GraphQLBackendConfig(
        schema = json.select("schema").as[String],
        initialData = json.select("initialData").asOpt[JsObject]
      )
    }  match {
      case Failure(ex)    => JsError(ex.getMessage())
      case Success(value) => JsSuccess(value)
    }

    override def writes(o: GraphQLBackendConfig): JsValue = Json.obj(
      "schema" -> o.schema,
      "initialData" -> o.initialData.getOrElse(JsNull).as[JsValue]
    )
  }
}


class GraphQLBackend extends NgBackendCall {

  private val DEFAULT_GRAPHQL_SCHEMA = """
   type User {
     name: String!
     firstname: String!
   }
  """.stripMargin

  override def useDelegates: Boolean                       = false
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = false
  override def name: String                                = "GraphQL Backend"
  override def description: Option[String]                 = "This plugin can be used to create a GraphQL schema".some
  override def defaultConfigObject: Option[NgPluginConfig] = GraphQLBackendConfig(
    schema = DEFAULT_GRAPHQL_SCHEMA
  ).some

  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Integrations)
  override def steps: Seq[NgStep]                = Seq(NgStep.CallBackend)

  override def callBackend(ctx: NgbBackendCallContext, delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]])(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val config = ctx.cachedConfig("internalName")(GraphQLBackendConfig.format).getOrElse(GraphQLBackendConfig(schema = DEFAULT_GRAPHQL_SCHEMA))
    val query = gql"""
    {
      users {
          firstname
          name
      }
    }
  """

    val exceptionHandler = ExceptionHandler(
      onException = {
        case (marshaller, throwable) => HandledException(throwable.getMessage)
      }
    )

    QueryParser.parse(config.schema) match {
      case Failure(exception) => bodyResponse(400, Map("Content-Type" -> "application/json"), Source.single(Json.obj("error" ->  exception.getMessage).stringify.byteString)).future
      case Success(astDocument: Document) =>
        val builder = AstSchemaBuilder.resolverBased[Unit](
          InstanceCheck.field[Unit, JsValue],
          FieldResolver.defaultInput[Unit, JsValue]
        )

        val astSchema = Schema.buildFromAst(astDocument, builder.validateSchemaWithException(astDocument))

        println(config)

        Executor.execute(
          astSchema,
          query,
          root = config.initialData.map(_.as[JsObject]).getOrElse(JsObject.empty),
          exceptionHandler = exceptionHandler
        )
          .map(res => {
              val response = Json.toJson(res)

              bodyResponse(200,
                Map("Content-Type" -> "application/json"),
                Source.single(response.stringify.byteString)
              )
          })
    }
  }
}





