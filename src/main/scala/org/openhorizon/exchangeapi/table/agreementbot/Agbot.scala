package org.openhorizon.exchangeapi.table.agreementbot

// This is the agbot table minus the key - used as the data structure to return to the REST clients
class Agbot(var token: String,
            var name: String,
            var owner: String,
            /*var patterns: List[APattern],*/
            var msgEndPoint: String,
            var lastHeartbeat: String,
            var publicKey: String) {
  def copy = new Agbot(token, name, owner, /*patterns,*/ msgEndPoint, lastHeartbeat, publicKey)
}
