package eu.kanade.tachiyomi.network

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.CONCURRENT)
class DnsBlockageTest {

    @Test
    fun poisonAddressFailureIsBlockedOrUnreachable() {
        evaluateDnsFailure(
            "Failed to connect to api.mangadex.org/202.169.44.80:443",
            dohEnabled = false,
        ) shouldBe DnsFailure.BlockedOrUnreachable
    }

    @Test
    fun secondKnownPrefixIsBlockedOrUnreachable() {
        evaluateDnsFailure(
            "Failed to connect to nyaa.si/103.52.52.10:443",
            dohEnabled = false,
        ) shouldBe DnsFailure.BlockedOrUnreachable
    }

    @Test
    fun ipv6AddressFailureIsNotDns() {
        evaluateDnsFailure(
            "Failed to connect to api.mangadex.org/2606:4700::64:443",
            dohEnabled = false,
        ) shouldBe DnsFailure.NotDns
        dnsFailureShape("Failed to connect to e-hentai.org/[2606:4700::64]:443") shouldBe
            DnsFailure.NotDns
    }

    @Test
    fun unresolvableHostIsBlockedOrUnreachable() {
        evaluateDnsFailure(
            "Unable to resolve host api.mangadex.org: no information for query",
            dohEnabled = false,
        ) shouldBe DnsFailure.BlockedOrUnreachable
    }

    @Test
    fun realIpFailureIsNotDns() {
        evaluateDnsFailure(
            "Failed to connect to api.mangadex.org/45.129.229.1:443",
            dohEnabled = false,
        ) shouldBe DnsFailure.NotDns
    }

    @Test
    fun addressOutsideKnownPrefixIsNotDns() {
        evaluateDnsFailure(
            "Failed to connect to api.mangadex.org/202.169.45.80:443",
            dohEnabled = false,
        ) shouldBe DnsFailure.NotDns
        evaluateDnsFailure(
            "Failed to connect to api.mangadex.org/104.16.0.1:443",
            dohEnabled = false,
        ) shouldBe DnsFailure.NotDns
    }

    @Test
    fun offerIsSuppressedWhenDohAlreadyEnabled() {
        evaluateDnsFailure(
            "Failed to connect to api.mangadex.org/202.169.44.80:443",
            dohEnabled = true,
        ) shouldBe DnsFailure.NotDns
    }

    @Test
    fun unrelatedMessagesAreNotDns() {
        evaluateDnsFailure(null, dohEnabled = false) shouldBe DnsFailure.NotDns
        evaluateDnsFailure("", dohEnabled = false) shouldBe DnsFailure.NotDns
        evaluateDnsFailure("HTTP error 503", dohEnabled = false) shouldBe DnsFailure.NotDns
        evaluateDnsFailure("timeout", dohEnabled = false) shouldBe DnsFailure.NotDns
    }

    @Test
    fun shapeStaysDnsShapedWhenDohAlreadyEnabled() {
        dnsFailureShape("Failed to connect to api.mangadex.org/202.169.44.80:443") shouldBe
            DnsFailure.BlockedOrUnreachable
        dnsFailureShape("Unable to resolve host api.mangadex.org: no information for query") shouldBe
            DnsFailure.BlockedOrUnreachable
    }

    @Test
    fun shapeNotDnsForNonAddressMessages() {
        dnsFailureShape(null) shouldBe DnsFailure.NotDns
        dnsFailureShape("") shouldBe DnsFailure.NotDns
        dnsFailureShape("HTTP error 503") shouldBe DnsFailure.NotDns
        dnsFailureShape("Failed to connect to api.mangadex.org/45.129.229.1:443") shouldBe
            DnsFailure.NotDns
    }

    @Test
    fun connectFailureYieldsBareHost() {
        dnsFailureHost("Failed to connect to api.mangadex.org/202.169.44.80:443") shouldBe
            "api.mangadex.org"
        dnsFailureHost("Failed to connect to e-hentai.org/[2606:4700::64]:443") shouldBe
            "e-hentai.org"
    }

    @Test
    fun unresolvedHostYieldsDomain() {
        dnsFailureHost("""Unable to resolve host "api.mangadex.org": No address associated with hostname""") shouldBe
            "api.mangadex.org"
        dnsFailureHost("Unable to resolve host api.mangadex.org: no information for query") shouldBe
            "api.mangadex.org"
    }

    @Test
    fun otherMessagesYieldNoHost() {
        dnsFailureHost("HTTP error 503") shouldBe null
        dnsFailureHost(null) shouldBe null
    }

    @Test
    fun offlineAddressFailureIsExplainedAsNoConnection() {
        chooseFailureText(
            "Failed to connect to api.mangadex.org/202.169.44.80:443",
            online = false,
        ) shouldBe FailureText.Offline
        chooseFailureText(
            "connect failed: ENETUNREACH (Network is unreachable)",
            online = false,
        ) shouldBe FailureText.Offline
    }

    @Test
    fun offlineLocalFileFailureKeepsItsOwnMessage() {
        chooseFailureText(
            "No such file or directory: /storage/emulated/0/manga/chapter1.cbz",
            online = false,
        ) shouldBe FailureText.Raw
        chooseFailureText("zip file is closed", online = false) shouldBe FailureText.Raw
    }

    @Test
    fun onlinePoisonedAddressFailureNamesTheDomain() {
        chooseFailureText(
            "Failed to connect to api.mangadex.org/202.169.44.80:443",
            online = true,
        ) shouldBe FailureText.DnsBlocked
        chooseFailureText(
            "Unable to resolve host api.mangadex.org: no information for query",
            online = true,
        ) shouldBe FailureText.DnsBlocked
    }

    @Test
    fun onlineNonAddressFailureKeepsItsOwnMessage() {
        chooseFailureText("HTTP error 503", online = true) shouldBe FailureText.Raw
        chooseFailureText("timeout", online = true) shouldBe FailureText.Raw
        chooseFailureText("Failed to connect to localhost/127.0.0.1:9", online = true) shouldBe
            FailureText.Raw
    }
}
