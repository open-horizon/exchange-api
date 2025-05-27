package org.openhorizon.exchangeapi.table.apikey

import java.util.UUID
import org.openhorizon.exchangeapi.table.user.UsersTQ
import slick.jdbc.PostgresProfile.api._
import slick.lifted.{Query, Rep, TableQuery}
import scala.concurrent.ExecutionContext

object ApiKeysTQ extends TableQuery(new ApiKeys(_)) { 
  
  def getByUser(user: UUID): Query[ApiKeys, ApiKeyRow, Seq] =
    this.filter(_.user === user)

  def getById(id: UUID): Query[ApiKeys, ApiKeyRow, Seq] =
    this.filter(_.id === id)

  def getByOrg(orgid: String): Query[ApiKeys, ApiKeyRow, Seq] =
    this.filter(_.orgid === orgid)
    
  def insert(apiKey: ApiKeyRow): DBIO[Int] = this += apiKey

}
