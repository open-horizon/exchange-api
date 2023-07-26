package org.openhorizon.exchangeapi.auth

import org.openhorizon.exchangeapi.auth.Access.{ADMIN, CREATE_IN_OTHER_ORGS, CREATE_ORGS, DELETE_ORG, NEVER_ALLOWED, READ_OTHER_ORGS, SET_IBM_ORG_TYPE, WRITE_OTHER_ORGS}

object AccessGroups {
  val CROSS_ORG_ACCESS: Set[Access.Value] =
    Set(CREATE_ORGS,
        DELETE_ORG,
        READ_OTHER_ORGS,
        WRITE_OTHER_ORGS,
        CREATE_IN_OTHER_ORGS,
        SET_IBM_ORG_TYPE,
        ADMIN,
        NEVER_ALLOWED)
}
