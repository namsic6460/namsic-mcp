package lkd.namsic.mcp.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserUrlValidatorTest {

    @Test
    void allowsRegularHttpAndHttps() {
        assertTrue(BrowserUrlValidator.validate("http://example.com").allowed());
        assertTrue(BrowserUrlValidator.validate("https://example.com/path?q=1").allowed());
    }

    @Test
    void allowsLocalhostDevServerPorts() {
        assertTrue(BrowserUrlValidator.validate("http://localhost:10000").allowed());
        assertTrue(BrowserUrlValidator.validate("http://localhost:10500/app").allowed());
        assertTrue(BrowserUrlValidator.validate("http://127.0.0.1:10999").allowed());
        assertTrue(BrowserUrlValidator.validate("http://127.0.0.1:8080").allowed());
    }

    @Test
    void blocksFileScheme() {
        assertFalse(BrowserUrlValidator.validate("file:///etc/passwd").allowed());
    }

    @Test
    void blocksJavascriptScheme() {
        assertFalse(BrowserUrlValidator.validate("javascript:alert(1)").allowed());
    }

    @Test
    void blocksDataScheme() {
        assertFalse(BrowserUrlValidator.validate("data:text/html,<script>alert(1)</script>").allowed());
    }

    @Test
    void blocksCloudMetadataHosts() {
        assertFalse(BrowserUrlValidator.validate("http://169.254.169.254/latest/meta-data/").allowed());
        assertFalse(BrowserUrlValidator.validate("http://metadata.google.internal/computeMetadata/v1/").allowed());
        assertFalse(BrowserUrlValidator.validate("http://100.100.100.200/").allowed());
    }

    @Test
    void blocksIpv6LoopbackAndPrivateRanges() {
        assertFalse(BrowserUrlValidator.validate("http://[::1]:8080/").allowed());
        assertFalse(BrowserUrlValidator.validate("http://[fe80::1]/").allowed());
        assertFalse(BrowserUrlValidator.validate("http://[fd12:3456:789a::1]/").allowed());
    }

    @Test
    void blocksCommonBackendPorts() {
        assertFalse(BrowserUrlValidator.validate("http://localhost:22").allowed());
        assertFalse(BrowserUrlValidator.validate("http://localhost:3306").allowed());
        assertFalse(BrowserUrlValidator.validate("http://localhost:5432").allowed());
        assertFalse(BrowserUrlValidator.validate("http://localhost:6379").allowed());
        assertFalse(BrowserUrlValidator.validate("http://localhost:27017").allowed());
        assertFalse(BrowserUrlValidator.validate("http://localhost:2375").allowed());
    }

    @Test
    void blocksPrivilegedPortsExceptHttp() {
        assertFalse(BrowserUrlValidator.validate("http://example.com:25").allowed());
        assertFalse(BrowserUrlValidator.validate("http://example.com:110").allowed());
        assertTrue(BrowserUrlValidator.validate("http://example.com:80").allowed());
        assertTrue(BrowserUrlValidator.validate("https://example.com:443").allowed());
    }

    @Test
    void rejectsEmptyOrBlankUrl() {
        assertFalse(BrowserUrlValidator.validate(null).allowed());
        assertFalse(BrowserUrlValidator.validate("").allowed());
        assertFalse(BrowserUrlValidator.validate("   ").allowed());
    }

    @Test
    void rejectsMalformedUrl() {
        assertFalse(BrowserUrlValidator.validate("ht tp://broken").allowed());
    }

    @Test
    void rejectsUrlWithoutHost() {
        assertFalse(BrowserUrlValidator.validate("http://").allowed());
    }
}
