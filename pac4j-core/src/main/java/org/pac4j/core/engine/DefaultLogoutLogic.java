package org.pac4j.core.engine;

import org.pac4j.core.client.Client;
import org.pac4j.core.config.Config;
import org.pac4j.core.context.HttpConstants;
import org.pac4j.core.context.WebContext;
import org.pac4j.core.context.session.SessionStore;
import org.pac4j.core.exception.http.HttpAction;
import org.pac4j.core.exception.http.NoContentAction;
import org.pac4j.core.http.adapter.HttpActionAdapter;
import org.pac4j.core.util.HttpActionHelper;
import org.pac4j.core.util.Pac4jConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Pattern;

import static org.pac4j.core.util.CommonHelper.*;

/**
 * <p>Default logout logic:</p>
 *
 * <p>If the <code>localLogout</code> property is <code>true</code>, the pac4j profiles are removed from the web session
 * (and the web session is destroyed if the <code>destroySession</code> property is <code>true</code>).</p>
 *
 * <p>A post logout action is computed as the redirection to the <code>url</code> request parameter if it matches the
 * <code>logoutUrlPattern</code> or to the <code>defaultUrl</code> if it is defined or as a blank page otherwise.</p>
 *
 * <p>If the <code>centralLogout</code> property is <code>true</code>, the user is redirected to the identity provider
 * for a central logout and then optionally to the post logout redirection URL (if it's supported by the identity provider and if it's an
 * absolute URL). If no central logout is defined, the post logout action is performed directly.</p>
 *
 * @author Jerome Leleu
 * @since 1.9.0
 */
public class DefaultLogoutLogic extends AbstractExceptionAwareLogic implements LogoutLogic {

    public static final DefaultLogoutLogic INSTANCE = new DefaultLogoutLogic();

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultLogoutLogic.class);

    @Override
    public Object perform(final WebContext context, final SessionStore sessionStore, final Config config,
                          final HttpActionAdapter httpActionAdapter, final String defaultUrl, final String inputLogoutUrlPattern,
                          final Boolean inputLocalLogout, final Boolean inputDestroySession, final Boolean inputCentralLogout) {

        LOGGER.debug("=== LOGOUT ===");

        HttpAction action;
        try {

            // default values
            final String logoutUrlPattern;
            if (inputLogoutUrlPattern == null) {
                logoutUrlPattern = Pac4jConstants.DEFAULT_LOGOUT_URL_PATTERN_VALUE;
            } else {
                logoutUrlPattern = inputLogoutUrlPattern;
            }
            final var localLogout = inputLocalLogout == null || inputLocalLogout;
            final var destroySession = inputDestroySession != null && inputDestroySession;
            final var centralLogout = inputCentralLogout != null && inputCentralLogout;

            // checks
            assertNotNull("context", context);
            assertNotNull("config", config);
            assertNotNull("httpActionAdapter", httpActionAdapter);
            assertNotBlank(Pac4jConstants.LOGOUT_URL_PATTERN, logoutUrlPattern);
            final var configClients = config.getClients();
            assertNotNull("configClients", configClients);

            // logic
            final var manager = getProfileManager(context, sessionStore);
            manager.setConfig(config);
            final var profiles = manager.getProfiles();

            // compute redirection URL
            final var url = context.getRequestParameter(Pac4jConstants.URL);
            var redirectUrl = defaultUrl;
            if (url.isPresent() && Pattern.matches(logoutUrlPattern, url.get())) {
                redirectUrl = url.get();
            }
            LOGGER.debug("redirectUrl: {}", redirectUrl);
            if (redirectUrl != null) {
                action = HttpActionHelper.buildRedirectUrlAction(context, redirectUrl);
            } else {
                action = NoContentAction.INSTANCE;
            }

            // local logout if requested or multiple profiles
            if (localLogout || profiles.size() > 1) {
                LOGGER.debug("Performing application logout");
                manager.removeProfiles();
                if (destroySession) {
                    if (sessionStore != null) {
                        final var removed = sessionStore.destroySession(context);
                        if (!removed) {
                            LOGGER.error("Unable to destroy the web session. The session store may not support this feature");
                        }
                    } else {
                        LOGGER.error("No session store available for this web context");
                    }
                }
            }

            // central logout
            if (centralLogout) {
                LOGGER.debug("Performing central logout");
                for (final var profile : profiles) {
                    LOGGER.debug("Profile: {}", profile);
                    final var clientName = profile.getClientName();
                    if (clientName != null) {
                        final var client = configClients.findClient(clientName);
                        if (client.isPresent()) {
                            String targetUrl = null;
                            if (redirectUrl != null) {
                                redirectUrl = enhanceRedirectUrl(config, client.get(), context, sessionStore, redirectUrl);
                                if (redirectUrl.startsWith(HttpConstants.SCHEME_HTTP) ||
                                    redirectUrl.startsWith(HttpConstants.SCHEME_HTTPS)) {
                                    targetUrl = redirectUrl;
                                }
                            }
                            final var logoutAction =
                                client.get().getLogoutAction(context, sessionStore, profile, targetUrl);
                            LOGGER.debug("Logout action: {}", logoutAction);
                            if (logoutAction.isPresent()) {
                                action = logoutAction.get();
                                break;
                            }
                        }
                    }
                }
            }

        } catch (final RuntimeException e) {
            return handleException(e, httpActionAdapter, context);
        }

        return httpActionAdapter.adapt(action, context);
    }

    protected String enhanceRedirectUrl(final Config config, final Client client, final WebContext context,
                                        final SessionStore sessionStore, final String redirectUrl) {
        return redirectUrl;
    }

    @Override
    public String toString() {
        return toNiceString(this.getClass(), "errorUrl", getErrorUrl());
    }
}
