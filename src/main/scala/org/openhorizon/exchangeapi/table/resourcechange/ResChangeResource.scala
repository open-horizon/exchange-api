package org.openhorizon.exchangeapi.table.resourcechange

object ResChangeResource extends Enumeration {
  type ResChangeResource = Value
  val AGBOT: ResChangeResource.Value = Value("agbot")
  val AGBOTAGREEMENTS: ResChangeResource.Value = Value("agbotagreements")
  val AGBOTBUSINESSPOLS: ResChangeResource.Value = Value("agbotbusinesspols")
  val AGBOTMSGS: ResChangeResource.Value = Value("agbotmsgs")
  val AGBOTPATTERNS: ResChangeResource.Value = Value("agbotpatterns")
  val AGENTFILEVERSION: ResChangeResource.Value = Value("agentfileversion")
  val NODE: ResChangeResource.Value = Value("node")
  val NODEAGREEMENTS: ResChangeResource.Value = Value("nodeagreements")
  val NODEERRORS: ResChangeResource.Value = Value("nodeerrors")
  val NODEMGMTPOLSTATUS: ResChangeResource.Value = Value("nodemgmtpolstatus")
  val NODEMSGS: ResChangeResource.Value = Value("nodemsgs")
  val NODEPOLICIES: ResChangeResource.Value = Value("nodepolicies")
  val NODESERVICES_CONFIGSTATE: ResChangeResource.Value = Value("services_configstate")
  val NODESTATUS: ResChangeResource.Value = Value("nodestatus")
  val NODEGROUP: ResChangeResource.Value = Value("ha_group")

  val ORG: ResChangeResource.Value = Value("org")
  
  val MGMTPOLICY: ResChangeResource.Value = Value("mgmtpolicy")
  val PATTERN: ResChangeResource.Value = Value("pattern")
  val PATTERNKEYS: ResChangeResource.Value = Value("patternkeys")
  val POLICY: ResChangeResource.Value = Value("policy")
  
  val SERVICE: ResChangeResource.Value = Value("service")
  val SERVICEDOCKAUTHS: ResChangeResource.Value = Value("servicedockauths")
  val SERVICEKEYS: ResChangeResource.Value = Value("servicekeys")
  val SERVICEPOLICIES: ResChangeResource.Value = Value("servicepolicies")
}
