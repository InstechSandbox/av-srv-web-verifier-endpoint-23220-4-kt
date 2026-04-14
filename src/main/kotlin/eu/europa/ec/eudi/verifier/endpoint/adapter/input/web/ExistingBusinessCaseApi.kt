package eu.europa.ec.eudi.verifier.endpoint.adapter.input.web

import arrow.core.getOrElse
import com.eygraber.uri.Uri
import eu.europa.ec.eudi.sdjwt.DisclosuresPerClaimPath
import eu.europa.ec.eudi.sdjwt.NimbusSdJwtOps
import eu.europa.ec.eudi.sdjwt.vc.ClaimPath
import eu.europa.ec.eudi.verifier.endpoint.adapter.out.json.jsonSupport
import eu.europa.ec.eudi.verifier.endpoint.domain.ClaimsQuery
import eu.europa.ec.eudi.verifier.endpoint.domain.CredentialQuery
import eu.europa.ec.eudi.verifier.endpoint.domain.Credentials
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType.APPLICATION_JSON
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.awaitBody
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import org.springframework.web.reactive.function.server.buildAndAwait
import org.springframework.web.reactive.function.server.coRouter
import org.springframework.web.reactive.function.server.json
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal class ExistingBusinessCaseApi(
    private val store: ExistingBusinessCaseStore,
    private val initTransaction: InitTransaction,
    private val getWalletResponse: GetWalletResponse,
    private val validateSdJwtVc: ValidateSdJwtVc,
    private val customerBaseUrl: String,
    private val pidIssuerChain: String?,
    private val emailSender: IrishLifeEmailSender,
    private val clock: Clock,
) {
    val route = coRouter {
        GET(CASES_PATH, accept(APPLICATION_JSON), ::handleListCases)
        POST(CASES_PATH, accept(APPLICATION_JSON), ::handleCreateCase)
        GET(CASE_PATH, accept(APPLICATION_JSON), ::handleGetCase)
        POST(INVITE_PATH, accept(APPLICATION_JSON), ::handleInviteCase)
        POST(COMPLETE_PATH, accept(APPLICATION_JSON), ::handleCompleteCase)
    }

    private suspend fun handleListCases(@Suppress("UNUSED_PARAMETER") request: ServerRequest): ServerResponse {
        val refreshedCases = store.list().map { refreshCaseFromWallet(it) }
        return ServerResponse.ok().json().bodyValueAndAwait(refreshedCases.map { it.toSummary() })
    }

    private suspend fun handleCreateCase(request: ServerRequest): ServerResponse = try {
        val body = request.awaitBody<CreateExistingBusinessCaseRequestTO>()
        val demoPolicy = demoPolicy(body.policyNumber.trim())
            ?: return ServerResponse.badRequest().json().bodyValueAndAwait(
                mapOf("error" to "Only demo policy number 12345678 is supported for the Existing Business flow.")
            )

        val created = store.create(demoPolicy, customerBaseUrl)
        val initialized = initializeCase(created, sendEmail = false)
        ServerResponse.ok().json().bodyValueAndAwait(initialized.toSummary())
    } catch (t: SerializationException) {
        logger.warn("Failed to deserialize create existing business case request", t)
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
        val updated = initializeCase(current, sendEmail = true)
        return ServerResponse.ok().json().bodyValueAndAwait(updated.toSummary())
    }

    private suspend fun handleCompleteCase(request: ServerRequest): ServerResponse = try {
        val caseId = request.caseId()
        val current = store.get(caseId) ?: return ServerResponse.notFound().buildAndAwait()
        val body = request.awaitBody<CompleteExistingBusinessCaseRequestTO>()

        val refreshed = refreshCaseFromWallet(current)
        val updated = if (refreshed.isTerminal()) {
            refreshed
        } else {
            finalizeCase(
                caseState = refreshed,
                success = body.success,
                validation = body.validation,
                fallbackReason = body.reason,
            )
        }

        ServerResponse.ok().json().bodyValueAndAwait(updated.toSummary())
    } catch (t: SerializationException) {
        logger.warn("Failed to deserialize complete existing business case request", t)
        ServerResponse.badRequest().buildAndAwait()
    }

    private suspend fun initializeCase(
        caseState: ExistingBusinessCase,
        sendEmail: Boolean,
    ): ExistingBusinessCase {
        val portalUrl = customerPortalUrl(caseState.id)
        val initRequest = existingBusinessTransactionInit(portalUrl)

        val initialized = initTransaction(initRequest).getOrElse {
            logger.warn("Unable to initialize Irish Life existing business case {}: {}", caseState.id, it)
            return markInitializationFailed(
                caseState = caseState,
                reason = "Unable to initialize wallet proof request: ${it.name}",
            )
        }

        val transaction = initialized as? InitTransactionResponse.JwtSecuredAuthorizationRequestTO
            ?: return markInitializationFailed(
                caseState = caseState,
                reason = "Unexpected wallet request output for Existing Business flow.",
            )

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

        return store.update(caseState.id) {
            val inviteEmailSent = if (sendEmail) {
                emailSender.sendExistingBusinessInvite(
                    to = customerEmail,
                    customerName = "$customerGivenName $customerFamilyName",
                    claimReference = claimReference,
                    customerPortalUrl = portalUrl,
                )
            } else {
                inviteEmailSent
            }

            copy(
                customerPortalUrl = portalUrl,
                walletDeepLink = walletDeepLink,
                activeTransaction = activeTransaction,
                inviteEmailSent = inviteEmailSent,
                statuses = statuses.addStatus(
                    ExistingBusinessCaseStatusCode.PROOF_INVITE_SENT,
                    "Wallet proof request started",
                    clock,
                ),
                notifications = notifications.addNotification(
                    ExistingBusinessNotificationCode.PROOF_REQUESTED_ALERT,
                    "Proof requested",
                    "The customer withdrawal request was accepted and the wallet proof step started automatically.",
                    clock,
                ),
                failureReason = null,
            )
        }
    }

    private fun markInitializationFailed(
        caseState: ExistingBusinessCase,
        reason: String,
    ): ExistingBusinessCase = store.update(caseState.id) {
        copy(
            statuses = statuses.addStatus(
                ExistingBusinessCaseStatusCode.FAILED,
                "Wallet proof request failed",
                clock,
            ),
            notifications = notifications.addNotification(
                ExistingBusinessNotificationCode.MANUAL_REVIEW_ALERT,
                "Manual review needed",
                reason,
                clock,
            ),
            failureReason = reason,
        )
    }

    private suspend fun refreshCaseFromWallet(caseState: ExistingBusinessCase): ExistingBusinessCase {
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
        caseState: ExistingBusinessCase,
        walletResponse: WalletResponseTO,
    ): ExistingBusinessCase {
        val received = ensureProofsReceived(caseState)
        if (received.isTerminal()) {
            return received
        }

        val validation = validateWalletResponse(received, walletResponse)
        return finalizeCase(
            caseState = received,
            success = validation.isSuccessful(),
            validation = validation,
            fallbackReason = validation.reason,
        )
    }

    private suspend fun validateWalletResponse(
        caseState: ExistingBusinessCase,
        walletResponse: WalletResponseTO,
    ): ExistingBusinessValidationSummaryTO {
        val token = walletResponse.firstExistingBusinessSdJwtVcToken()
        if (token.isNullOrBlank()) {
            return ExistingBusinessValidationSummaryTO(reason = "No PID proof was returned by the wallet.")
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

            is SdJwtVcValidationResult.Invalid -> ExistingBusinessValidationSummaryTO(
                credentialExpired = true,
                reason = result.errors.joinToString(" ") { it.description },
            )
        }
    }

    private fun buildValidationSummary(
        caseState: ExistingBusinessCase,
        claims: JsonObject,
        disclosedClaims: DisclosuresPerClaimPath,
    ): ExistingBusinessValidationSummaryTO {
        val givenName = claims.existingBusinessStringValue("given_name")
        val familyName = claims.existingBusinessStringValue("family_name")
        val birthDate = claims.existingBusinessStringValue("birthdate").ifBlank {
            claims.existingBusinessStringValue("birth_date")
        }
        val address = claims.extractExistingBusinessAddress()
        val expiry = claims.existingBusinessStringValue("date_of_expiry").ifBlank {
            claims.existingBusinessStringValue("expiry_date")
        }
        val credentialExpired = isExistingBusinessExpired(expiry)

        val matchedGivenName = existingBusinessMatches(givenName, caseState.customerGivenName)
        val matchedFamilyName = existingBusinessMatches(familyName, caseState.customerFamilyName)
        val matchedBirthDate = birthDate == caseState.customerBirthDate
        val matchedAddress = existingBusinessAddressMatches(address, caseState.customerAddress)
        val reasons = mutableListOf<String>()

        if (!matchedGivenName) {
            reasons += "Given name did not match the Irish Life policy record."
        }
        if (!matchedFamilyName) {
            reasons += "Family name did not match the Irish Life policy record."
        }
        if (!matchedBirthDate) {
            reasons += "Birth date did not match the Irish Life policy record."
        }
        if (!matchedAddress) {
            reasons += "Address did not match the Irish Life policy record."
        }
        if (credentialExpired) {
            reasons += "The presented PID is expired."
        }

        return ExistingBusinessValidationSummaryTO(
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

    private fun finalizeCase(
        caseState: ExistingBusinessCase,
        success: Boolean,
        validation: ExistingBusinessValidationSummaryTO?,
        fallbackReason: String?,
    ): ExistingBusinessCase =
        if (success) {
            store.update(caseState.id) {
                if (isTerminal()) {
                    this
                } else {
                    copy(
                        statuses = statuses
                            .addStatus(ExistingBusinessCaseStatusCode.PROOFS_VERIFIED, "Proofs verified", clock)
                            .addStatus(ExistingBusinessCaseStatusCode.POLICY_APPLICATION_MATCHED, "Policy application matched", clock)
                            .addStatus(ExistingBusinessCaseStatusCode.AML_RECORD_NOT_FOUND, "AML record not found", clock)
                            .addStatus(ExistingBusinessCaseStatusCode.AUTOMATED_DECISION_RECORDED, "Automated decision recorded", clock)
                            .addStatus(ExistingBusinessCaseStatusCode.COMPLETED, "Existing Business claim completed", clock),
                        notifications = notifications
                            .addNotification(
                                ExistingBusinessNotificationCode.PROOFS_READY_ALERT,
                                "Proofs verified",
                                "The customer PID proof matched the Irish Life policy record for this withdrawal request.",
                                clock,
                            )
                            .addNotification(
                                ExistingBusinessNotificationCode.AML_CHECK_COMPLETED_ALERT,
                                "AML check completed",
                                "Dummy AML lookup completed with no matching prior AML record found for this policy.",
                                clock,
                            )
                            .addNotification(
                                ExistingBusinessNotificationCode.DECISION_READY_ALERT,
                                "Decision ready",
                                "Automated checks completed and the withdrawal was approved for release.",
                                clock,
                            ),
                        completionEmailSent = false,
                        amlRecordFound = false,
                        policyApplicationMatched = caseState.policyNumber == SUPPORTED_POLICY_NUMBER,
                        automatedDecision = "Approved for automated withdrawal release",
                        validation = validation,
                        failureReason = null,
                    )
                }
            }
        } else {
            store.update(caseState.id) {
                if (isTerminal()) {
                    this
                } else {
                    val reason = fallbackReason ?: validation?.reason ?: "The presented proof failed validation."
                    copy(
                        statuses = statuses.addStatus(ExistingBusinessCaseStatusCode.FAILED, "Proof validation failed", clock),
                        notifications = notifications.addNotification(
                            ExistingBusinessNotificationCode.MANUAL_REVIEW_ALERT,
                            "Manual review needed",
                            reason,
                            clock,
                        ),
                        completionEmailSent = false,
                        validation = validation,
                        failureReason = reason,
                    )
                }
            }
        }

    private fun ensureProofsReceived(caseState: ExistingBusinessCase): ExistingBusinessCase =
        if (caseState.statuses.any { it.code == ExistingBusinessCaseStatusCode.PROOFS_RECEIVED }) {
            caseState
        } else {
            store.update(caseState.id) {
                copy(
                    statuses = statuses.addStatus(ExistingBusinessCaseStatusCode.PROOFS_RECEIVED, "Proofs received", clock),
                    notifications = notifications.addNotification(
                        ExistingBusinessNotificationCode.PROOFS_SUBMITTED_ALERT,
                        "Proof submitted",
                        "The customer submitted wallet proof and the verifier is now validating the disclosed claims.",
                        clock,
                    ),
                )
            }
        }

    private fun customerPortalUrl(caseId: String): String =
        "${customerBaseUrl.trimEnd('/')}/irish-life/existing-business/customer/$caseId"

    private fun existingBusinessTransactionInit(customerPortalUrl: String): InitTransactionTO {
        val queryId = QueryId(PID_QUERY_ID)
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
            dcqlQuery = DCQL(credentials = Credentials(pidQuery)),
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

    private fun ServerRequest.caseId(): String = pathVariable("caseId")

    private fun demoPolicy(policyNumber: String): DemoExistingBusinessPolicyRecord? =
        DEMO_POLICY_RECORDS[policyNumber]

    companion object {
        const val CASES_PATH = "/ui/irish-life/existing-business/cases"
        const val CASE_PATH = "/ui/irish-life/existing-business/cases/{caseId}"
        const val INVITE_PATH = "/ui/irish-life/existing-business/cases/{caseId}/invite"
        const val COMPLETE_PATH = "/ui/irish-life/existing-business/cases/{caseId}/complete"
        const val AUTHORIZATION_REQUEST_SCHEME = "eudi-openid4vp"
        const val FRONTEND_PROFILE = "openid4vp"
        private const val PID_QUERY_ID = "query_pid"
        private const val SUPPORTED_POLICY_NUMBER = "12345678"
        private val DEMO_POLICY_RECORDS = mapOf(
            SUPPORTED_POLICY_NUMBER to DemoExistingBusinessPolicyRecord(
                policyNumber = SUPPORTED_POLICY_NUMBER,
                productName = "Savings & Investments withdrawal",
                withdrawalAmount = "EUR 25,000",
                bankAccountLastFour = "6789",
                customerGivenName = "Patrick",
                customerFamilyName = "Murphy",
                customerEmail = "patrick.murphy@example.com",
                customerBirthDate = "1980-04-12",
                customerAddress = "1 Main Street, Dublin, Leinster, D02 XY56",
            ),
        )
        private val logger = LoggerFactory.getLogger(ExistingBusinessCaseApi::class.java)
    }
}

private fun ExistingBusinessCase.isTerminal(): Boolean =
    statuses.any {
        it.code == ExistingBusinessCaseStatusCode.COMPLETED ||
            it.code == ExistingBusinessCaseStatusCode.FAILED
    }

private fun List<ExistingBusinessCaseStatusEntry>.addStatus(
    code: ExistingBusinessCaseStatusCode,
    label: String,
    clock: Clock,
): List<ExistingBusinessCaseStatusEntry> =
    if (any { it.code == code }) {
        this
    } else {
        this + ExistingBusinessCaseStatusEntry(code, label, clock.instant())
    }

private fun List<ExistingBusinessNotificationEntry>.addNotification(
    code: ExistingBusinessNotificationCode,
    label: String,
    message: String,
    clock: Clock,
): List<ExistingBusinessNotificationEntry> =
    if (any { it.code == code }) {
        this
    } else {
        this + ExistingBusinessNotificationEntry(code, label, message, clock.instant())
    }

private fun ExistingBusinessValidationSummaryTO.isSuccessful(): Boolean =
    matchedGivenName != false &&
        matchedFamilyName != false &&
        matchedBirthDate != false &&
        matchedAddress != false &&
        credentialExpired != true &&
        reason.isNullOrBlank()

private fun WalletResponseTO.firstExistingBusinessSdJwtVcToken(): String? =
    vpToken?.get("query_pid")?.jsonArray?.firstOrNull()?.let { element ->
        if (element is JsonPrimitive && element.isString) {
            element.content
        } else {
            null
        }
    }

private fun JsonObject.existingBusinessStringValue(key: String): String =
    this[key]?.existingBusinessAsString().orEmpty()

private fun JsonObject.extractExistingBusinessAddress(): String {
    val address = this["address"]
    if (address is JsonPrimitive && address.isString) {
        return address.content
    }
    if (address is JsonObject) {
        val formatted = address["formatted"]?.existingBusinessAsString().orEmpty()
        if (formatted.isNotBlank()) {
            return formatted
        }
        return listOf(
            address["street_address"]?.existingBusinessAsString(),
            address["locality"]?.existingBusinessAsString(),
            address["region"]?.existingBusinessAsString(),
            address["postal_code"]?.existingBusinessAsString(),
        ).filterNotNull().filter { it.isNotBlank() }.joinToString(", ")
    }
    return ""
}

private fun JsonElement.existingBusinessAsString(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun existingBusinessMatches(left: String, right: String): Boolean =
    existingBusinessNormalize(left) == existingBusinessNormalize(right)

private fun existingBusinessAddressMatches(left: String, right: String): Boolean =
    existingBusinessNormalize(left) == existingBusinessNormalize(right) ||
        existingBusinessNormalizeCompact(left) == existingBusinessNormalizeCompact(right)

private fun existingBusinessNormalize(value: String): String =
    value.lowercase().replace(Regex("[^a-z0-9\\s]"), " ").replace(Regex("\\s+"), " ").trim()

private fun existingBusinessNormalizeCompact(value: String): String =
    value.lowercase().replace(Regex("[^a-z0-9]"), "")

private fun isExistingBusinessExpired(expiry: String): Boolean {
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

internal class ExistingBusinessCaseStore(
    private val store: ConcurrentHashMap<String, ExistingBusinessCase> = ConcurrentHashMap(),
    private val clock: Clock,
) {
    fun create(
        policy: DemoExistingBusinessPolicyRecord,
        customerBaseUrl: String,
    ): ExistingBusinessCase {
        val id = UUID.randomUUID().toString()
        val claimReference = "CLM-${id.take(8).uppercase()}"
        val requestTime = clock.instant()
        val created = ExistingBusinessCase(
            id = id,
            policyNumber = policy.policyNumber,
            claimReference = claimReference,
            productName = policy.productName,
            withdrawalAmount = policy.withdrawalAmount,
            bankAccountLastFour = policy.bankAccountLastFour,
            customerGivenName = policy.customerGivenName,
            customerFamilyName = policy.customerFamilyName,
            customerEmail = policy.customerEmail,
            customerBirthDate = policy.customerBirthDate,
            customerAddress = policy.customerAddress,
            customerPortalUrl = "${customerBaseUrl.trimEnd('/')}/irish-life/existing-business/customer/$id",
            requestedAt = requestTime,
            statuses = listOf(
                ExistingBusinessCaseStatusEntry(
                    ExistingBusinessCaseStatusCode.WITHDRAWAL_REQUEST_RECEIVED,
                    "Withdrawal request received",
                    requestTime,
                ),
                ExistingBusinessCaseStatusEntry(
                    ExistingBusinessCaseStatusCode.AUTOMATED_CHECKS_STARTED,
                    "Automated checks started",
                    requestTime,
                ),
            ),
            notifications = listOf(
                ExistingBusinessNotificationEntry(
                    ExistingBusinessNotificationCode.WITHDRAWAL_REQUEST_ALERT,
                    "Withdrawal request received",
                    "Customer started an Existing Business withdrawal request for policy ${policy.policyNumber}.",
                    requestTime,
                ),
                ExistingBusinessNotificationEntry(
                    ExistingBusinessNotificationCode.AUTOMATED_CHECKS_STARTED_ALERT,
                    "Automated checks started",
                    "The verifier began the automatic proof and policy checks for this withdrawal request.",
                    requestTime,
                ),
            ),
        )
        store[id] = created
        return created
    }

    fun get(id: String): ExistingBusinessCase? = store[id]

    fun list(): List<ExistingBusinessCase> =
        store.values.sortedByDescending { it.requestedAt }

    fun update(id: String, update: ExistingBusinessCase.() -> ExistingBusinessCase): ExistingBusinessCase {
        val current = requireNotNull(store[id]) { "Case not found: $id" }
        val updated = current.update()
        store[id] = updated
        return updated
    }
}

internal data class DemoExistingBusinessPolicyRecord(
    val policyNumber: String,
    val productName: String,
    val withdrawalAmount: String,
    val bankAccountLastFour: String,
    val customerGivenName: String,
    val customerFamilyName: String,
    val customerEmail: String,
    val customerBirthDate: String,
    val customerAddress: String,
)

@Serializable
internal data class CreateExistingBusinessCaseRequestTO(
    val policyNumber: String,
)

@Serializable
internal data class CompleteExistingBusinessCaseRequestTO(
    val transactionId: String? = null,
    val success: Boolean,
    val reason: String? = null,
    val validation: ExistingBusinessValidationSummaryTO? = null,
)

@Serializable
internal data class ExistingBusinessValidationSummaryTO(
    val matchedGivenName: Boolean? = null,
    val matchedFamilyName: Boolean? = null,
    val matchedBirthDate: Boolean? = null,
    val matchedAddress: Boolean? = null,
    val credentialExpired: Boolean? = null,
    val credentialExpiry: String? = null,
    val reason: String? = null,
    val claimsSnapshot: JsonObject? = null,
)

internal data class ExistingBusinessCase(
    val id: String,
    val policyNumber: String,
    val claimReference: String,
    val productName: String,
    val withdrawalAmount: String,
    val bankAccountLastFour: String,
    val customerGivenName: String,
    val customerFamilyName: String,
    val customerEmail: String,
    val customerBirthDate: String,
    val customerAddress: String,
    val customerPortalUrl: String,
    val requestedAt: Instant,
    val walletDeepLink: String? = null,
    val activeTransaction: FrontendActiveTransactionTO? = null,
    val inviteEmailSent: Boolean = false,
    val completionEmailSent: Boolean = false,
    val amlRecordFound: Boolean = false,
    val policyApplicationMatched: Boolean = false,
    val automatedDecision: String? = null,
    val statuses: List<ExistingBusinessCaseStatusEntry>,
    val notifications: List<ExistingBusinessNotificationEntry>,
    val validation: ExistingBusinessValidationSummaryTO? = null,
    val failureReason: String? = null,
) {
    fun toSummary(): ExistingBusinessCaseSummaryTO = ExistingBusinessCaseSummaryTO(
        caseId = id,
        policyNumber = policyNumber,
        claimReference = claimReference,
        productName = productName,
        withdrawalAmount = withdrawalAmount,
        bankAccountLastFour = bankAccountLastFour,
        customerGivenName = customerGivenName,
        customerFamilyName = customerFamilyName,
        customerEmail = customerEmail,
        customerBirthDate = customerBirthDate,
        customerAddress = customerAddress,
        requestedAt = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(requestedAt.atOffset(ZoneOffset.UTC)),
        currentStatus = statuses.last().code,
        statuses = statuses.map { it.toTO() },
        notifications = notifications.map { it.toTO() },
        customerPortalUrl = customerPortalUrl,
        walletDeepLink = walletDeepLink,
        activeTransaction = activeTransaction,
        inviteEmailSent = inviteEmailSent,
        completionEmailSent = completionEmailSent,
        amlRecordFound = amlRecordFound,
        policyApplicationMatched = policyApplicationMatched,
        automatedDecision = automatedDecision,
        failureReason = failureReason,
        validation = validation,
    )
}

internal data class ExistingBusinessCaseStatusEntry(
    val code: ExistingBusinessCaseStatusCode,
    val label: String,
    val at: Instant,
) {
    fun toTO(): ExistingBusinessCaseStatusTO = ExistingBusinessCaseStatusTO(
        code = code,
        label = label,
        at = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(at.atOffset(ZoneOffset.UTC)),
    )
}

internal data class ExistingBusinessNotificationEntry(
    val code: ExistingBusinessNotificationCode,
    val label: String,
    val message: String,
    val at: Instant,
) {
    fun toTO(): ExistingBusinessNotificationTO = ExistingBusinessNotificationTO(
        code = code,
        label = label,
        message = message,
        at = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(at.atOffset(ZoneOffset.UTC)),
    )
}

@Serializable
internal enum class ExistingBusinessCaseStatusCode {
    WITHDRAWAL_REQUEST_RECEIVED,
    AUTOMATED_CHECKS_STARTED,
    PROOF_INVITE_SENT,
    PROOFS_RECEIVED,
    PROOFS_VERIFIED,
    POLICY_APPLICATION_MATCHED,
    AML_RECORD_NOT_FOUND,
    AUTOMATED_DECISION_RECORDED,
    CUSTOMER_NOTIFIED,
    COMPLETED,
    FAILED,
}

@Serializable
internal enum class ExistingBusinessNotificationCode {
    WITHDRAWAL_REQUEST_ALERT,
    AUTOMATED_CHECKS_STARTED_ALERT,
    PROOF_REQUESTED_ALERT,
    PROOFS_SUBMITTED_ALERT,
    PROOFS_READY_ALERT,
    AML_CHECK_COMPLETED_ALERT,
    DECISION_READY_ALERT,
    MANUAL_REVIEW_ALERT,
}

@Serializable
internal data class ExistingBusinessCaseStatusTO(
    val code: ExistingBusinessCaseStatusCode,
    val label: String,
    val at: String,
)

@Serializable
internal data class ExistingBusinessNotificationTO(
    val code: ExistingBusinessNotificationCode,
    val label: String,
    val message: String,
    val at: String,
)

@Serializable
internal data class ExistingBusinessCaseSummaryTO(
    val caseId: String,
    val policyNumber: String,
    val claimReference: String,
    val productName: String,
    val withdrawalAmount: String,
    val bankAccountLastFour: String,
    val customerGivenName: String,
    val customerFamilyName: String,
    val customerEmail: String,
    val customerBirthDate: String,
    val customerAddress: String,
    val requestedAt: String,
    val currentStatus: ExistingBusinessCaseStatusCode,
    val statuses: List<ExistingBusinessCaseStatusTO>,
    val notifications: List<ExistingBusinessNotificationTO>,
    val customerPortalUrl: String,
    val walletDeepLink: String? = null,
    val activeTransaction: FrontendActiveTransactionTO? = null,
    val inviteEmailSent: Boolean,
    val completionEmailSent: Boolean,
    val amlRecordFound: Boolean,
    val policyApplicationMatched: Boolean,
    val automatedDecision: String? = null,
    val failureReason: String? = null,
    val validation: ExistingBusinessValidationSummaryTO? = null,
)