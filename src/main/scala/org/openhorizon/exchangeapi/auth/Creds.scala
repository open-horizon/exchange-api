package org.openhorizon.exchangeapi.auth

final case class Creds(id: String,
                       token: String) { // id and token are generic names and their values can actually be username and password
  //def isAnonymous: Boolean = (id == "" && token == "")
  //someday: maybe add an optional hint to this so when they specify creds as username/password we know to try to authenticate as a user 1st
}
