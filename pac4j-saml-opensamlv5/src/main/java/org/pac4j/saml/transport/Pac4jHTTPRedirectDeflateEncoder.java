package org.pac4j.saml.transport;

import net.shibboleth.shared.codec.Base64Support;
import net.shibboleth.shared.collection.Pair;
import net.shibboleth.shared.component.ComponentInitializationException;
import net.shibboleth.shared.net.URLBuilder;
import net.shibboleth.shared.xml.SerializeSupport;
import org.opensaml.core.xml.XMLObject;
import org.opensaml.core.xml.io.MarshallingException;
import org.opensaml.core.xml.util.XMLObjectSupport;
import org.opensaml.messaging.context.MessageContext;
import org.opensaml.messaging.encoder.AbstractMessageEncoder;
import org.opensaml.messaging.encoder.MessageEncodingException;
import org.opensaml.saml.common.SAMLObject;
import org.opensaml.saml.common.SignableSAMLObject;
import org.opensaml.saml.common.binding.BindingException;
import org.opensaml.saml.common.binding.SAMLBindingSupport;
import org.opensaml.saml.common.messaging.SAMLMessageSecuritySupport;
import org.opensaml.saml.saml2.core.RequestAbstractType;
import org.opensaml.saml.saml2.core.StatusResponseType;
import org.opensaml.security.credential.Credential;
import org.opensaml.security.credential.CredentialSupport;
import org.opensaml.xmlsec.SignatureSigningParameters;
import org.opensaml.xmlsec.crypto.XMLSigningUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;

import java.io.ByteArrayOutputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * Pac4j implementation extending directly the {@link AbstractMessageEncoder} as intermediate classes use the JEE HTTP response.
 * It's mostly a copy/paste of the source code of these intermediate opensaml classes.
 *
 * @author Misagh Moayyed
 * @since 1.8
 */
public class Pac4jHTTPRedirectDeflateEncoder extends AbstractMessageEncoder {

    private static final Logger log = LoggerFactory.getLogger(Pac4jHTTPRedirectDeflateEncoder.class);

    private final Pac4jSAMLResponse responseAdapter;
    private final boolean isAuthnRequestSigned;

    public Pac4jHTTPRedirectDeflateEncoder(final Pac4jSAMLResponse responseAdapter,
                                           final boolean isAuthnRequestSigned) {
        this.responseAdapter = responseAdapter;
        this.isAuthnRequestSigned = isAuthnRequestSigned;
    }

    @Override
    protected void doEncode() throws MessageEncodingException {
        final var messageContext = this.getMessageContext();
        final var outboundMessage = (SAMLObject)messageContext.getMessage();
        final var endpointURL = this.getEndpointURL(messageContext).toString();

        this.removeSignature(outboundMessage);

        final var encodedMessage = this.deflateAndBase64Encode(outboundMessage);
        final var redirectURL = this.buildRedirectURL(messageContext, endpointURL, encodedMessage);

        responseAdapter.init();
        responseAdapter.setRedirectUrl(redirectURL);
    }

    @Override
    protected void doInitialize() throws ComponentInitializationException {
        log.debug("Initialized {}", this.getClass().getSimpleName());
    }

    /**
     * Gets the response URL from the message context.
     *
     * @param messageContext current message context
     *
     * @return response URL from the message context
     *
     * @throws MessageEncodingException throw if no relying party endpoint is available
     */
    protected URI getEndpointURL(final MessageContext messageContext) throws MessageEncodingException {
        try {
            return SAMLBindingSupport.getEndpointURL(messageContext);
        } catch (final BindingException e) {
            throw new MessageEncodingException("Could not obtain message endpoint URL", e);
        }
    }

    /**
     * Removes the signature from the protocol message.
     *
     * @param message current message context
     */
    protected void removeSignature(final SAMLObject message) {
        if (message instanceof SignableSAMLObject) {
            final var signableMessage = (SignableSAMLObject) message;
            if (signableMessage.isSigned()) {
                log.debug("Removing SAML protocol message signature");
                signableMessage.setSignature(null);
            }
        }
    }

    /**
     * DEFLATE (RFC1951) compresses the given SAML message.
     *
     * @param message SAML message
     *
     * @return DEFLATE compressed message
     *
     * @throws MessageEncodingException thrown if there is a problem compressing the message
     */
    String deflateAndBase64Encode(final SAMLObject message) throws MessageEncodingException {
        log.debug("Deflating and Base64 encoding SAML message");
        try {
            final var messageStr = SerializeSupport.nodeToString(marshallMessage(message));
            log.trace("Output XML message: {}", messageStr);

            final var bytesOut = new ByteArrayOutputStream();
            final var deflater = new Deflater(Deflater.DEFLATED, true);
            final var deflaterStream = new DeflaterOutputStream(bytesOut, deflater);
            deflaterStream.write(messageStr.getBytes(StandardCharsets.UTF_8));
            deflaterStream.finish();

            return Base64Support.encode(bytesOut.toByteArray(), Base64Support.UNCHUNKED);
        } catch (final Exception e) {
            throw new MessageEncodingException("Unable to DEFLATE and Base64 encode SAML message", e);
        }
    }

    /**
     * Helper method that marshalls the given message.
     *
     * @param message message the marshall and serialize
     *
     * @return marshalled message
     *
     * @throws MessageEncodingException thrown if the give message can not be marshalled into its DOM representation
     */
    protected Element marshallMessage(final XMLObject message) throws MessageEncodingException {
        log.debug("Marshalling message");

        try {
            return XMLObjectSupport.marshall(message);
        } catch (final MarshallingException e) {
            throw new MessageEncodingException("Error marshalling message", e);
        }
    }

    /**
     * Builds the URL to redirect the client to.
     *
     * @param messageContext current message context
     * @param endpoint endpoint URL to send encoded message to
     * @param message Deflated and Base64 encoded message
     *
     * @return URL to redirect client to
     *
     * @throws MessageEncodingException thrown if the SAML message is neither a RequestAbstractType or Response
     */
    protected String buildRedirectURL(final MessageContext messageContext, final String endpoint, final String message)
            throws MessageEncodingException {
        log.debug("Building URL to redirect client to");

        final URLBuilder urlBuilder;
        try {
            urlBuilder = new URLBuilder(endpoint);
        } catch (final MalformedURLException e) {
            throw new MessageEncodingException("Endpoint URL " + endpoint + " is not a valid URL", e);
        }

        final var queryParams = urlBuilder.getQueryParams();
        // remove the query parameters set below
        queryParams.removeIf(p ->
            p.getFirst().equals("SAMLRequest") ||
            p.getFirst().equals("SAMLResponse") ||
            p.getFirst().equals("RelayState") ||
            p.getFirst().equals("SigAlg") ||
            p.getFirst().equals("Signature"));

        final var outboundMessage = (SAMLObject) messageContext.getMessage();

        if (outboundMessage instanceof RequestAbstractType) {
            queryParams.add(new Pair<>("SAMLRequest", message));
        } else if (outboundMessage instanceof StatusResponseType) {
            queryParams.add(new Pair<>("SAMLResponse", message));
        } else {
            throw new MessageEncodingException(
                    "SAML message is neither a SAML RequestAbstractType or StatusResponseType");
        }

        final var relayState = SAMLBindingSupport.getRelayState(messageContext);
        if (SAMLBindingSupport.checkRelayState(relayState)) {
            queryParams.add(new Pair<>("RelayState", relayState));
        }

        if (isAuthnRequestSigned) {
            final var signingParameters =
                    SAMLMessageSecuritySupport.getContextSigningParameters(messageContext);
            if (signingParameters != null && signingParameters.getSigningCredential() != null) {
                final var sigAlgURI =  getSignatureAlgorithmURI(signingParameters);
                final var sigAlg = new Pair<String, String>("SigAlg", sigAlgURI);
                queryParams.add(sigAlg);
                final var sigMaterial = urlBuilder.buildQueryString();

                queryParams.add(new Pair<>("Signature", generateSignature(
                        signingParameters.getSigningCredential(), sigAlgURI, sigMaterial)));
            } else {
                log.debug("No signing credential was supplied, skipping HTTP-Redirect DEFLATE signing");
            }
        }

        return urlBuilder.buildURL();
    }

    /**
     * Gets the signature algorithm URI to use.
     *
     * @param signingParameters the signing parameters to use
     *
     * @return signature algorithm to use with the associated signing credential
     *
     * @throws MessageEncodingException thrown if the algorithm URI is not supplied explicitly and
     *          could not be derived from the supplied credential
     */
    protected String getSignatureAlgorithmURI(final SignatureSigningParameters signingParameters)
            throws MessageEncodingException {

        if (signingParameters.getSignatureAlgorithm() != null) {
            return signingParameters.getSignatureAlgorithm();
        }

        throw new MessageEncodingException("The signing algorithm URI could not be determined");
    }

    /**
     * Generates the signature over the query string.
     *
     * @param signingCredential credential that will be used to sign query string
     * @param algorithmURI algorithm URI of the signing credential
     * @param queryString query string to be signed
     *
     * @return base64 encoded signature of query string
     *
     * @throws MessageEncodingException there is an error computing the signature
     */
    protected String generateSignature(final Credential signingCredential, final String algorithmURI, final String queryString)
            throws MessageEncodingException {

        log.debug(String.format("Generating signature with key type '%s', algorithm URI '%s' over query string '%s'",
                CredentialSupport.extractSigningKey(signingCredential).getAlgorithm(), algorithmURI, queryString));

        final String b64Signature;
        try {
            final var rawSignature =
                    XMLSigningUtil.signWithURI(signingCredential, algorithmURI, queryString.getBytes(StandardCharsets.UTF_8));
            b64Signature = Base64Support.encode(rawSignature, Base64Support.UNCHUNKED);
            log.debug("Generated digital signature value (base64-encoded) {}", b64Signature);
        } catch (final Exception e) {
            throw new MessageEncodingException("Unable to sign URL query string", e);
        }

        return b64Signature;
    }
}
