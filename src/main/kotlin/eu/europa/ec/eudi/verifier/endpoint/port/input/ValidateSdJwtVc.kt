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

import arrow.core.*
import com.nimbusds.jwt.SignedJWT
import eu.europa.ec.eudi.sdjwt.SdJwtAndKbJwt
import eu.europa.ec.eudi.sdjwt.SdJwtVerificationException
import eu.europa.ec.eudi.verifier.endpoint.adapter.out.cert.X5CShouldBe
import eu.europa.ec.eudi.verifier.endpoint.adapter.out.sdjwtvc.SdJwtVcValidationError
import eu.europa.ec.eudi.verifier.endpoint.adapter.out.sdjwtvc.SdJwtVcValidationErrorCode
import eu.europa.ec.eudi.verifier.endpoint.adapter.out.sdjwtvc.SdJwtVcValidator
import eu.europa.ec.eudi.verifier.endpoint.adapter.out.sdjwtvc.StatusCheckException
import eu.europa.ec.eudi.verifier.endpoint.adapter.out.sdjwtvc.description
import eu.europa.ec.eudi.verifier.endpoint.adapter.out.utils.getOrThrow
import eu.europa.ec.eudi.verifier.endpoint.domain.Nonce
import eu.europa.ec.eudi.verifier.endpoint.port.out.x509.ParsePemEncodedX509CertificateChain
import eu.europa.ec.eudi.verifier.endpoint.port.out.x509.x5cShouldBeTrustedOrNull
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

internal enum class SdJwtVcValidationErrorCodeTO {
    IsUnparsable,
    ContainsInvalidJwt,
    IsMissingHolderPublicKey,
    UnsupportedHolderPublicKey,
    ContainsInvalidKeyBindingJwt,
    ContainsKeyBindingJwt,
    IsMissingKeyBindingJwt,
    ContainsInvalidDisclosures,
    ContainsUnsupportedHashingAlgorithm,
    ContainsNonUniqueDigests,
    ContainsNonUniqueDisclosures,
    ContainsDisclosuresWithNoDigests,
    UnsupportedVerificationMethod,
    UnableToResolveIssuerMetadata,
    IssuerCertificateIsNotTrusted,
    UnableToLookupDID,
    UnableToDetermineVerificationMethod,
    StatusCheckFailed,
    UnexpectedError,
    InvalidIssuerChain,
}

internal data class SdJwtVcValidationErrorDetailsTO(
    val reason: SdJwtVcValidationErrorCodeTO,
    val description: String,
    val cause: Throwable?,
)

internal fun SdJwtVcValidationResult.Invalid.toJson(): JsonArray = buildJsonArray {
    errors.forEach { error ->
        addJsonObject {
            put("error", error.reason.name)
            put("description", error.description)
            error.cause?.message?.let { cause -> put("cause", cause) }
        }
    }
}

internal sealed interface SdJwtVcValidationResult {
    /**
     * Successfully validated an SD-JWT Verifiable Credential.
     */
    data class Valid(val payload: SdJwtAndKbJwt<SignedJWT>) : SdJwtVcValidationResult

    /**
     * SD-JWT Verifiable Credential validation failed.
     */
    data class Invalid(val errors: NonEmptyList<SdJwtVcValidationErrorDetailsTO>) : SdJwtVcValidationResult
}

/**
 * Validates an SD-JWT Verifiable Credential.
 */
internal class ValidateSdJwtVc(
    private val sdJwtVcValidatorFactory: (X5CShouldBe.Trusted?) -> SdJwtVcValidator,
    private val parsePemEncodedX509CertificateChain: ParsePemEncodedX509CertificateChain,
) {
    suspend operator fun invoke(
        unverified: JsonObject,
        nonce: Nonce,
        issuerChain: String?,
    ): SdJwtVcValidationResult =
        validate(unverified.left(), nonce, issuerChain)

    suspend operator fun invoke(
        unverified: String,
        nonce: Nonce,
        issuerChain: String?,
    ): SdJwtVcValidationResult =
        validate(unverified.right(), nonce, issuerChain)

    private suspend fun validate(
        unverified: Either<JsonObject, String>,
        nonce: Nonce,
        issuerChain: String?,
    ): SdJwtVcValidationResult {
        incomingLeafSummary(unverified)?.let {
            logger.info("ValidateSdJwtVc incoming leaf: {}", it)
        }

        val sdJwtVcValidator = sdJwtVcValidator(issuerChain)
            .getOrElse {
                return SdJwtVcValidationResult.Invalid(nonEmptyListOf(it.toInvalidIssuersChainSdJwtVcValidationError()))
            }

        return unverified.fold(
            ifLeft = { sdJwtVcValidator.validate(it, nonce, null) },
            ifRight = { sdJwtVcValidator.validate(it, nonce, null) },
        ).fold(
            ifLeft = { errors -> SdJwtVcValidationResult.Invalid(errors.map { it.toSdJwtVcValidationError() }) },
            ifRight = { SdJwtVcValidationResult.Valid(it) },
        )
    }

    private fun sdJwtVcValidator(issuerChain: String?): Either<Throwable, SdJwtVcValidator> = Either.catch {
        val x5cShouldBe = issuerChain
            ?.let {
                parsePemEncodedX509CertificateChain.x5cShouldBeTrustedOrNull(it).getOrThrow()
                    ?.also { trusted ->
                        logger.info(
                            "ValidateSdJwtVc configured trusted issuer chain leaf: {}",
                            trusted.rootCACertificates.first().summary(),
                        )
                    }
            }
            ?: run {
                logger.warn("ValidateSdJwtVc invoked without configured issuer chain")
                null
            }
        sdJwtVcValidatorFactory(x5cShouldBe)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ValidateSdJwtVc::class.java)
    }
}

private fun incomingLeafSummary(unverified: Either<JsonObject, String>): String? =
    unverified.fold(
        ifLeft = { json ->
            json["vp_token"]?.jsonPrimitive?.contentOrNull?.let(::signedJwtLeafSummary)
        },
        ifRight = ::signedJwtLeafSummary,
    )

private fun signedJwtLeafSummary(token: String): String? =
    Either.catch {
        val x5c = SignedJWT.parse(token.substringBefore("~")).header.toJSONObject()["x5c"] as? List<*>
        val firstCert = x5c?.firstOrNull() as? String ?: return@catch null
        val certBytes = Base64.getDecoder().decode(firstCert)
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate
        certificate.summary()
    }.getOrNull()

private fun X509Certificate.summary(): String {
    val sans = subjectAlternativeNames
        ?.joinToString(",") { entry -> "${entry[0]}:${entry[1]}" }
        ?: "none"
    val sha256 = MessageDigest.getInstance("SHA-256")
        .digest(encoded)
        .joinToString("") { "%02x".format(it) }
    return "subject=${subjectX500Principal.name}; issuer=${issuerX500Principal.name}; san=$sans; sha256=$sha256"
}

private fun Throwable.toInvalidIssuersChainSdJwtVcValidationError(): SdJwtVcValidationErrorDetailsTO =
    SdJwtVcValidationErrorDetailsTO(
        reason = SdJwtVcValidationErrorCodeTO.InvalidIssuerChain,
        description = "unable to parse Trusted Issuers certificates",
        cause = this,
    )

private fun SdJwtVcValidationError.toSdJwtVcValidationError(): SdJwtVcValidationErrorDetailsTO =
    SdJwtVcValidationErrorDetailsTO(
        reason = reason.toSdJwtVcValidationErrorCodeTO(),
        description = when (cause) {
            is SdJwtVerificationException -> cause.description
            is StatusCheckException -> cause.reason
            else -> "an unexpected error occurred${cause.message?.let { ": $it" } ?: ""}"
        },
        cause = cause.cause,
    )

private fun SdJwtVcValidationErrorCode.toSdJwtVcValidationErrorCodeTO(): SdJwtVcValidationErrorCodeTO =
    SdJwtVcValidationErrorCodeTO.valueOf(name)
