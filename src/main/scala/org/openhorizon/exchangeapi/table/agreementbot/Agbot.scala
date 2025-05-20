package org.openhorizon.exchangeapi.table.agreementbot

// This is the agbot table minus the key - used as the data structure to return to the REST clients
case class Agbot(id: String,
                 lastHeartbeat: String,
                 msgEndPoint: String,
                 name: String,
                 orgid: String,
                 owner: String,
                 publicKey: String,
                 token: String = "***************") {
  def this(tuple: (String, String, String, String, String, String, String)) =
    this(id = tuple._1,
         lastHeartbeat = tuple._2,
         msgEndPoint = tuple._3,
         name = tuple._4,
         orgid = tuple._5,
         owner = tuple._6,
         publicKey = tuple._7)
}
