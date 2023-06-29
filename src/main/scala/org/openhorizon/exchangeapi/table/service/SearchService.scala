package org.openhorizon.exchangeapi.table.service

import slick.jdbc.PostgresProfile.api._

class SearchService(tag: Tag) extends Table[SearchServiceKey](tag, "search_service") {
  def architecture = column[String]("architecture", O.Default("%"))
  def domain = column[String]("domain")
  def organization = column[String]("organization")
  def session = column[String]("session")
  def version = column[String]("version", O.Default("%"))
  
  def * = (architecture, domain, organization, session, version) <> (SearchServiceKey.tupled, SearchServiceKey.unapply)
  def pk_servicesearch = primaryKey("pk_searchservice", (architecture, domain, organization, session, version))
  def idx_servicesearch_session = index("idx_searchservice_session", session, unique = false)
}
