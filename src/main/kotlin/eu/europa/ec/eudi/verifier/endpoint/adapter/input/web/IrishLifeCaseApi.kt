package eu.europa.ec.eudi.verifier.endpoint.adapter.input.web

import arrow.core.getOrElse
import com.eygraber.uri.Uri
import eu.europa.ec.eudi.sdjwt.DisclosuresPerClaimPath
import eu.europa.ec.eudi.sdjwt.NimbusSdJwtOps
import eu.europa.ec.eudi.sdjwt.vc.ClaimPath
import eu.europa.ec.eudi.verifier.endpoint.adapter.out.json.jsonSupport
import eu.europa.ec.eudi.verifier.endpoint.domain.ClaimsQuery
import eu.europa.ec.eudi.verifier.endpoint.domain.ClientId
import eu.europa.ec.eudi.verifier.endpoint.domain.CredentialQuery
import eu.europa.ec.eudi.verifier.endpoint.domain.CredentialQueryIds
import eu.europa.ec.eudi.verifier.endpoint.domain.CredentialSetQuery
import eu.europa.ec.eudi.verifier.endpoint.domain.Credentials
import eu.europa.ec.eudi.verifier.endpoint.domain.CredentialSets
import eu.europa.ec.eudi.verifier.endpoint.domain.DCQL
import eu.europa.ec.eudi.verifier.endpoint.domain.DCQLMetaSdJwtVcExtensions
import eu.europa.ec.eudi.verifier.endpoint.domain.Nonce
import eu.europa.ec.eudi.verifier.endpoint.domain.OpenId4VPSpec
import eu.europa.ec.eudi.verifier.endpoint.domain.QueryId
import eu.europa.ec.eudi.verifier.endpoint.domain.TransactionId
import eu.europa.ec.eudi.verifier.endpoint.port.input.GetWalletResponse
import eu.europa.ec.eudi.verifier.endpoint.port.input.InitTransaction
import eu.europa.ec.eudi.verifier.endpoint.port.input.InitTransactionResponse
import eu.europa.ec.eudi.verifier.endpoint.port.input.InitTransactionTO
import eu.europa.ec.eudi.verifier.endpoint.port.input.QueryResponse
import eu.europa.ec.eudi.verifier.endpoint.port.input.RequestUriMethodTO
import eu.europa.ec.eudi.verifier.endpoint.port.input.SdJwtVcValidationResult
import eu.europa.ec.eudi.verifier.endpoint.port.input.ValidateSdJwtVc
import eu.europa.ec.eudi.verifier.endpoint.port.input.WalletResponseTO
import jakarta.mail.internet.InternetAddress
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType.APPLICATION_JSON
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.web.reactive.function.server.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal class IrishLifeCaseApi(
    private val store: IrishLifeCaseStore,
    private val initTransaction: InitTransaction,
    private val getWalletResponse: GetWalletResponse,
    private val validateSdJwtVc: ValidateSdJwtVc,
    private val customerBaseUrl: String,
    private val pidIssuerChain: String?,
    private val emailSender: IrishLifeEmailSender,
    private val clock: Clock,
) {
    val route = coRouter {
        POST(CASES_PATH, accept(APPLICATION_JSON), ::handleCreateCase)
        GET(CASE_PATH, accept(APPLICATION_JSON), ::handleGetCase)
        POST(INVITE_PATH, accept(APPLICATION_JSON), ::handleInviteCase)
        POST(COMPLETE_PATH, accept(APPLICATION_JSON), ::handleCompleteCase)
    }

    private suspend fun handleCreateCase(request: ServerRequest): ServerResponse = try {
        val body = request.awaitBody<CreateNewBusinessCaseRequestTO>()
        val created = store.create(body, customerBaseUrl)
        ServerResponse.ok().json().bodyValueAndAwait(created.toSummary())
    } catch (t: SerializationException) {
        logger.warn("Failed to deserialize create case request", t)
        ServerResponse.badRequest().buildAndAwait()
    }

    private suspend fun handleGetCase(request: ServerRequest): ServerResponse {
        val caseId = request.caseId()
        val current = store.get(caseId) ?: return ServerResponse.notFound().buildAndAwait()

        val refreshed = refreshCaseFromWallet(current)
        return ServerResponse.ok().json().bodyValueAndAwait(refreshed.toSummary())
    }

    private suspend fun handleInviteCase(request: ServerRequest): ServerResponse {
        val caseId = request.caseId()
        val current = store.get(caseId) ?: return ServerResponse.notFound().buildAndAwait()
        val portalUrl = customerPortalUrl(current.id)
        val initRequest = newBusinessTransactionInit(portalUrl)

        val initialized = initTransaction(initRequest).getOrElse {
            logger.warn("Unable to initialize Irish Life case {}: {}", caseId, it)
            return ServerResponse.badRequest().json().bodyValueAndAwait(mapOf("error" to it.name))
        }

        val transaction = initialized as? InitTransactionResponse.JwtSecuredAuthorizationRequestTO
            ?: return ServerResponse.badRequest().json().bodyValueAndAwait(mapOf("error" to "Unexpected transaction output"))

        val walletDeepLink = authorizationRequestUri(transaction, AUTHORIZATION_REQUEST_SCHEME)
        val activeTransaction = FrontendActiveTransactionTO(
            initializedTransaction = InitializedTransactionViewTO(
                clientId = transaction.clientId,
                request = transaction.request,
                requestUri = transaction.requestUri,
                requestUriMethod = transaction.requestUriMethod?.let { it.name.lowercase() },
                transactionId = transaction.transactionId,
                authorizationRequestUri = walletDeepLink,
            ),
            initializationRequest = FrontendInitializationRequestTO(
                nonce = initRequest.nonce.orEmpty(),
                requestUriMethod = initRequest.requestUriMethod?.name?.lowercase().orEmpty(),
                dcqlQuery = jsonSupport.encodeToJsonElement(DCQL.serializer(), initRequest.dcqlQuery!!).jsonObject,
                profile = FRONTEND_PROFILE,
                authorizationRequestScheme = AUTHORIZATION_REQUEST_SCHEME,
            ),
        )

        val updated = store.update(caseId) {
            copy(
                customerPortalUrl = portalUrl,
                walletDeepLink = walletDeepLink,
                activeTransaction = activeTransaction,
                inviteEmailSent = emailSender.sendInvite(
                    to = customerEmail,
                    customerName = "$customerGivenName $customerFamilyName",
                    policyReference = policyReference,
                    customerPortalUrl = portalUrl,
                ),
                statuses = statuses + status(IrishLifeCaseStatusCode.INVITE_SENT, "Invite sent"),
                failureReason = null,
            )
        }

        return ServerResponse.ok().json().bodyValueAndAwait(updated.toSummary())
    }

    private suspend fun handleCompleteCase(request: ServerRequest): ServerResponse {
        return try {
            val caseId = request.caseId()
            val current = store.get(caseId) ?: return ServerResponse.notFound().buildAndAwait()
            val body = request.awaitBody<CompleteNewBusinessCaseRequestTO>()

            val refreshed = refreshCaseFromWallet(current)
            if (refreshed.isTerminal()) {
                ServerResponse.ok().json().bodyValueAndAwait(refreshed.toSummary())
            } else {
                val terminalStatuses = mutableListOf<IrishLifeCaseStatusEntry>()
                val completionEmailSent: Boolean
                val failureReason: String?

                if (body.success) {
                    terminalStatuses += status(IrishLifeCaseStatusCode.PROOFS_RECEIVED, "Proofs received")
                    terminalStatuses += status(IrishLifeCaseStatusCode.PROOFS_VERIFIED, "Proofs verified")
                    terminalStatuses += status(IrishLifeCaseStatusCode.PROOFS_MATCHED, "Proofs matched")
                    terminalStatuses += status(IrishLifeCaseStatusCode.AML_STATUS_LOGGED, "AML status logged")
                    completionEmailSent = emailSender.sendCompletion(
                        to = refreshed.customerEmail,
                        customerName = "${refreshed.customerGivenName} ${refreshed.customerFamilyName}",
                        policyReference = refreshed.policyReference,
                    )
                    if (completionEmailSent) {
                        terminalStatuses += status(IrishLifeCaseStatusCode.CUSTOMER_NOTIFIED, "Customer notified")
                    }
                    terminalStatuses += status(IrishLifeCaseStatusCode.COMPLETED, "New Business process completed")
                    failureReason = if (completionEmailSent) null else "Completion email was not sent by the backend."
                } else {
                    completionEmailSent = false
                    terminalStatuses += status(IrishLifeCaseStatusCode.FAILED, "Proof validation failed")
                    failureReason = body.reason ?: body.validation?.reason ?: "The presented proof failed validation."
                }

                val updated = store.update(caseId) {
                    copy(
                        statuses = statuses + terminalStatuses,
                        completionEmailSent = completionEmailSent,
                        validation = body.validation,
                        failureReason = failureReason,
                    )
                }

                ServerResponse.ok().json().bodyValueAndAwait(updated.toSummary())
            }
        } catch (t: SerializationException) {
            logger.warn("Failed to deserialize complete case request", t)
            ServerResponse.badRequest().buildAndAwait()
        }
    }

    private suspend fun refreshCaseFromWallet(caseState: IrishLifeNewBusinessCase): IrishLifeNewBusinessCase {
        if (caseState.isTerminal()) {
            return caseState
        }

        val transactionId = caseState.activeTransaction?.initializedTransaction?.transactionId ?: return caseState
        return when (val result = getWalletResponse(TransactionId(transactionId), responseCode = null)) {
            is QueryResponse.Found -> reconcileCaseWithWalletResponse(caseState, result.value)
            else -> caseState
        }
    }

    private suspend fun reconcileCaseWithWalletResponse(
        caseState: IrishLifeNewBusinessCase,
        walletResponse: WalletResponseTO,
    ): IrishLifeNewBusinessCase {
        val received = ensureStatus(caseState, IrishLifeCaseStatusCode.PROOFS_RECEIVED, "Proofs received")
        if (received.isTerminal()) {
            return received
        }

        val validation = validateWalletResponse(received, walletResponse)
        return finalizeCaseFromValidation(received, validation)
    }

    private suspend fun validateWalletResponse(
        caseState: IrishLifeNewBusinessCase,
        walletResponse: WalletResponseTO,
    ): ValidationSummaryTO {
        val token = walletResponse.firstSdJwtVcToken()
        if (token.isNullOrBlank()) {
            return ValidationSummaryTO(reason = "No PID proof was returned by the wallet.")
        }

        return when (
            val result = validateSdJwtVc(
                token,
                Nonce(caseState.activeTransaction!!.initializationRequest.nonce),
                pidIssuerChain,
            )
        ) {
            is SdJwtVcValidationResult.Valid -> {
                val (claims, disclosedClaims) = with(NimbusSdJwtOps) {
                    result.payload.sdJwt.recreateClaimsAndDisclosuresPerClaim()
                }
                buildValidationSummary(caseState, claims, disclosedClaims)
            }

            is SdJwtVcValidationResult.Invalid -> ValidationSummaryTO(
                credentialExpired = true,
                reason = result.errors.joinToString(" ") { it.description },
            )
        }
    }

    private fun buildValidationSummary(
        caseState: IrishLifeNewBusinessCase,
        claims: JsonObject,
        disclosedClaims: DisclosuresPerClaimPath,
    ): ValidationSummaryTO {
        val givenName = claims.stringValue("given_name")
        val familyName = claims.stringValue("family_name")
        val birthDate = claims.stringValue("birthdate").ifBlank { claims.stringValue("birth_date") }
        val address = claims.extractAddress()
        val expiry = claims.stringValue("date_of_expiry").ifBlank { claims.stringValue("expiry_date") }
        val credentialExpired = isExpired(expiry)

        val matchedGivenName = matches(givenName, caseState.customerGivenName)
        val matchedFamilyName = matches(familyName, caseState.customerFamilyName)
        val matchedBirthDate = birthDate == caseState.customerBirthDate
        val matchedAddress = addressMatches(address, caseState.customerAddress)
        val reasons = mutableListOf<String>()

        if (!matchedGivenName) {
            reasons += "Given name did not match the application."
        }
        if (!matchedFamilyName) {
            reasons += "Family name did not match the application."
        }
        if (!matchedBirthDate) {
            reasons += "Birth date did not match the application."
        }
        if (!matchedAddress) {
            reasons += "Address did not match the application."
        }
        if (credentialExpired) {
            reasons += "The presented PID is expired."
        }

        return ValidationSummaryTO(
            matchedGivenName = matchedGivenName,
            matchedFamilyName = matchedFamilyName,
            matchedBirthDate = matchedBirthDate,
            matchedAddress = matchedAddress,
            credentialExpired = credentialExpired,
            credentialExpiry = expiry,
            reason = reasons.joinToString(" "),
            claimsSnapshot = buildJsonObject {
                put("givenName", JsonPrimitive(givenName))
                put("familyName", JsonPrimitive(familyName))
                put("birthDate", JsonPrimitive(birthDate))
                put("address", JsonPrimitive(address))
                put("expiry", JsonPrimitive(expiry))
                put("disclosedClaimPaths", buildJsonArray {
                    disclosedClaims.keys
                        .map { it.toString() }
                        .sorted()
                        .forEach { add(JsonPrimitive(it)) }
                })
            },
        )
    }

    private fun finalizeCaseFromValidation(
        caseState: IrishLifeNewBusinessCase,
        validation: ValidationSummaryTO,
    ): IrishLifeNewBusinessCase =
        if (validation.isSuccessful()) {
            store.update(caseState.id) {
                if (isTerminal()) {
                    this
                } else {
                    val terminalStatuses = mutableListOf<IrishLifeCaseStatusEntry>()
                    if (statuses.none { it.code == IrishLifeCaseStatusCode.PROOFS_VERIFIED }) {
                        terminalStatuses += status(IrishLifeCaseStatusCode.PROOFS_VERIFIED, "Proofs verified")
                    }
                    if (statuses.none { it.code == IrishLifeCaseStatusCode.PROOFS_MATCHED }) {
                        terminalStatuses += status(IrishLifeCaseStatusCode.PROOFS_MATCHED, "Proofs matched")
                    }
                    if (statuses.none { it.code == IrishLifeCaseStatusCode.AML_STATUS_LOGGED }) {
                        terminalStatuses += status(IrishLifeCaseStatusCode.AML_STATUS_LOGGED, "AML status logged")
                    }
                    val completionEmailSent = emailSender.sendCompletion(
                        to = customerEmail,
                        customerName = "$customerGivenName $customerFamilyName",
                        policyReference = policyReference,
                    )
                    if (completionEmailSent && statuses.none { it.code == IrishLifeCaseStatusCode.CUSTOMER_NOTIFIED }) {
                        terminalStatuses += status(IrishLifeCaseStatusCode.CUSTOMER_NOTIFIED, "Customer notified")
                    }
                    if (statuses.none { it.code == IrishLifeCaseStatusCode.COMPLETED }) {
                        terminalStatuses += status(IrishLifeCaseStatusCode.COMPLETED, "New Business process completed")
                    }
                    copy(
                        statuses = statuses + terminalStatuses,
                        completionEmailSent = completionEmailSent,
                        validation = validation,
                        failureReason = if (completionEmailSent) null else "Completion email was not sent by the backend.",
                    )
                }
            }
        } else {
            logger.warn(
                "Irish Life validation failed for caseId={}, policyReference={}, transactionId={}, application={}, claimsSnapshot={}, reason={}",
                caseState.id,
                caseState.policyReference,
                caseState.activeTransaction?.initializedTransaction?.transactionId,
                mapOf(
                    "givenName" to caseState.customerGivenName,
                    "familyName" to caseState.customerFamilyName,
                    "birthDate" to caseState.customerBirthDate,
                    "address" to caseState.customerAddress,
                ),
                validation.claimsSnapshot,
                validation.reason,
            )
            store.update(caseState.id) {
                if (isTerminal()) {
                    this
                } else {
                    copy(
                        statuses = if (statuses.any { it.code == IrishLifeCaseStatusCode.FAILED }) {
                            statuses
                        } else {
                            statuses + status(IrishLifeCaseStatusCode.FAILED, "Proof validation failed")
                        },
                        completionEmailSent = false,
                        validation = validation,
                        failureReason = validation.reason ?: "The presented proof failed validation.",
                    )
                }
            }
        }

    private fun ensureStatus(
        caseState: IrishLifeNewBusinessCase,
        code: IrishLifeCaseStatusCode,
        label: String,
    ): IrishLifeNewBusinessCase =
        if (caseState.statuses.any { it.code == code }) {
            caseState
        } else {
            store.update(caseState.id) {
                copy(statuses = statuses + status(code, label))
            }
        }

    private fun customerPortalUrl(caseId: String): String =
        "${customerBaseUrl.trimEnd('/')}/irish-life/new-business/customer/$caseId"

    private fun newBusinessTransactionInit(customerPortalUrl: String): InitTransactionTO {
        val queryId = QueryId("query_pid")
        logger.info(
            "Irish Life new-business DCQL summary: issuerChainConfigured={}, queryId={}, format=dc+sd-jwt, vct=urn:eudi:pid:1, claimCount=11, credentialSets=single",
            !pidIssuerChain.isNullOrBlank(),
            queryId,
        )
        val pidQuery = CredentialQuery.sdJwtVc(
            id = queryId,
            sdJwtVcMeta = DCQLMetaSdJwtVcExtensions(vctValues = listOf("urn:eudi:pid:1")),
            claims = listOf(
                ClaimsQuery.sdJwtVc(path = ClaimPath.claim("given_name")),
                ClaimsQuery.sdJwtVc(path = ClaimPath.claim("family_name")),
                ClaimsQuery.sdJwtVc(path = ClaimPath.claim("birthdate")),
                ClaimsQuery.sdJwtVc(path = ClaimPath.claim("address").claim("formatted")),
                ClaimsQuery.sdJwtVc(path = ClaimPath.claim("address").claim("street_address")),
                ClaimsQuery.sdJwtVc(path = ClaimPath.claim("address").claim("locality")),
                ClaimsQuery.sdJwtVc(path = ClaimPath.claim("address").claim("region")),
                ClaimsQuery.sdJwtVc(path = ClaimPath.claim("address").claim("postal_code")),
                ClaimsQuery.sdJwtVc(path = ClaimPath.claim("date_of_issuance")),
                ClaimsQuery.sdJwtVc(path = ClaimPath.claim("date_of_expiry")),
                ClaimsQuery.sdJwtVc(path = ClaimPath.claim("issuing_authority")),
            ),
        )

        return InitTransactionTO(
            dcqlQuery = DCQL(
                credentials = Credentials(pidQuery),
                credentialSets = CredentialSets(
                    CredentialSetQuery(options = listOf(CredentialQueryIds(listOf(queryId))))
                ),
            ),
            nonce = UUID.randomUUID().toString(),
            requestUriMethod = RequestUriMethodTO.Get,
            redirectUriTemplate = "$customerPortalUrl?response_code={RESPONSE_CODE}",
            authorizationRequestScheme = AUTHORIZATION_REQUEST_SCHEME,
            issuerChain = pidIssuerChain,
        )
    }

    private fun authorizationRequestUri(
        initialized: InitTransactionResponse.JwtSecuredAuthorizationRequestTO,
        scheme: String,
    ): String {
        val authority = initialized.requestUri
            ?.let { requestUri -> runCatching { Uri.parse(requestUri).authority }.getOrNull() }
            ?.takeIf { it.isNotBlank() }
            ?: initialized.clientId.takeIf { !it.contains('/') }
            ?: "localhost"

        val params = mutableListOf(
            "client_id=${urlEncode(initialized.clientId)}",
            "${OpenId4VPSpec.RESPONSE_TYPE}=${urlEncode(OpenId4VPSpec.VP_TOKEN)}",
        )
        initialized.request?.let { params += "request=${urlEncode(it)}" }
        initialized.requestUri?.let { params += "request_uri=${urlEncode(it)}" }
        initialized.requestUriMethod?.let { params += "request_uri_method=${urlEncode(it.name.lowercase())}" }
        return "$scheme://$authority?${params.joinToString("&")}" 
    }

    private fun urlEncode(value: String): String = java.net.URLEncoder.encode(value, Charsets.UTF_8)

    private fun status(code: IrishLifeCaseStatusCode, label: String) = IrishLifeCaseStatusEntry(code, label, clock.instant())

    private fun ServerRequest.caseId(): String = pathVariable("caseId")

    companion object {
        const val CASES_PATH = "/ui/irish-life/new-business/cases"
        const val CASE_PATH = "/ui/irish-life/new-business/cases/{caseId}"
        const val INVITE_PATH = "/ui/irish-life/new-business/cases/{caseId}/invite"
        const val COMPLETE_PATH = "/ui/irish-life/new-business/cases/{caseId}/complete"
        const val AUTHORIZATION_REQUEST_SCHEME = "eudi-openid4vp"
        const val FRONTEND_PROFILE = "openid4vp"
        private const val PID_QUERY_ID = "query_pid"
        private val logger = LoggerFactory.getLogger(IrishLifeCaseApi::class.java)
    }
}

private fun IrishLifeNewBusinessCase.isTerminal(): Boolean =
    statuses.any { it.code == IrishLifeCaseStatusCode.COMPLETED || it.code == IrishLifeCaseStatusCode.FAILED }

private fun ValidationSummaryTO.isSuccessful(): Boolean =
    matchedGivenName != false &&
        matchedFamilyName != false &&
        matchedBirthDate != false &&
        matchedAddress != false &&
        credentialExpired != true &&
        reason.isNullOrBlank()

private fun WalletResponseTO.firstSdJwtVcToken(): String? =
    vpToken?.get("query_pid")?.jsonArray?.firstOrNull()?.let { element ->
        if (element is JsonPrimitive && element.isString) {
            element.content
        } else {
            null
        }
    }

private fun JsonObject.stringValue(key: String): String =
    this[key]?.asString().orEmpty()

private fun JsonObject.extractAddress(): String {
    val address = this["address"]
    if (address is JsonPrimitive && address.isString) {
        return address.content
    }
    if (address is JsonObject) {
        val formatted = address["formatted"]?.asString().orEmpty()
        if (formatted.isNotBlank()) {
            return formatted
        }
        return listOf(
            address["street_address"]?.asString(),
            address["locality"]?.asString(),
            address["region"]?.asString(),
            address["postal_code"]?.asString(),
        ).filterNotNull().filter { it.isNotBlank() }.joinToString(", ")
    }
    return ""
}

private fun JsonElement.asString(): String? =
    (this as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content

private fun matches(left: String, right: String): Boolean = normalize(left) == normalize(right)

private fun addressMatches(left: String, right: String): Boolean =
    normalize(left) == normalize(right) || normalizeAddressCompact(left) == normalizeAddressCompact(right)

private fun normalize(value: String): String =
    value
        .lowercase()
        .replace(Regex("[^a-z0-9\\s]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

private fun normalizeAddressCompact(value: String): String =
    value
        .lowercase()
        .replace(Regex("[^a-z0-9]"), "")

private fun isExpired(expiry: String): Boolean {
    if (expiry.isBlank()) {
        return true
    }
    return try {
        LocalDate.parse(expiry).isBefore(LocalDate.now())
    } catch (_: DateTimeParseException) {
        try {
            Instant.parse(expiry).isBefore(Instant.now())
        } catch (_: DateTimeParseException) {
            true
        }
    }
}

internal class IrishLifeCaseStore(
    private val store: ConcurrentHashMap<String, IrishLifeNewBusinessCase> = ConcurrentHashMap(),
    private val clock: Clock,
) {
    fun create(
        request: CreateNewBusinessCaseRequestTO,
        customerBaseUrl: String,
    ): IrishLifeNewBusinessCase {
        val id = UUID.randomUUID().toString()
        val policyReference = request.policyReference?.takeIf { it.isNotBlank() }
            ?: "NB-${id.take(8).uppercase()}"
        val created = IrishLifeNewBusinessCase(
            id = id,
            policyReference = policyReference,
            customerGivenName = request.customerGivenName.trim(),
            customerFamilyName = request.customerFamilyName.trim(),
            customerEmail = request.customerEmail.trim(),
            customerBirthDate = request.customerBirthDate.trim(),
            customerAddress = request.customerAddress.trim(),
            customerPortalUrl = "${customerBaseUrl.trimEnd('/')}/irish-life/new-business/customer/$id",
            statuses = listOf(
                IrishLifeCaseStatusEntry(IrishLifeCaseStatusCode.POLICY_SETUP, "New business policy set up", clock.instant()),
                IrishLifeCaseStatusEntry(IrishLifeCaseStatusCode.AML_TRIGGERED, "AML triggered", clock.instant()),
            ),
        )
        store[id] = created
        return created
    }

    fun get(id: String): IrishLifeNewBusinessCase? = store[id]

    fun update(id: String, update: IrishLifeNewBusinessCase.() -> IrishLifeNewBusinessCase): IrishLifeNewBusinessCase {
        val current = requireNotNull(store[id]) { "Case not found: $id" }
        val updated = current.update()
        store[id] = updated
        return updated
    }
}

internal class IrishLifeEmailSender(
    private val mailSender: JavaMailSender?,
    private val fromAddress: String?,
    private val smtpHost: String?,
) {
    fun sendInvite(
        to: String,
        customerName: String,
        policyReference: String,
        customerPortalUrl: String,
    ): Boolean = send(
        to = to,
        subject = "Irish Life New Business proof request",
        body = buildString {
            appendLine("Hello $customerName,")
            appendLine()
            appendLine("Irish Life needs you to share your wallet proof for New Business policy reference $policyReference.")
            appendLine("Open this secure page to continue: $customerPortalUrl")
            appendLine()
            appendLine("If you open the page on a desktop device you can scan the QR code. If you open it on mobile, you can continue directly into your wallet.")
        },
    )

    fun sendCompletion(
        to: String,
        customerName: String,
        policyReference: String,
    ): Boolean = send(
        to = to,
        subject = "Irish Life New Business proof completed",
        body = buildString {
            appendLine("Hello $customerName,")
            appendLine()
            appendLine("Your Irish Life New Business proof process for policy reference $policyReference has been completed.")
            appendLine("No further action is needed from you at this stage.")
        },
    )

    fun sendExistingBusinessInvite(
        to: String,
        customerName: String,
        claimReference: String,
        customerPortalUrl: String,
    ): Boolean = send(
        to = to,
        subject = "Irish Life Existing Business withdrawal proof request",
        body = buildString {
            appendLine("Hello $customerName,")
            appendLine()
            appendLine("Irish Life needs you to confirm your Existing Business withdrawal request for claim reference $claimReference.")
            appendLine("Open this secure page to review the request and share your wallet proof: $customerPortalUrl")
            appendLine()
            appendLine("If you open the page on a desktop device you can scan the QR code. If you open it on mobile, you can continue directly into your wallet.")
        },
    )

    fun sendExistingBusinessCompletion(
        to: String,
        customerName: String,
        claimReference: String,
    ): Boolean = send(
        to = to,
        subject = "Irish Life Existing Business withdrawal proof completed",
        body = buildString {
            appendLine("Hello $customerName,")
            appendLine()
            appendLine("Your Irish Life Existing Business withdrawal proof process for claim reference $claimReference has been completed.")
            appendLine("No further action is needed from you at this stage.")
        },
    )

    private fun send(to: String, subject: String, body: String): Boolean {
        val configuredSender = mailSender
        val configuredHost = smtpHost?.trim()
        if (configuredSender == null || fromAddress.isNullOrBlank() || configuredHost.isNullOrBlank()) {
            logger.warn("Email not sent because JavaMailSender, verifier.mail.from, or spring.mail.host is not configured")
            return false
        }

        if (isPlaceholderHost(configuredHost)) {
            logger.info("Email not sent because spring.mail.host uses placeholder value '{}'", configuredHost)
            return false
        }

        return runCatching {
            InternetAddress(fromAddress).validate()
            InternetAddress(to).validate()
            val message = SimpleMailMessage()
            message.from = fromAddress
            message.setTo(to)
            message.subject = subject
            message.text = body
            configuredSender.send(message)
        }.onFailure {
            logger.warn("Failed to send Irish Life email to {}", to, it)
        }.isSuccess
    }

    companion object {
        private val logger = LoggerFactory.getLogger(IrishLifeEmailSender::class.java)

        private fun isPlaceholderHost(host: String): Boolean {
            val normalized = host.lowercase()
            return normalized == "smtp.example.com" || normalized == "example.com" || normalized.endsWith(".example.com")
        }
    }
}

@Serializable
internal data class CreateNewBusinessCaseRequestTO(
    val policyReference: String? = null,
    val customerGivenName: String,
    val customerFamilyName: String,
    val customerEmail: String,
    val customerBirthDate: String,
    val customerAddress: String,
)

@Serializable
internal data class CompleteNewBusinessCaseRequestTO(
    val transactionId: String? = null,
    val success: Boolean,
    val reason: String? = null,
    val validation: ValidationSummaryTO? = null,
)

@Serializable
internal data class ValidationSummaryTO(
    val matchedGivenName: Boolean? = null,
    val matchedFamilyName: Boolean? = null,
    val matchedBirthDate: Boolean? = null,
    val matchedAddress: Boolean? = null,
    val credentialExpired: Boolean? = null,
    val credentialExpiry: String? = null,
    val reason: String? = null,
    val claimsSnapshot: JsonObject? = null,
)

internal data class IrishLifeNewBusinessCase(
    val id: String,
    val policyReference: String,
    val customerGivenName: String,
    val customerFamilyName: String,
    val customerEmail: String,
    val customerBirthDate: String,
    val customerAddress: String,
    val customerPortalUrl: String,
    val walletDeepLink: String? = null,
    val activeTransaction: FrontendActiveTransactionTO? = null,
    val inviteEmailSent: Boolean = false,
    val completionEmailSent: Boolean = false,
    val statuses: List<IrishLifeCaseStatusEntry>,
    val validation: ValidationSummaryTO? = null,
    val failureReason: String? = null,
) {
    fun toSummary(): NewBusinessCaseSummaryTO = NewBusinessCaseSummaryTO(
        caseId = id,
        policyReference = policyReference,
        customerGivenName = customerGivenName,
        customerFamilyName = customerFamilyName,
        customerEmail = customerEmail,
        customerBirthDate = customerBirthDate,
        customerAddress = customerAddress,
        currentStatus = statuses.last().code,
        statuses = statuses.map { it.toTO() },
        customerPortalUrl = customerPortalUrl,
        walletDeepLink = walletDeepLink,
        activeTransaction = activeTransaction,
        inviteEmailSent = inviteEmailSent,
        completionEmailSent = completionEmailSent,
        failureReason = failureReason,
        validation = validation,
    )
}

internal data class IrishLifeCaseStatusEntry(
    val code: IrishLifeCaseStatusCode,
    val label: String,
    val at: Instant,
) {
    fun toTO(): NewBusinessCaseStatusTO = NewBusinessCaseStatusTO(
        code = code,
        label = label,
        at = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(at.atOffset(ZoneOffset.UTC)),
    )
}

@Serializable
internal enum class IrishLifeCaseStatusCode {
    POLICY_SETUP,
    AML_TRIGGERED,
    INVITE_SENT,
    PROOFS_RECEIVED,
    PROOFS_VERIFIED,
    PROOFS_MATCHED,
    AML_STATUS_LOGGED,
    CUSTOMER_NOTIFIED,
    COMPLETED,
    FAILED,
}

@Serializable
internal data class NewBusinessCaseStatusTO(
    val code: IrishLifeCaseStatusCode,
    val label: String,
    val at: String,
)

@Serializable
internal data class NewBusinessCaseSummaryTO(
    val caseId: String,
    val policyReference: String,
    val customerGivenName: String,
    val customerFamilyName: String,
    val customerEmail: String,
    val customerBirthDate: String,
    val customerAddress: String,
    val currentStatus: IrishLifeCaseStatusCode,
    val statuses: List<NewBusinessCaseStatusTO>,
    val customerPortalUrl: String,
    val walletDeepLink: String? = null,
    val activeTransaction: FrontendActiveTransactionTO? = null,
    val inviteEmailSent: Boolean,
    val completionEmailSent: Boolean,
    val failureReason: String? = null,
    val validation: ValidationSummaryTO? = null,
)

@Serializable
internal data class FrontendActiveTransactionTO(
    @SerialName("initialized_transaction") val initializedTransaction: InitializedTransactionViewTO,
    @SerialName("initialization_request") val initializationRequest: FrontendInitializationRequestTO,
)

@Serializable
internal data class InitializedTransactionViewTO(
    @SerialName("client_id") val clientId: ClientId,
    val request: String? = null,
    @SerialName("request_uri") val requestUri: String? = null,
    @SerialName("request_uri_method") val requestUriMethod: String? = null,
    @SerialName("transaction_id") val transactionId: String,
    @SerialName("authorization_request_uri") val authorizationRequestUri: String,
)

@Serializable
internal data class FrontendInitializationRequestTO(
    val nonce: String,
    @SerialName("request_uri_method") val requestUriMethod: String,
    @SerialName("dcql_query") val dcqlQuery: JsonObject,
    val profile: String,
    @SerialName("authorization_request_scheme") val authorizationRequestScheme: String,
)