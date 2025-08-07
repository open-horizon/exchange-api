package org.openhorizon.exchangeapi.table.apikey
import com.fasterxml.jackson.annotation.{JsonInclude, JsonProperty}

import java.time.Instant
import java.util.UUID

@JsonInclude(JsonInclude.Include.NON_NULL)
final case class ApiKeyMetadata(description: String = "",
                                id: String,
                                label: String = "",
                                lastUpdated: String,
                                @JsonInclude(JsonInclude.Include.NON_NULL)
                                owner: String = null)
{
  def this(row: ApiKeyRow,
           ownerStr: String) =
    this(description = row.description.getOrElse(""),
         id = row.id.toString,
         label = row.label.getOrElse(""),
         lastUpdated = row.modifiedAt.toString,
         owner = ownerStr)
  
  def this(tuple: (Option[String], UUID, Option[String], Instant)) = // (description, id, label, modified_at)
    this(description = tuple._1.getOrElse(""),
         id = tuple._2.toString,
         label = tuple._3.getOrElse(""),
         lastUpdated = tuple._4.toString)
}