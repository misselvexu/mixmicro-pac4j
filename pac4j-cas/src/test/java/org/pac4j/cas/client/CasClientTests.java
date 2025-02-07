package org.pac4j.cas.client;

import org.junit.Test;
import org.pac4j.cas.config.CasConfiguration;
import org.pac4j.core.context.MockWebContext;
import org.pac4j.core.context.WebContext;
import org.pac4j.core.context.session.MockSessionStore;
import org.pac4j.core.exception.http.FoundAction;
import org.pac4j.core.exception.http.HttpAction;
import org.pac4j.core.exception.http.WithContentAction;
import org.pac4j.core.http.callback.CallbackUrlResolver;
import org.pac4j.core.http.url.UrlResolver;
import org.pac4j.core.util.Pac4jConstants;
import org.pac4j.core.util.TestsConstants;
import org.pac4j.core.util.TestsHelper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.Deflater;

import static org.junit.Assert.*;
import static org.pac4j.core.context.HttpConstants.FOUND;
import static org.pac4j.core.context.HttpConstants.HTTP_METHOD;

/**
 * This class tests the {@link CasClient} class.
 *
 * @author Jerome Leleu
 * @since 1.4.0
 */
public final class CasClientTests implements TestsConstants {

    private static final String CAS = "/cas";
    private static final String CASBACK = "/casback";
    private static final String HOST = "protocol://myHost";
    private static final String LOGIN = "/login";
    private static final String PREFIX_URL = "http://myserver/";
    private static final String PREFIX_URL_WITHOUT_SLASH = "http://myserver";
    private static final String LOGOUT_MESSAGE = "\"<samlp:LogoutRequest xmlns:samlp=\\\"urn:oasis:names:tc:SAML:2.0:protocol\\\""
        + "ID=\\\"LR-1-B2b0CVRW5eSvPBZPsAVXdNPj7jee4SWjr9y\\\" Version=\\\"2.0\\\" IssueInstant=\\\"2012-12-19T15:30:55Z\\\">"
        + "<saml:NameID xmlns:saml=\\\"urn:oasis:names:tc:SAML:2.0:assertion\\\">@NOT_USED@</saml:NameID><samlp:SessionIndex>\""
        + TICKET + "\"</samlp:SessionIndex></samlp:LogoutRequest>\";";

    @Test
    public void testMissingCasUrls() {
        final var casClient = new CasClient();
        casClient.setCallbackUrl(CALLBACK_URL);
        TestsHelper.initShouldFail(casClient.getConfiguration(), "loginUrl, prefixUrl and restUrl cannot be all blank");
    }

    @Test
    public void testMissingSlashOnPrefixUrl() {
        final var configuration = new CasConfiguration();
        configuration.setLoginUrl(LOGIN_URL);
        configuration.setPrefixUrl(PREFIX_URL_WITHOUT_SLASH);
        final var casClient = new CasClient(configuration);
        casClient.setCallbackUrl(CALLBACK_URL);
        configuration.init();
        assertEquals(PREFIX_URL, configuration.getPrefixUrl());
    }

    @Test
    public void testInitPrefixUrl() {
        final var configuration = new CasConfiguration();
        configuration.setLoginUrl(LOGIN_URL);
        final var casClient = new CasClient(configuration);
        casClient.setCallbackUrl(CALLBACK_URL);
        assertEquals(null, configuration.getPrefixUrl());
        configuration.init();
        assertEquals(PREFIX_URL, configuration.getPrefixUrl());
    }

    @Test
    public void testInitLoginUrl() {
        final var configuration = new CasConfiguration();
        configuration.setPrefixUrl(PREFIX_URL);
        final var casClient = new CasClient(configuration);
        casClient.setCallbackUrl(CALLBACK_URL);
        assertEquals(null, configuration.getLoginUrl());
        configuration.init();
        assertEquals(LOGIN_URL, configuration.getLoginUrl());
    }

    @Test
    public void testCallbackUrlResolver() {
        final var configuration = new CasConfiguration();
        configuration.setPrefixUrl(CAS);
        configuration.setLoginUrl(CAS + LOGIN);
        final var casClient = new CasClient(configuration);
        casClient.setCallbackUrl(CASBACK);
        casClient.setUrlResolver((url, context) -> HOST + url);
        casClient.setCallbackUrlResolver(new CallbackUrlResolver() {
            @Override
            public String compute(final UrlResolver urlResolver, final String url, final String clientName, final WebContext context) {
                return null;
            }

            @Override
            public boolean matches(final String clientName, final WebContext context) {
                return false;
            }
        });
        casClient.init();
        assertEquals(HOST + CAS + LOGIN, configuration.computeFinalLoginUrl(null));
        assertEquals(HOST + CAS + "/", configuration.computeFinalPrefixUrl(null));
    }

    @Test
    public void testRenewMissing() {
        final var configuration = new CasConfiguration();
        configuration.setLoginUrl(LOGIN_URL);
        final var casClient = new CasClient(configuration);
        casClient.setCallbackUrl(CALLBACK_URL);
        final var context = MockWebContext.create();
        final var action = (FoundAction) casClient.getRedirectionAction(context, new MockSessionStore()).get();
        assertFalse(action.getLocation().indexOf("renew=true") >= 0);
    }

    @Test
    public void testRenew() {
        final var configuration = new CasConfiguration();
        configuration.setLoginUrl(LOGIN_URL);
        final var casClient = new CasClient(configuration);
        casClient.setCallbackUrl(CALLBACK_URL);
        configuration.setRenew(true);
        final var context = MockWebContext.create();
        final var action = (FoundAction) casClient.getRedirectionAction(context, new MockSessionStore()).get();
        assertTrue(action.getLocation().indexOf("renew=true") >= 0);
    }

    @Test
    public void testGatewayMissing() {
        final var configuration = new CasConfiguration();
        configuration.setLoginUrl(LOGIN_URL);
        final var casClient = new CasClient(configuration);
        casClient.setCallbackUrl(CALLBACK_URL);
        final var context = MockWebContext.create();
        final var action = (FoundAction) casClient.getRedirectionAction(context, new MockSessionStore()).get();
        assertFalse(action.getLocation().indexOf("gateway=true") >= 0);
    }

    @Test
    public void testGatewayOK() {
        final var configuration = new CasConfiguration();
        configuration.setLoginUrl(LOGIN_URL);
        final var casClient = new CasClient(configuration);
        casClient.setCallbackUrl(CALLBACK_URL);
        final var context = MockWebContext.create();
        configuration.setGateway(true);
        final var action = (FoundAction) casClient.getRedirectionAction(context, new MockSessionStore()).get();
        assertTrue(action.getLocation().indexOf("gateway=true") >= 0);
        final var credentials = casClient.getCredentials(context, new MockSessionStore());
        assertFalse(credentials.isPresent());
    }

    @Test
    public void testBackLogout() {
        final var configuration = new CasConfiguration();
        configuration.setLoginUrl(LOGIN_URL);
        final var casClient = new CasClient(configuration);
        casClient.setCallbackUrl(CALLBACK_URL);
        casClient.init();
        final var context = MockWebContext.create()
            .addRequestParameter(CasConfiguration.LOGOUT_REQUEST_PARAMETER, LOGOUT_MESSAGE)
            .setRequestMethod(HTTP_METHOD.POST.name());
        final var action = (HttpAction) TestsHelper.expectException(() -> casClient.getCredentials(context, new MockSessionStore()));
        assertEquals(204, action.getCode());
    }

    private String deflateAndBase64(final String data) {
        final var deflater = new Deflater();
        deflater.setInput(data.getBytes(StandardCharsets.UTF_8));
        deflater.finish();
        final var buffer = new byte[data.length()];
        final var resultSize = deflater.deflate(buffer);
        final var output = new byte[resultSize];
        System.arraycopy(buffer, 0, output, 0, resultSize);
        return Base64.getEncoder().encodeToString(output);
    }

    @Test
    public void testFrontLogout() {
        final var configuration = new CasConfiguration();
        configuration.setLoginUrl(LOGIN_URL);
        final var casClient = new CasClient(configuration);
        casClient.setCallbackUrl(CALLBACK_URL);
        casClient.init();
        final var context = MockWebContext.create()
                .addRequestParameter(CasConfiguration.LOGOUT_REQUEST_PARAMETER, deflateAndBase64(LOGOUT_MESSAGE))
                .setRequestMethod(HTTP_METHOD.GET.name());
        final var action = (HttpAction) TestsHelper.expectException(() -> casClient.getCredentials(context, new MockSessionStore()));
        assertEquals(200, action.getCode());
        assertEquals(Pac4jConstants.EMPTY_STRING, ((WithContentAction) action).getContent());
    }

    @Test
    public void testFrontLogoutWithRelayState() {
        final var configuration = new CasConfiguration();
        configuration.setLoginUrl(LOGIN_URL);
        final var casClient = new CasClient(configuration);
        casClient.setCallbackUrl(CALLBACK_URL);
        casClient.init();
        final var context = MockWebContext.create()
                .addRequestParameter(CasConfiguration.LOGOUT_REQUEST_PARAMETER, deflateAndBase64(LOGOUT_MESSAGE))
                .addRequestParameter(CasConfiguration.RELAY_STATE_PARAMETER, VALUE).setRequestMethod(HTTP_METHOD.GET.name());
        final var action = (HttpAction) TestsHelper.expectException(() -> casClient.getCredentials(context, new MockSessionStore()));
        assertEquals(FOUND, action.getCode());
    }

    @Test
    public void testInitUrlWithLoginString() {
        final var testCasLoginUrl = "https://login.foo.bar/login/login";
        final var testCasPrefixUrl = "https://login.foo.bar/login/";
        final var configuration = new CasConfiguration();
        configuration.setLoginUrl(testCasLoginUrl);
        final var casClient = new CasClient(configuration);
        casClient.setCallbackUrl(CALLBACK_URL);
        assertEquals(null, configuration.getPrefixUrl());
        configuration.init();
        assertEquals(testCasPrefixUrl, configuration.getPrefixUrl());
    }
}
