package org.openhorizon.exchangeapi.auth

// This class is separate from the one above, because when the id is for a cred, we want automatically add the org only when a different org is not there
final case class OrgAndIdCred(org: String,
                              id: String) {
  override def toString: String =
    if (org == "" || id.contains("/") || id.startsWith(Role.superUser))
      id.trim
    else
      org.trim + "/" + id.trim    // we only check for slash, because they could already have on a different org
}
