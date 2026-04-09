/*
 * Copyright (c) 2023 European Commission
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package eu.europa.ec.eudi.verifier.endpoint.port.input

import eu.europa.ec.eudi.verifier.endpoint.adapter.out.presentation.ValidateSdJwtVcOrMsoMdocVerifiablePresentation
import eu.europa.ec.eudi.verifier.endpoint.domain.*
import eu.europa.ec.eudi.verifier.endpoint.port.input.QueryResponse.*
import eu.europa.ec.eudi.verifier.endpoint.port.out.persistence.LoadPresentationById
import eu.europa.ec.eudi.verifier.endpoint.port.out.persistence.PresentationEvent
import eu.europa.ec.eudi.verifier.endpoint.port.out.persistence.PublishPresentationEvent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.time.Clock

/**
 * Represent the [WalletResponse] as returned by the wallet
 */
@Serializable
@SerialName("wallet_response")
data class WalletResponseTO(
    @SerialName(OpenId4VPSpec.VP_TOKEN) val vpToken: JsonObject? = null,
    @SerialName(RFC6749.ERROR) val error: String? = null,
    @SerialName(RFC6749.ERROR_DESCRIPTION) val errorDescription: String? = null,
    @SerialName("trust_info") val trustInfo: List<TrustInfoTO>? = null,
)

@Serializable
data class TrustInfoTO(
    @SerialName("issuer_in_trusted_list") val issuerInTrustedList: Boolean,
    @SerialName("issuer_not_expired") val issuerNotExpired: Boolean,
    @SerialName("signature_valid") val signatureValid: Boolean,
    @SerialName("validation_errors") val validationErrors: List<String>,
    @SerialName("is_fully_trusted") val isFullyTrusted: Boolean,
)

internal fun TrustInfo.toTO(): TrustInfoTO = TrustInfoTO(
    issuerInTrustedList = issuerInTrustedList,
    issuerNotExpired = issuerNotExpired,
    signatureValid = signatureValid,
    validationErrors = trustValidationErrors,
    isFullyTrusted = isFullyTrusted,
)

internal fun WalletResponse.toTO(): WalletResponseTO {
    fun VerifiablePresentation.toJsonElement(): JsonElement =
        when (this) {
            is VerifiablePresentation.Str -> JsonPrimitive(value)
            is VerifiablePresentation.Json -> value
        }

    fun VerifiablePresentations.toJsonObject(): JsonObject = buildJsonObject {
        value.forEach { (queryId, verifiablePresentations) ->
            putJsonArray(queryId.value) {
                verifiablePresentations.forEach {
                    add(it.toJsonElement())
                }
            }
        }
    }

    return when (this) {
        is WalletResponse.VpToken -> WalletResponseTO(
            vpToken = verifiablePresentations.toJsonObject(),
            trustInfo = trustInfo?.map { it.toTO() },
        )

        is WalletResponse.Error -> WalletResponseTO(
            error = value,
            errorDescription = description,
        )
    }
}

/**
 * Given a [TransactionId] and a [Nonce] returns the [WalletResponse]
 */
fun interface GetWalletResponse {
    suspend operator fun invoke(
        transactionId: TransactionId,
        responseCode: ResponseCode?,
    ): QueryResponse<WalletResponseTO>
}

class GetWalletResponseLive(
    private val clock: Clock,
    private val loadPresentationById: LoadPresentationById,
    private val publishPresentationEvent: PublishPresentationEvent,
) : GetWalletResponse {
    override suspend fun invoke(
        transactionId: TransactionId,
        responseCode: ResponseCode?,
    ): QueryResponse<WalletResponseTO> {
        return when (val presentation = loadPresentationById(transactionId)) {
            null -> NotFound
            is Presentation.Submitted ->
                when (responseCode) {
                    null -> found(presentation)
                    presentation.responseCode -> found(presentation)
                    else -> responseCodeMismatch(presentation, responseCode)
                }

            else -> invalidState(presentation)
        }
    }

    private suspend fun found(presentation: Presentation.Submitted): Found<WalletResponseTO> {
        val walletResponse = presentation.walletResponse.toTO()

        val trustInfo = ValidateSdJwtVcOrMsoMdocVerifiablePresentation.getTrustInfo(presentation.id)
        val enhancedWalletResponse = if (trustInfo.isNotEmpty()) {
            walletResponse.copy(trustInfo = trustInfo.map { it.toTO() })
        } else {
            walletResponse
        }

        ValidateSdJwtVcOrMsoMdocVerifiablePresentation.clearTrustInfo(presentation.id)

        logVerifierGotWalletResponse(presentation, enhancedWalletResponse)
        return Found(enhancedWalletResponse)
    }

    private suspend fun responseCodeMismatch(
        presentation: Presentation.Submitted,
        responseCode: ResponseCode?,
    ): InvalidState {
        fun ResponseCode?.txt() = this?.let { value } ?: "N/A"
        val cause =
            "Invalid response_code. " +
                "Expected: ${presentation.responseCode.txt()}, " +
                "Provided ${responseCode.txt()}"
        logVerifierFailedToGetWalletResponse(presentation, cause)
        return InvalidState
    }

    private suspend fun invalidState(presentation: Presentation): InvalidState {
        val cause = "Presentation should be in Submitted state but is in ${presentation.javaClass.name}"
        logVerifierFailedToGetWalletResponse(presentation, cause)
        return InvalidState
    }

    private suspend fun logVerifierGotWalletResponse(
        presentation: Presentation.Submitted,
        walletResponse: WalletResponseTO,
    ) {
        val event = PresentationEvent.VerifierGotWalletResponse(presentation.id, clock.instant(), walletResponse)
        publishPresentationEvent(event)
    }

    private suspend fun logVerifierFailedToGetWalletResponse(
        presentation: Presentation,
        cause: String,
    ) {
        val event = PresentationEvent.VerifierFailedToGetWalletResponse(presentation.id, clock.instant(), cause)
        publishPresentationEvent(event)
    }
}
