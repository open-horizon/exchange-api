package org.openhorizon.exchangeapi.table.apikey
final case class ApiKeyRow(orgid: String,
                           id: String, // UUID
                           username: String,
                           description: String,
                           hashedKey: String)
{
  def toMetadata: ApiKeyMetadata = ApiKeyMetadata(id, description, username)
}