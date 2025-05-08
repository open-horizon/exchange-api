package org.openhorizon.exchangeapi.table.resourcechange

object ResChangeCategory extends Enumeration {
  type ResChangeCategory = Value
  val AGBOT: ResChangeCategory.Value = Value("agbot")
  val MGMTPOLICY: ResChangeCategory.Value = Value("mgmtpolicy")
  val NODE: ResChangeCategory.Value = Value("node")
  val ORG: ResChangeCategory.Value = Value("org")
  val PATTERN: ResChangeCategory.Value = Value("pattern")
  val POLICY: ResChangeCategory.Value = Value("policy")
  val SERVICE: ResChangeCategory.Value = Value("service")
  val NODEGROUP: ResChangeCategory.Value = Value("ha_group")
  val APIKEY: ResChangeCategory.Value = Value("apikey")
}
