package org.openhorizon.exchangeapi.auth

final case class OrgAndId(org: String,
                          id: String) {
  override def toString: String =
    if (org == "" || id.startsWith(org + "/") || id.startsWith(Role.superUser))
      id.trim
    else
      org.trim + "/" + id.trim
}
