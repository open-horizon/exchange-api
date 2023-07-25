package org.openhorizon.exchangeapi.table.service.policy

import org.json4s.jackson.Serialization.read
import org.json4s.{DefaultFormats, Formats}
import org.openhorizon.exchangeapi.table.service.OneProperty
import slick.dbio.DBIO
import slick.jdbc.PostgresProfile.api._


final case class ServicePolicyRow(serviceId: String,
                                  label: String,
                                  description: String,
                                  properties: String,
                                  constraints: String,
                                  lastUpdated: String) {
  protected implicit val jsonFormats: Formats = DefaultFormats
  
  def toServicePolicy: ServicePolicy = {
    val prop: List[OneProperty] = if (properties != "") read[List[OneProperty]](properties) else List[OneProperty]()
    val con: List[String] = if (constraints != "") read[List[String]](constraints) else List[String]()
    ServicePolicy(label, description, prop, con, lastUpdated)
  }
  
  def upsert: DBIO[_] = ServicePolicyTQ.insertOrUpdate(this)
}
