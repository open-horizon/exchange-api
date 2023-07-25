package org.openhorizon.exchangeapi.table.managementpolicy

import org.json4s.jackson.Serialization.read
import org.json4s.{DefaultFormats, Formats}
import org.openhorizon.exchangeapi.table.service.OneProperty
import slick.dbio.DBIO
import slick.jdbc.PostgresProfile.api._

final case class ManagementPolicyRow(managementPolicy: String,
                                     orgid: String,
                                     owner: String,
                                     label: String,
                                     description: String,
                                     properties: String,
                                     constraints: String,
                                     patterns: String,
                                     enabled: Boolean,
                                     lastUpdated: String,
                                     created: String,
                                     allowDowngrade: Boolean,
                                     manifest: String,
                                     start: String = "now",
                                     startWindow: Long = 0) {
  protected implicit val jsonFormats: Formats = DefaultFormats
  
  def toManagementPolicy: ManagementPolicy = {
    val prop: List[OneProperty] = if (properties != "") read[List[OneProperty]](properties) else List[OneProperty]()
    val con: List[String] = if (constraints != "") read[List[String]](constraints) else List[String]()
    val pat: List[String] = if (patterns != "") read[List[String]](patterns) else List[String]()
    
    new ManagementPolicy(new AgentUpgradePolicy(allowDowngrade = allowDowngrade, manifest = manifest), constraints = con, created = created, description = description, enabled = enabled, label = label, lastUpdated = lastUpdated, owner = owner, patterns = pat, properties = prop, start = start, startWindow = startWindow)
  }
  
  // update returns a DB action to update this row
  def update: DBIO[_] = (for {m <- ManagementPoliciesTQ if m.managementPolicy === managementPolicy} yield (m.managementPolicy, m.orgid, m.owner, m.label, m.description, m.properties, m.constraints, m.patterns, m.enabled, m.lastUpdated, m.created, m.allowDowngrade, m.manifest, m.start, m.startWindow)).update((managementPolicy, orgid, owner, label, description, properties, constraints, patterns, enabled, lastUpdated, created, allowDowngrade, manifest, start, startWindow))
  
  // insert returns a DB action to insert this row
  def insert: DBIO[_] = ManagementPoliciesTQ += this
}
