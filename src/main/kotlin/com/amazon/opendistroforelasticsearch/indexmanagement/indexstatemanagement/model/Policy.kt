/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 *
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

/*
 * Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.amazon.opendistroforelasticsearch.indexmanagement.indexstatemanagement.model

import com.amazon.opendistroforelasticsearch.indexmanagement.opensearchapi.instant
import com.amazon.opendistroforelasticsearch.indexmanagement.opensearchapi.optionalISMTemplateField
import com.amazon.opendistroforelasticsearch.indexmanagement.opensearchapi.optionalTimeField
import com.amazon.opendistroforelasticsearch.indexmanagement.util.IndexUtils
import com.amazon.opendistroforelasticsearch.indexmanagement.indexstatemanagement.util.WITH_TYPE
import org.opensearch.common.io.stream.StreamInput
import org.opensearch.common.io.stream.StreamOutput
import org.opensearch.common.io.stream.Writeable
import org.opensearch.common.xcontent.ToXContent
import org.opensearch.common.xcontent.ToXContentObject
import org.opensearch.common.xcontent.XContentBuilder
import org.opensearch.common.xcontent.XContentParser
import org.opensearch.common.xcontent.XContentParser.Token
import org.opensearch.common.xcontent.XContentParserUtils.ensureExpectedToken
import org.opensearch.index.seqno.SequenceNumbers
import java.io.IOException
import java.time.Instant

data class Policy(
    val id: String = NO_ID,
    val seqNo: Long = SequenceNumbers.UNASSIGNED_SEQ_NO,
    val primaryTerm: Long = SequenceNumbers.UNASSIGNED_PRIMARY_TERM,
    val description: String,
    val schemaVersion: Long,
    val lastUpdatedTime: Instant,
    val errorNotification: ErrorNotification?,
    val defaultState: String,
    val states: List<State>,
    val ismTemplate: ISMTemplate? = null
) : ToXContentObject, Writeable {

    init {
        val distinctStateNames = states.map { it.name }.distinct()
        states.forEach { state ->
            state.transitions.forEach { transition ->
                require(distinctStateNames.contains(transition.stateName)) {
                    "Policy contains a transition in state=${state.name} pointing to a nonexistent state=${transition.stateName}"
                }
            }
        }
        require(distinctStateNames.size == states.size) { "Policy cannot have duplicate state names" }
        require(states.isNotEmpty()) { "Policy must contain at least one State" }
        requireNotNull(states.find { it.name == defaultState }) { "Policy must have a valid default state" }
    }

    fun toXContent(builder: XContentBuilder): XContentBuilder {
        return toXContent(builder, ToXContent.EMPTY_PARAMS)
    }

    override fun toXContent(builder: XContentBuilder, params: ToXContent.Params): XContentBuilder {
        builder.startObject()
        if (params.paramAsBoolean(WITH_TYPE, true)) builder.startObject(POLICY_TYPE)
        builder.field(POLICY_ID_FIELD, id)
            .field(DESCRIPTION_FIELD, description)
            .optionalTimeField(LAST_UPDATED_TIME_FIELD, lastUpdatedTime)
            .field(SCHEMA_VERSION_FIELD, schemaVersion)
            .field(ERROR_NOTIFICATION_FIELD, errorNotification)
            .field(DEFAULT_STATE_FIELD, defaultState)
            .field(STATES_FIELD, states.toTypedArray())
            .optionalISMTemplateField(ISM_TEMPLATE, ismTemplate)
        if (params.paramAsBoolean(WITH_TYPE, true)) builder.endObject()
        return builder.endObject()
    }

    @Throws(IOException::class)
    constructor(sin: StreamInput) : this(
        id = sin.readString(),
        seqNo = sin.readLong(),
        primaryTerm = sin.readLong(),
        description = sin.readString(),
        schemaVersion = sin.readLong(),
        lastUpdatedTime = sin.readInstant(),
        errorNotification = sin.readOptionalWriteable(::ErrorNotification),
        defaultState = sin.readString(),
        states = sin.readList(::State),
        ismTemplate = sin.readOptionalWriteable(::ISMTemplate)
    )

    @Throws(IOException::class)
    override fun writeTo(out: StreamOutput) {
        out.writeString(id)
        out.writeLong(seqNo)
        out.writeLong(primaryTerm)
        out.writeString(description)
        out.writeLong(schemaVersion)
        out.writeInstant(lastUpdatedTime)
        out.writeOptionalWriteable(errorNotification)
        out.writeString(defaultState)
        out.writeList(states)
        out.writeOptionalWriteable(ismTemplate)
    }

    companion object {
        const val POLICY_TYPE = "policy"
        const val POLICY_ID_FIELD = "policy_id"
        const val DESCRIPTION_FIELD = "description"
        const val NO_ID = ""
        const val LAST_UPDATED_TIME_FIELD = "last_updated_time"
        const val SCHEMA_VERSION_FIELD = "schema_version"
        const val ERROR_NOTIFICATION_FIELD = "error_notification"
        const val DEFAULT_STATE_FIELD = "default_state"
        const val STATES_FIELD = "states"
        const val ISM_TEMPLATE = "ism_template"

        @Suppress("ComplexMethod")
        @JvmStatic
        @JvmOverloads
        @Throws(IOException::class)
        fun parse(
            xcp: XContentParser,
            id: String = NO_ID,
            seqNo: Long = SequenceNumbers.UNASSIGNED_SEQ_NO,
            primaryTerm: Long = SequenceNumbers.UNASSIGNED_PRIMARY_TERM
        ): Policy {
            var description: String? = null
            var defaultState: String? = null
            var errorNotification: ErrorNotification? = null
            var lastUpdatedTime: Instant? = null
            var schemaVersion: Long = IndexUtils.DEFAULT_SCHEMA_VERSION
            val states: MutableList<State> = mutableListOf()
            var ismTemplate: ISMTemplate? = null

            ensureExpectedToken(Token.START_OBJECT, xcp.currentToken(), xcp)
            while (xcp.nextToken() != Token.END_OBJECT) {
                val fieldName = xcp.currentName()
                xcp.nextToken()

                when (fieldName) {
                    SCHEMA_VERSION_FIELD -> schemaVersion = xcp.longValue()
                    LAST_UPDATED_TIME_FIELD -> lastUpdatedTime = xcp.instant()
                    POLICY_ID_FIELD -> { /* do nothing as this is an internal field */ }
                    DESCRIPTION_FIELD -> description = xcp.text()
                    ERROR_NOTIFICATION_FIELD -> errorNotification = if (xcp.currentToken() == Token.VALUE_NULL) null else ErrorNotification.parse(xcp)
                    DEFAULT_STATE_FIELD -> defaultState = xcp.text()
                    STATES_FIELD -> {
                        ensureExpectedToken(Token.START_ARRAY, xcp.currentToken(), xcp)
                        while (xcp.nextToken() != Token.END_ARRAY) {
                            states.add(State.parse(xcp))
                        }
                    }
                    ISM_TEMPLATE -> ismTemplate = if (xcp.currentToken() == Token.VALUE_NULL) null else ISMTemplate.parse(xcp)
                    else -> throw IllegalArgumentException("Invalid field: [$fieldName] found in Policy.")
                }
            }

            return Policy(
                id = id,
                seqNo = seqNo,
                primaryTerm = primaryTerm,
                description = requireNotNull(description) { "$DESCRIPTION_FIELD is null" },
                schemaVersion = schemaVersion,
                lastUpdatedTime = lastUpdatedTime ?: Instant.now(),
                errorNotification = errorNotification,
                defaultState = requireNotNull(defaultState) { "$DEFAULT_STATE_FIELD is null" },
                states = states.toList(),
                ismTemplate = ismTemplate
            )
        }
    }
}
