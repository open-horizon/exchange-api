package org.openhorizon.exchangeapi.auth

/** The list of access rights. */
object Access extends Enumeration {
  type Access = Value
  val READ: Access.Value = Value("READ") // these 1st 3 are generic and will be changed to specific ones below based on the identity and target
  val WRITE: Access.Value = Value("WRITE") // implies READ and includes delete
  val CREATE: Access.Value = Value("CREATE")
  val READ_MYSELF: Access.Value = Value("READ_MYSELF") // is used for users, nodes, agbots
  val WRITE_MYSELF: Access.Value = Value("WRITE_MYSELF")
  val CREATE_NODE: Access.Value = Value("CREATE_NODE") // we use WRITE_MY_NODES instead of this
  val READ_MY_NODES: Access.Value = Value("READ_MY_NODES") // when an node tries to do this it means other node owned by the same user, but i do not think this works
  val WRITE_MY_NODES: Access.Value = Value("WRITE_MY_NODES")
  val READ_ALL_NODES: Access.Value = Value("READ_ALL_NODES")
  val WRITE_ALL_NODES: Access.Value = Value("WRITE_ALL_NODES")
  val SEND_MSG_TO_NODE: Access.Value = Value("SEND_MSG_TO_NODE")
  val CREATE_AGBOT: Access.Value = Value("CREATE_AGBOT") // we use WRITE_MY_AGBOTS instead of this
  val READ_MY_AGBOTS: Access.Value = Value("READ_MY_AGBOTS") // when an agbot tries to do this it means other agbots owned by the same user
  val WRITE_MY_AGBOTS: Access.Value = Value("WRITE_MY_AGBOTS")
  val READ_ALL_AGBOTS: Access.Value = Value("READ_ALL_AGBOTS")
  val WRITE_ALL_AGBOTS: Access.Value = Value("WRITE_ALL_AGBOTS")
  val DATA_HEARTBEAT_MY_AGBOTS: Access.Value = Value("DATA_HEARTBEAT_MY_AGBOTS")
  val SEND_MSG_TO_AGBOT: Access.Value = Value("SEND_MSG_TO_AGBOT")
  val CREATE_USER: Access.Value = Value("CREATE_USER")
  val CREATE_SUPERUSER: Access.Value = Value("CREATE_SUPERUSER")
  val READ_ALL_USERS: Access.Value = Value("READ_ALL_USERS")
  val WRITE_ALL_USERS: Access.Value = Value("WRITE_ALL_USERS")
  val WRITE_SUPERUSER: Access.Value = Value("WRITE_SUPERUSER")
  //val RESET_USER_PW = Value("RESET_USER_PW")
  val READ_MY_SERVICES: Access.Value = Value("READ_MY_SERVICES")
  val WRITE_MY_SERVICES: Access.Value = Value("WRITE_MY_SERVICES")
  val READ_ALL_SERVICES: Access.Value = Value("READ_ALL_SERVICES")
  val WRITE_ALL_SERVICES: Access.Value = Value("WRITE_ALL_SERVICES")
  val CREATE_SERVICES: Access.Value = Value("CREATE_SERVICES")
  val READ_MY_PATTERNS: Access.Value = Value("READ_MY_PATTERNS")
  val WRITE_MY_PATTERNS: Access.Value = Value("WRITE_MY_PATTERNS")
  val READ_ALL_PATTERNS: Access.Value = Value("READ_ALL_PATTERNS")
  val WRITE_ALL_PATTERNS: Access.Value = Value("WRITE_ALL_PATTERNS")
  val CREATE_PATTERNS: Access.Value = Value("CREATE_PATTERNS")
  val READ_MY_BUSINESS: Access.Value = Value("READ_MY_BUSINESS")
  val WRITE_MY_BUSINESS: Access.Value = Value("WRITE_MY_BUSINESS")
  val READ_ALL_BUSINESS: Access.Value = Value("READ_ALL_BUSINESS")
  val WRITE_ALL_BUSINESS: Access.Value = Value("WRITE_ALL_BUSINESS")
  val CREATE_BUSINESS: Access.Value = Value("CREATE_BUSINESS")
  val READ_MY_MANAGEMENT_POLICY: Access.Value = Value("READ_MY_MANAGEMENT_POLICY")
  val WRITE_MY_MANAGEMENT_POLICY: Access.Value = Value("WRITE_MY_MANAGEMENT_POLICY")
  val READ_ALL_MANAGEMENT_POLICY: Access.Value = Value("READ_ALL_MANAGEMENT_POLICY")
  val WRITE_ALL_MANAGEMENT_POLICY: Access.Value = Value("WRITE_ALL_MANAGEMENT_POLICY")
  val CREATE_MANAGEMENT_POLICY: Access.Value = Value("CREATE_MANAGEMENT_POLICY")
  val READ_MY_ORG: Access.Value = Value("READ_MY_ORG")
  val WRITE_MY_ORG: Access.Value = Value("WRITE_MY_ORG")
  val SET_IBM_ORG_TYPE: Access.Value = Value("SET_IBM_ORG_TYPE")
  val STATUS: Access.Value = Value("STATUS")
  val ORGSTATUS: Access.Value = Value("ORGSTATUS")
  val UTILITIES: Access.Value = Value("UTILITIES")
  val MAXCHANGEID: Access.Value = Value("MAXCHANGEID")
  val CREATE_ORGS: Access.Value = Value("CREATE_ORGS")
  val DELETE_ORG: Access.Value = Value("DELETE_ORG") // separate from WRITE_MY_ORG or WRITE_OTHER_ORGS so we can restrict it more
  val READ_OTHER_ORGS: Access.Value = Value("READ_OTHER_ORGS")
  val READ_IBM_ORGS: Access.Value = Value("READ_IBM_ORGS")
  val WRITE_OTHER_ORGS: Access.Value = Value("WRITE_OTHER_ORGS")
  val CREATE_IN_OTHER_ORGS: Access.Value = Value("CREATE_IN_OTHER_ORGS")
  val ADMIN: Access.Value = Value("ADMIN")
  val READ_AGENT_CONFIG_MGMT: Access.Value = Value("READ_AGENT_CONFIG_MGMT")
  val WRITE_AGENT_CONFIG_MGMT: Access.Value = Value("WRITE_AGENT_CONFIG_MGMT")
  // Hub Admin Permissions
  val READ_MY_USERS: Access.Value = Value("READ_MY_USERS")
  val WRITE_MY_USERS: Access.Value = Value("WRITE_MY_USERS")
  val WRITE_ALL_ORGS: Access.Value = Value("WRITE_ALL_ORGS")
  
  val ALL_IN_ORG: Access.Value = Value("ALL_IN_ORG") // for org admin
  val ALL: Access.Value = Value("ALL")
  val NEVER_ALLOWED: Access.Value = Value("NEVER_ALLOWED") // should not be put in any role below (including root)

  val NOT_FOUND: Access.Value = Value("NOT_FOUND") // special case where the target we are trying to determine access to does not exist
}
