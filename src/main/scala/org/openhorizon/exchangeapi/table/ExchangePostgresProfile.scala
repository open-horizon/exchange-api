package org.openhorizon.exchangeapi.table

import org.json4s._
import com.github.tminglei.slickpg._
import org.json4s.native.Document
import slick.jdbc.PostgresProfile

trait ExchangePostgresProfile extends PostgresProfile
  with PgJson4sSupport
  with array.PgArrayJdbcTypes {
  /// for json support
  override val pgjson = "jsonb"
  type DOCType = org.json4s.native.Document
  override val jsonMethods: JsonMethods[Document] = org.json4s.native.JsonMethods.asInstanceOf[JsonMethods[DOCType]]

  override val api: APIExchange = new APIExchange {}

  val plainAPI = new APIExchange with Json4sJsonPlainImplicits

  ///
  trait APIExchange extends super.API with JsonImplicits {
    implicit val strListTypeMapper: DriverJdbcType[List[String]] = new SimpleArrayJdbcType[String]("text").to(_.toList)
    implicit val json4sJsonArrayTypeMapper: DriverJdbcType[List[JValue]] =
      new AdvancedArrayJdbcType[JValue](pgjson,
        (s) => utils.SimpleArrayUtils.fromString[JValue](jsonMethods.parse(_))(s).orNull,
        (v) => utils.SimpleArrayUtils.mkString[JValue](j=>jsonMethods.compact(jsonMethods.render(j)))(v)
      ).to(_.toList)
  }
}

object ExchangePostgresProfile extends ExchangePostgresProfile
