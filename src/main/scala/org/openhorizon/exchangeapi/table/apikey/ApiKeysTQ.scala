package org.openhorizon.exchangeapi.table.apikey

import slick.jdbc.PostgresProfile.api._
import slick.lifted.{TableQuery, Rep, Query}

object ApiKeysTQ extends TableQuery(new ApiKeys(_)) {
  def getByHashedKey(hash: String): Query[Rep[String], String, Seq] =
    this.filter(_.hashedKey === hash).map(_.username)

  def getByUser(username: String): Query[ApiKeys, ApiKeyRow, Seq] =
    this.filter(_.username === username)

  def getById(id: String): Query[ApiKeys, ApiKeyRow, Seq] =
    this.filter(_.id === id)

  def deleteById(id: String): Query[ApiKeys, ApiKeyRow, Seq] =
    this.filter(_.id === id)

  def insert(apiKey: ApiKeyRow): DBIO[Int] = this += apiKey

  def getByOrg(orgid: String): Query[ApiKeys, ApiKeyRow, Seq] =
  this.filter(_.orgid === orgid)
}
