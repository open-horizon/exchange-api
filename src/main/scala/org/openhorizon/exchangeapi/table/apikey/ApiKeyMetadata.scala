package org.openhorizon.exchangeapi.table.apikey
final case class ApiKeyMetadata(
  id: String,
  description: String,
  user: String
) {
  def this(row: ApiKeyRow) = this(
    id = row.id,
    description = row.description,
    user = row.username
  )
}