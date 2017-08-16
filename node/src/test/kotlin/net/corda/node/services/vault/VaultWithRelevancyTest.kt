package net.corda.node.services.vault

import net.corda.contracts.asset.DUMMY_CASH_ISSUER
import net.corda.contracts.asset.DUMMY_CASH_ISSUER_KEY
import net.corda.core.contracts.ContractState
import net.corda.core.contracts.FungibleAsset
import net.corda.core.node.services.Vault
import net.corda.core.node.services.VaultQueryService
import net.corda.core.node.services.VaultService
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.node.services.vault.builder
import net.corda.finance.DOLLARS
import net.corda.finance.USD
import net.corda.node.utilities.CordaPersistence
import net.corda.schemas.CashSchemaV1
import net.corda.testing.*
import net.corda.testing.contracts.fillWithSomeTestCash
import net.corda.testing.contracts.fillWithSomeTestDeals
import net.corda.testing.contracts.fillWithSomeTestLinearStates
import net.corda.testing.node.MockServices
import net.corda.testing.node.makeTestDatabaseAndMockServices
import org.assertj.core.api.Assertions
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.*

class VaultWithRelevancyTest : TestDependencyInjectionBase() {

    private lateinit var services: MockServices
    private lateinit var notaryServices: MockServices
    private val vaultSvc: VaultService get() = services.vaultService
    private val vaultQuerySvc: VaultQueryService get() = services.vaultQueryService
    private lateinit var database: CordaPersistence

    @Before
    fun setUp() {
        val databaseAndServices = makeTestDatabaseAndMockServices(keys = listOf(MEGA_CORP_KEY, DUMMY_NOTARY_KEY), storeIrrelevantStates = true)
        database = databaseAndServices.first
        services = databaseAndServices.second
        notaryServices = MockServices(DUMMY_NOTARY_KEY, DUMMY_CASH_ISSUER_KEY, BOC_KEY, MEGA_CORP_KEY)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `unconsumed states simple with irrelevant states`() {
        database.transaction {
            services.fillWithSomeTestCash(100.DOLLARS, notaryServices, DUMMY_NOTARY, 3, 3, Random(0L))
            services.fillWithSomeTestLinearStates(10)
            services.fillWithSomeTestDeals(listOf("123", "456", "789"), relevantToMe = false)

            vaultQuerySvc.queryBy<ContractState>(QueryCriteria.VaultQueryCriteria(relevancy = Vault.Relevancy.IRRELEVANT)).apply {
                Assertions.assertThat(states).hasSize(3)
                Assertions.assertThat(statesMetadata).hasSize(3)
            }
            vaultQuerySvc.queryBy<ContractState>(QueryCriteria.VaultQueryCriteria(relevancy = Vault.Relevancy.RELEVANT)).apply {
                Assertions.assertThat(states).hasSize(13)
                Assertions.assertThat(statesMetadata).hasSize(13)
            }
            vaultQuerySvc.queryBy<ContractState>(QueryCriteria.VaultQueryCriteria(relevancy = Vault.Relevancy.ALL)).apply {
                Assertions.assertThat(states).hasSize(16)
                Assertions.assertThat(statesMetadata).hasSize(16)
            }
        }
    }

    @Test
    fun `unconsumed cash balance for single currency with different relevancy`() {
        database.transaction {
            services.fillWithSomeTestCash(100.DOLLARS, notaryServices, DUMMY_NOTARY, 1, 1, Random(0L), ownedBy = DUMMY_CASH_ISSUER.party)
            services.fillWithSomeTestCash(200.DOLLARS, notaryServices, DUMMY_NOTARY, 2, 2, Random(0L), ownedBy = DUMMY_CASH_ISSUER.party)
            services.fillWithSomeTestCash(300.DOLLARS, notaryServices, DUMMY_NOTARY, 3, 3, Random(0L))
            services.fillWithSomeTestCash(400.DOLLARS, notaryServices, DUMMY_NOTARY, 4, 4, Random(0L))

            getBalance(Vault.Relevancy.RELEVANT).apply {
                Assertions.assertThat(otherResults).hasSize(2)
                Assertions.assertThat(otherResults[0]).isEqualTo(70000L)
                Assertions.assertThat(otherResults[1]).isEqualTo("USD")
            }

            getBalance(Vault.Relevancy.IRRELEVANT).apply {
                Assertions.assertThat(otherResults).hasSize(2)
                Assertions.assertThat(otherResults[0]).isEqualTo(30000L)
                Assertions.assertThat(otherResults[1]).isEqualTo("USD")
            }

            getBalance(Vault.Relevancy.ALL).apply {
                Assertions.assertThat(otherResults).hasSize(2)
                Assertions.assertThat(otherResults[0]).isEqualTo(100000L)
                Assertions.assertThat(otherResults[1]).isEqualTo("USD")
            }
        }
    }

    private fun getBalance(relevancy: Vault.Relevancy): Vault.Page<FungibleAsset<*>> {
        val sum = builder { CashSchemaV1.PersistentCashState::pennies.sum(groupByColumns = listOf(CashSchemaV1.PersistentCashState::currency)) }
        val sumCriteria = QueryCriteria.VaultCustomQueryCriteria(sum, relevancy = relevancy)

        val ccyIndex = builder { CashSchemaV1.PersistentCashState::currency.equal(USD.currencyCode) }
        val ccyCriteria = QueryCriteria.VaultCustomQueryCriteria(ccyIndex, relevancy = relevancy)

        return vaultQuerySvc.queryBy<FungibleAsset<*>>(sumCriteria.and(ccyCriteria))
    }
}